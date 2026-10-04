package com.verisonder.sondericons

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shell UID through Shizuku.
 *
 * Everything privileged this app does is three kinds of shell command: read a file the
 * theme engine owns, copy a file into the Themes app's folder, and restart the Themes app.
 * Shell UID can do all three on HyperOS without root; an ordinary app can do none of them.
 */
object Shell {

    fun available(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(0) }
    }

    class Result(val code: Int, val out: ByteArray, val err: String) {
        val text: String get() = String(out)
        val ok: Boolean get() = code == 0
    }

    /** Runs one shell script line and returns its output. Blocking: call off the main thread. */
    fun run(script: String): Result = runCatching {
        val p = exec(arrayOf("sh", "-c", script))
        // stderr is drained on its own thread so a chatty command cannot fill the pipe and stall.
        var err = ""
        val errThread = Thread { err = p.errorStream.bufferedReader().readText() }.apply { start() }
        val out = p.inputStream.use { it.readBytes() }
        val code = p.waitFor()
        errThread.join(2000)
        Result(code, out, err)
    }.getOrElse { Result(-1, ByteArray(0), it.toString()) }

    /** Single-quotes a path for sh. Theme paths have no quotes in them, but app names might. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Shizuku's process API is hidden, so it is reached by name, as in SonderAssist. */
    private fun exec(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }
}
