package dev.rootssh

import android.content.Context

class Config(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 2022)
        set(v) = prefs.edit().putInt("port", v).apply()
    var username: String
        get() = prefs.getString("username", "root")!!
        set(v) = prefs.edit().putString("username", v).apply()
    var password: String
        get() = prefs.getString("password", "")!!
        set(v) = prefs.edit().putString("password", v).apply()
    var authorizedKeys: String
        get() = prefs.getString("authorized_keys", "")!!
        set(v) = prefs.edit().putString("authorized_keys", v).apply()
    var useRoot: Boolean
        get() = prefs.getBoolean("use_root", true)
        set(v) = prefs.edit().putBoolean("use_root", v).apply()
    /** Non-root sessions run as the ADB shell user through Shizuku when it is running and allowed. */
    var useShizuku: Boolean
        get() = prefs.getBoolean("use_shizuku", true)
        set(v) = prefs.edit().putBoolean("use_shizuku", v).apply()
    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", false)
        set(v) = prefs.edit().putBoolean("start_on_boot", v).apply()

    /** Base64 key blobs from the authorized_keys text (one key per line, OpenSSH format). */
    fun authorizedKeyBlobs(): Set<String> = authorizedKeys.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            // Lines may carry options before the key type, e.g. from="..." ssh-ed25519 AAAA...
            val parts = line.split(Regex("\\s+"))
            val i = parts.indexOfFirst { it.startsWith("ssh-") || it.startsWith("ecdsa-") || it.startsWith("sk-") }
            if (i >= 0) parts.getOrNull(i + 1) else null
        }
        .toSet()
}
