package dev.rootssh

import java.util.concurrent.TimeUnit

object Root {
    /** Runs `su -c id` (this also triggers the root manager's grant prompt). */
    fun check(): Pair<Boolean, String> = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        p.outputStream.close()
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroy()
            false to "Timed out waiting for su (was the root prompt answered?)"
        } else {
            val out = p.inputStream.bufferedReader().readText().trim()
            (p.exitValue() == 0 && out.contains("uid=0")) to out.ifEmpty { "su exited with ${p.exitValue()}" }
        }
    } catch (e: Exception) {
        false to "su not available: ${e.message}"
    }
}
