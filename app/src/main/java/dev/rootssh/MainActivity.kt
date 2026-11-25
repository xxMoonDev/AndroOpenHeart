package dev.rootssh

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.rootssh.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var cfg: Config
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        cfg = Config(this)

        loadFields()
        b.shizukuAllow.setOnClickListener { ShizukuShell.requestPermission(2) }

        b.toggle.setOnClickListener {
            if (SshService.running) {
                SshService.stop(this)
            } else if (save()) {
                SshService.start(this)
            }
            handler.postDelayed({ refresh() }, 300)
        }
        b.testRoot.setOnClickListener {
            b.rootResult.text = "Asking for root… (approve the prompt)"
            Thread {
                val (ok, msg) = Root.check()
                runOnUiThread { b.rootResult.text = (if (ok) "Root OK: " else "No root: ") + msg }
            }.start()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun loadFields() {
        b.port.setText(cfg.port.toString())
        b.username.setText(cfg.username)
        b.password.setText(cfg.password)
        b.authorizedKeys.setText(cfg.authorizedKeys)
        b.useRoot.isChecked = cfg.useRoot
        b.useShizuku.isChecked = cfg.useShizuku
        b.startOnBoot.isChecked = cfg.startOnBoot
    }

    // settings changed from outside (setup over ADB): show them, so Start doesn't save stale fields over them
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { loadFields() }
    }

    private fun save(): Boolean {
        val port = b.port.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("Port must be 1024–65535 (ports below 1024 need system privileges)")
            return false
        }
        val user = b.username.text.toString().trim()
        if (user.isEmpty()) {
            toast("Enter a username")
            return false
        }
        val pw = b.password.text.toString()
        val keys = b.authorizedKeys.text.toString()
        cfg.port = port
        cfg.username = user
        cfg.password = pw
        cfg.authorizedKeys = keys
        cfg.useRoot = b.useRoot.isChecked
        cfg.useShizuku = b.useShizuku.isChecked
        cfg.startOnBoot = b.startOnBoot.isChecked
        if (pw.isEmpty() && cfg.authorizedKeyBlobs().isEmpty()) {
            toast("Set a password or paste at least one public key")
            return false
        }
        if (pw.isNotEmpty() && pw.length < 8) {
            toast("Use a password of at least 8 characters")
            return false
        }
        return true
    }

    private fun refresh() {
        val running = SshService.running
        b.status.text = SshService.status
        b.toggle.text = if (running) "Stop server" else "Start server"
        val ips = SshService.ipAddresses()
        val port = cfg.port
        b.connect.text = if (ips.isEmpty()) "No network address" else
            ips.joinToString("\n") { "ssh -p $port ${cfg.username}@$it" }
        b.fingerprint.text = SshService.fingerprint?.let { "Host key: $it" } ?: ""
        b.shizukuStatus.text = ShizukuShell.status() +
            "\nGranted: " + Perms.granted(this).joinToString(", ").ifEmpty { "none" }
        b.shizukuAllow.visibility = if (ShizukuShell.running() && !ShizukuShell.permitted())
            android.view.View.VISIBLE else android.view.View.GONE
        val editable = !running
        listOf(b.port, b.username, b.password, b.authorizedKeys, b.useRoot, b.useShizuku, b.startOnBoot)
            .forEach { it.isEnabled = editable }
    }

    override fun onResume() {
        super.onResume()
        loadFields()
        getSharedPreferences("config", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
        getSharedPreferences("config", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (!SshService.running) {
            // Keep the boot toggle in sync even if the server wasn't started.
            cfg.startOnBoot = b.startOnBoot.isChecked
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
