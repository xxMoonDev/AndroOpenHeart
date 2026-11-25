package dev.rootssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.util.Log
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import java.security.PublicKey
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

class SshService : Service() {

    companion object {
        private const val TAG = "AndroOpenHeart"
        private const val CHANNEL_ID = "ssh"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.rootssh.STOP"

        @Volatile var running = false
            private set
        @Volatile var status = "Stopped"
            private set
        @Volatile var fingerprint: String? = null
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SshService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SshService::class.java).setAction(ACTION_STOP))
        }

        fun ipAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private var server: SshServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("Starting…")
        if (server == null && !running) {
            running = true
            status = "Starting…"
            Thread({ startServer() }, "sshd-start").start()
        }
        return START_STICKY
    }

    private fun startServer() {
        try {
            val cfg = Config(this)
            val keyBlobs = cfg.authorizedKeyBlobs()
            val password = cfg.password
            val username = cfg.username
            require(password.isNotEmpty() || keyBlobs.isNotEmpty()) {
                "Set a password or add an authorized key first"
            }
            System.setProperty("user.home", filesDir.absolutePath)

            // Explicit key-exchange list: MINA's curve25519 availability probe crashes on
            // Android (it touches javax.management, which ART doesn't ship).
            val kex = NamedFactory.setUpTransformedFactories(
                true,
                listOf(
                    BuiltinDHFactories.ecdhp256, BuiltinDHFactories.ecdhp384, BuiltinDHFactories.ecdhp521,
                    BuiltinDHFactories.dhg16_512, BuiltinDHFactories.dhg18_512, BuiltinDHFactories.dhg14_256,
                ),
                ServerBuilder.DH2KEX,
            )
            val sshd: SshServer = ServerBuilder.builder().keyExchangeFactories(kex).build()
            sshd.port = cfg.port
            // Send small packets immediately (interactive shells, scrcpy control)
            CoreModuleProperties.TCP_NODELAY.set(sshd, true)

            val hostKey = SimpleGeneratorHostKeyProvider(File(filesDir, "ssh_host_ec_key").toPath())
            hostKey.algorithm = KeyUtils.EC_ALGORITHM
            hostKey.keySize = 256
            sshd.keyPairProvider = hostKey
            fingerprint = hostKey.loadKeys(null).firstOrNull()?.let { KeyUtils.getFingerPrint(it.public) }

            sshd.passwordAuthenticator = if (password.isEmpty()) null else
                PasswordAuthenticator { user, pw, _ ->
                    val ok = user == username && MessageDigest.isEqual(
                        pw.toByteArray(), password.toByteArray(),
                    )
                    if (!ok) Thread.sleep(1500) // slow down guessing
                    ok
                }
            sshd.keyboardInteractiveAuthenticator = null
            val authorized: List<PublicKey> = cfg.authorizedKeys.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    try {
                        AuthorizedKeyEntry.parseAuthorizedKeyEntry(line)
                            ?.resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.FAILING)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unusable authorized key: $line", e)
                        null
                    }
                }
            Log.i(TAG, "Loaded ${authorized.size} authorized key(s)")
            sshd.publickeyAuthenticator = if (authorized.isEmpty()) null else
                PublickeyAuthenticator { user, key, _ ->
                    val ok = user == username && authorized.any { KeyUtils.compareKeys(it, key) }
                    Log.i(TAG, "publickey auth user=$user type=${KeyUtils.getKeyType(key)} ok=$ok")
                    ok
                }

            val root = cfg.useRoot
            val wantShizuku = !root && cfg.useShizuku
            if (wantShizuku && !ShizukuShell.awaitBinder(3000) && ShizukuShell.starterPath(this) != null) {
                // e.g. after a reboot: bring Shizuku back up as the shell user if we have root
                ShizukuShell.startWithRoot(this)
            }
            val home = if (root) "/data/local/tmp" else filesDir.absolutePath
            val cwd = if (root) "/" else filesDir.absolutePath
            val shell = if (root) listOf("su") else listOf("/system/bin/sh", "-l")
            val ctx = applicationContext
            fun env(mode: String) = mapOf(
                "ROOTSSH_MODE" to mode,
                "ROOTSSH_PERMS" to Perms.granted(ctx).joinToString(","),
            )

            // a client may ask for one root session (env ROOTSSH_ROOT=1) even when sessions
            // normally run unprivileged; it uses the app's own su grant
            fun rootVariant(argv: List<String>) = if (root) null else
                ProcessCommand.RootVariant(argv, argv, env("root"))
            sshd.shellFactory = ShellFactory {
                ProcessCommand(shell, shell, home, cwd, env(if (root) "root" else "app"),
                    root = rootVariant(listOf("su")))
            }
            sshd.commandFactory = CommandFactory { _, command ->
                when {
                    root -> listOf("su", "-c", command).let {
                        ProcessCommand(it, it, home, cwd, env("root"))
                    }
                    wantShizuku && ShizukuShell.usable() -> listOf("/system/bin/sh", "-c", command).let {
                        // pipe sessions (what AndroUnderTheHood and scp/sftp-style tools use) run as
                        // the shell user; a pty request still gets a local app-user terminal
                        val shellArgv = it
                        ProcessCommand(it, shellArgv, "/data/local/tmp", "/", env("shizuku"),
                            spawner = { argv, e, dir -> ShizukuShell.spawn(argv, e, dir) },
                            root = rootVariant(listOf("su", "-c", command)))
                    }
                    else -> listOf("/system/bin/sh", "-c", command).let {
                        ProcessCommand(it, it, home, cwd, env("app"), root = rootVariant(listOf("su", "-c", command)))
                    }
                }
            }

            sshd.start()
            server = sshd
            acquireLocks()
            status = "Running on port ${cfg.port}" + when {
                root -> " (root)"
                wantShizuku -> " (shell user via Shizuku when ready, else app user)"
                else -> " (app user)"
            }
            startInForeground("Listening on port ${cfg.port}")
        } catch (e: Throwable) {
            Log.e(TAG, "Server failed to start", e)
            running = false
            status = if (e is java.net.BindException) {
                "Error: port ${Config(this).port} is already in use (another SSH app?)"
            } else {
                "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            server = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        val s = server
        server = null
        Thread {
            try { s?.stop(true) } catch (_: Exception) {}
        }.start()
        releaseLocks()
        if (!status.startsWith("Error")) status = "Stopped"
        running = false
        super.onDestroy()
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroOpenHeart::server").apply { acquire() }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL
        wifiLock = wm.createWifiLock(mode, "AndroOpenHeart::wifi").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSH server", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AndroOpenHeart")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }
}
