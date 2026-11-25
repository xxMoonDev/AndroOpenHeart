package dev.rootssh

import android.os.ParcelFileDescriptor
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs a process for one SSH session channel. If the client asked for a terminal
 * (interactive `ssh` logins do) the process gets a real pty; otherwise
 * (`ssh host cmd`, piped transfers, `ssh -T`) plain pipes are used so binary
 * data passes through untouched.
 */
class ProcessCommand(
    private var ptyArgv: List<String>,
    private var pipeArgv: List<String>,
    private var home: String,
    private var cwd: String,
    private var extraEnv: Map<String, String> = emptyMap(),
    /** Starts the pipe-mode process somewhere else (Shizuku); null = ProcessBuilder here. */
    private var spawner: ((List<String>, Map<String, String>, String) -> Process)? = null,
    /** Used instead when the client sends env ROOTSSH_ROOT=1 (AndroUnderTheHood's Root toggle). */
    private val root: RootVariant? = null,
) : Command {
    /** How to run this session through the app's own `su` (full root, whatever runs the default sessions). */
    class RootVariant(
        val ptyArgv: List<String>,
        val pipeArgv: List<String>,
        val env: Map<String, String>,
    )

    companion object {
        const val ROOT_REQUEST = "ROOTSSH_ROOT"
    }

    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var error: OutputStream
    private var exitCallback: ExitCallback? = null
    private val exited = AtomicBoolean(false)

    @Volatile private var pid = -1
    @Volatile private var ptyFd: ParcelFileDescriptor? = null
    @Volatile private var process: Process? = null

    override fun setInputStream(`in`: InputStream) { input = `in` }
    override fun setOutputStream(out: OutputStream) { output = out }
    override fun setErrorStream(err: OutputStream) { error = err }
    override fun setExitCallback(callback: ExitCallback) { exitCallback = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        if (root != null && env.env[ROOT_REQUEST] == "1") {
            ptyArgv = root.ptyArgv
            pipeArgv = root.pipeArgv
            extraEnv = root.env
            spawner = null
            home = "/data/local/tmp"
            cwd = "/"
        }
        if (env.env.containsKey(Environment.ENV_COLUMNS)) startPty(env) else startPipes(env.env)
    }

    private fun environment(vars: Map<String, String>): Map<String, String> {
        val m = LinkedHashMap(System.getenv())
        m["TERM"] = vars[Environment.ENV_TERM] ?: "xterm-256color"
        m["HOME"] = home
        if (m["PATH"].isNullOrEmpty()) {
            m["PATH"] = "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:" +
                "/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        }
        vars.filterKeys { it == "LANG" || it.startsWith("LC_") }.forEach { (k, v) -> m[k] = v }
        m.putAll(extraEnv)
        return m
    }

    private fun startPty(env: Environment) {
        val vars = env.env
        val cols = vars[Environment.ENV_COLUMNS]?.toIntOrNull()?.takeIf { it > 0 } ?: 80
        val rows = vars[Environment.ENV_LINES]?.toIntOrNull()?.takeIf { it > 0 } ?: 24
        val pidOut = IntArray(1)
        val envArr = environment(vars).map { (k, v) -> "$k=$v" }.toTypedArray()
        val fd = Pty.createSubprocess(ptyArgv[0], ptyArgv.toTypedArray(), envArr, cwd, rows, cols, pidOut)
        pid = pidOut[0]
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        ptyFd = pfd
        val ptyIn = FileInputStream(pfd.fileDescriptor)
        val ptyOut = FileOutputStream(pfd.fileDescriptor)

        env.addSignalListener(SignalListener { _, signal ->
            if (signal == Signal.WINCH) {
                val c = env.env[Environment.ENV_COLUMNS]?.toIntOrNull()
                val r = env.env[Environment.ENV_LINES]?.toIntOrNull()
                val f = ptyFd
                if (c != null && r != null && f != null) Pty.setWindowSize(f.fd, r, c)
            }
        }, Signal.WINCH)

        val reader = thread(name = "pty-out", isDaemon = true) {
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = ptyIn.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: IOException) {
                // EIO once the last process on the terminal exits.
            }
        }
        thread(name = "pty-in", isDaemon = true) {
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) {
                        ptyOut.write(4) // client closed stdin: send Ctrl-D
                        break
                    }
                    ptyOut.write(buf, 0, n)
                }
            } catch (_: IOException) {
            }
        }
        thread(name = "pty-wait", isDaemon = true) {
            val code = Pty.waitFor(pid)
            reader.join(2000)
            closePty()
            finish(code)
        }
    }

    private fun startPipes(vars: Map<String, String>) {
        val p = spawner?.invoke(pipeArgv, environment(vars), cwd) ?: run {
            val pb = ProcessBuilder(pipeArgv)
            pb.directory(File(cwd).takeIf { it.isDirectory } ?: File("/"))
            pb.environment().apply { clear(); putAll(environment(vars)) }
            pb.start()
        }
        process = p
        val tOut = pump("pipe-out", p.inputStream, output)
        val tErr = pump("pipe-err", p.errorStream, error)
        thread(name = "pipe-in", isDaemon = true) {
            // Process stdin is a BufferedOutputStream: flush every chunk, or small
            // writes (interactive input, scrcpy control events) sit in the 8 KiB
            // buffer until it fills or the channel closes.
            val buf = ByteArray(16384)
            val stdin = p.outputStream
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    stdin.write(buf, 0, n)
                    stdin.flush()
                }
            } catch (_: IOException) {
            } finally {
                try { p.outputStream.close() } catch (_: IOException) {}
            }
        }
        thread(name = "pipe-wait", isDaemon = true) {
            val code = p.waitFor()
            tOut.join(5000)
            tErr.join(5000)
            finish(code)
        }
    }

    private fun pump(name: String, from: InputStream, to: OutputStream) = thread(name = name, isDaemon = true) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }

    private fun closePty() {
        val p = ptyFd ?: return
        ptyFd = null
        try { p.close() } catch (_: IOException) {}
    }

    private fun finish(code: Int) {
        if (exited.compareAndSet(false, true)) exitCallback?.onExit(code)
    }

    override fun destroy(channel: ChannelSession) {
        process?.destroy()
        val p = pid
        if (p > 0 && !exited.get()) {
            thread(name = "pty-kill", isDaemon = true) {
                // Hang up the whole session group (the shell and its jobs, or the su client).
                Pty.sendSignal(-p, Pty.SIGHUP)
                Pty.sendSignal(p, Pty.SIGHUP)
                Thread.sleep(1000)
                if (!exited.get()) Pty.sendSignal(p, Pty.SIGKILL)
            }
        }
    }
}
