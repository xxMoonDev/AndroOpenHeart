package dev.rootssh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.File

/**
 * Setup over ADB without root (used by AndroUnderTheHood's "Set up AndroOpenHeart").
 *
 * Only callers holding android.permission.DUMP can send this (see the manifest): the ADB shell user has it,
 * normal apps can't get it. Example:
 *   am broadcast -a dev.rootssh.action.SETUP -n dev.rootssh/.AdbSetupReceiver \
 *       --es add_key "ssh-ed25519 AAAA… me@pc" --ei port 2222 --ez use_root false --ez start true
 *
 * Extras (all optional): add_key (appended to authorized_keys if missing), port, username, use_root,
 * use_shizuku, start_on_boot, start (start the server, or restart it so new settings apply).
 * The result data (printed by `am broadcast`) is "key=value" lines: ok, port, username, hostkey (OpenSSH
 * public key line), fingerprint, start (started / restarted / skipped / error: …).
 */
class AdbSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cfg = Config(context)
        val out = linkedMapOf<String, String>()
        try {
            intent.getStringExtra("add_key")?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
                val blob = key.split(Regex("\\s+")).getOrNull(1) ?: key
                if (blob !in cfg.authorizedKeyBlobs()) {
                    cfg.authorizedKeys = (cfg.authorizedKeys.trimEnd().lines().filter { it.isNotBlank() } + key)
                        .joinToString("\n")
                    out["key"] = "added"
                } else {
                    out["key"] = "present"
                }
            }
            if (intent.hasExtra("port")) {
                val p = intent.getIntExtra("port", cfg.port)
                require(p in 1024..65535) { "port must be 1024-65535" }
                cfg.port = p
            }
            intent.getStringExtra("username")?.trim()?.takeIf { it.isNotEmpty() }?.let { cfg.username = it }
            if (intent.hasExtra("use_root")) cfg.useRoot = intent.getBooleanExtra("use_root", false)
            if (intent.hasExtra("use_shizuku")) cfg.useShizuku = intent.getBooleanExtra("use_shizuku", true)
            if (intent.hasExtra("start_on_boot")) cfg.startOnBoot = intent.getBooleanExtra("start_on_boot", true)

            // the same host key file the server uses; created now if the server never ran
            val hk = SimpleGeneratorHostKeyProvider(File(context.filesDir, "ssh_host_ec_key").toPath())
            hk.algorithm = KeyUtils.EC_ALGORITHM
            hk.keySize = 256
            hk.loadKeys(null).firstOrNull()?.public?.let {
                out["hostkey"] = PublicKeyEntry.toString(it)
                out["fingerprint"] = KeyUtils.getFingerPrint(it)
            }
            out["port"] = cfg.port.toString()
            out["username"] = cfg.username

            if (intent.getBooleanExtra("start", false)) {
                if (SshService.running) {
                    // restart so new keys / port apply; the old server needs a moment to close its socket
                    SshService.stop(context)
                    val pending = goAsync()
                    Handler(Looper.getMainLooper()).postDelayed({
                        val r = startService(context)
                        pending.setResultData(finish(out, if (r == "started") "restarted" else r))
                        pending.finish()
                    }, 1500)
                    return
                }
                out["start"] = startService(context)
            } else {
                out["start"] = "skipped"
            }
            setResultData(finish(out, null))
        } catch (e: Exception) {
            Log.e(TAG, "ADB setup failed", e)
            out["ok"] = "false"
            out["error"] = (e.message ?: e.javaClass.simpleName).replace('\n', ' ')
            setResultData(out.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    private fun startService(ctx: Context): String = try {
        SshService.start(ctx)
        "started"
    } catch (e: Exception) {
        // Android 12+ refuses foreground services started from the background: open the app first
        "error: ${e.javaClass.simpleName}: ${e.message}".replace('\n', ' ')
    }

    private fun finish(out: LinkedHashMap<String, String>, start: String?): String {
        if (start != null) out["start"] = start
        out["ok"] = "true"
        return out.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    companion object {
        const val ACTION = "dev.rootssh.action.SETUP"
        private const val TAG = "AndroOpenHeart"
    }
}
