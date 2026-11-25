package dev.rootssh

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process as AndroidProcess
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs commands as the ADB shell user (uid 2000) through a running Shizuku server. */
object ShizukuShell {
    private const val TAG = "AndroOpenHeart"
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /** Shizuku.newProcess is private in API 13 but still the simplest way to spawn a remote process. */
    private val newProcess: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun permitted(): Boolean = try {
        running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun usable(): Boolean = permitted()

    /** Waits briefly for the binder (it arrives asynchronously after the process starts). */
    fun awaitBinder(timeoutMs: Long): Boolean {
        if (running()) return true
        val latch = CountDownLatch(1)
        val l = Shizuku.OnBinderReceivedListener { latch.countDown() }
        Shizuku.addBinderReceivedListenerSticky(l)
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            Shizuku.removeBinderReceivedListener(l)
        }
        return running()
    }

    fun status(): String = when {
        !running() -> "Shizuku: not running"
        !permitted() -> "Shizuku: running, AndroOpenHeart not allowed yet"
        else -> "Shizuku: ready (sessions run as uid ${try { Shizuku.getUid() } catch (_: Throwable) { "?" }})"
    }

    fun requestPermission(code: Int) {
        if (running() && !permitted()) Shizuku.requestPermission(code)
    }

    fun spawn(argv: List<String>, env: Map<String, String>, dir: String): Process {
        val envArr = env.map { (k, v) -> "$k=$v" }.toTypedArray()
        val d = if (File(dir).isDirectory) dir else "/"
        return newProcess.invoke(null, argv.toTypedArray(), envArr, d) as Process
    }

    /** Path of Shizuku's starter (libshizuku.so), if Shizuku is installed. */
    fun starterPath(ctx: Context): String? = try {
        val ai = ctx.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
        File(ai.nativeLibraryDir, "libshizuku.so").takeIf { it.exists() }?.absolutePath
    } catch (_: Exception) {
        null
    }

    /**
     * With root, start Shizuku as the shell user (uid 2000) – e.g. after a reboot – so
     * non-root sessions keep shell (ADB-level) rights rather than root.
     */
    fun startWithRoot(ctx: Context): Boolean {
        val starter = starterPath(ctx) ?: return false
        return try {
            val p = ProcessBuilder("su", "2000", "-c", starter).redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            Log.i(TAG, "Shizuku start via su 2000: rc=${p.exitValue()} $out")
            awaitBinder(5000)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku start via root failed", e)
            false
        }
    }
}

/** Special permissions AndroOpenHeart currently holds (reported to clients in ROOTSSH_PERMS). */
object Perms {
    fun granted(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) out += "storage"
        if (Build.VERSION.SDK_INT < 30 || has(Manifest.permission.QUERY_ALL_PACKAGES)) out += "packages"
        if (has(Manifest.permission.READ_LOGS)) out += "logs"
        if (has(Manifest.permission.DUMP)) out += "dump"
        if (has(Manifest.permission.WRITE_SECURE_SETTINGS)) out += "secure_settings"
        if (usageStats(ctx)) out += "usage_stats"
        if (Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()) out += "install"
        return out
    }

    private fun usageStats(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, AndroidProcess.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, AndroidProcess.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}
