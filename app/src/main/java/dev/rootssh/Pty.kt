package dev.rootssh

/** JNI bridge to pty.c. */
object Pty {
    init {
        System.loadLibrary("rootssh_pty")
    }

    /** Starts [cmd] on a new pseudo-terminal. Returns the master fd; pidOut[0] gets the child pid. */
    external fun createSubprocess(
        cmd: String, args: Array<String>, env: Array<String>, cwd: String,
        rows: Int, cols: Int, pidOut: IntArray,
    ): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /** Blocks until [pid] exits; returns its exit code (128+signal if killed). */
    external fun waitFor(pid: Int): Int

    external fun sendSignal(pid: Int, signal: Int): Int

    const val SIGHUP = 1
    const val SIGKILL = 9
}
