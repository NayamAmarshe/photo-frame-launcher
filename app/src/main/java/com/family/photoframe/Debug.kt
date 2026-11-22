package com.family.photoframe

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The /debug page is a terminal you can open from a phone on the same Wi-Fi.
 * It is always on and has no password, on purpose: it is meant for a trusted home network.
 * Do not expose this device to the internet.
 *
 * Note: commands run as this app, not as "adb shell", so they can look around
 * but can't change deep system settings.
 */

/** One running `sh`. We keep what it prints, and the web page asks for new output every moment. */
class ShellSession(val id: String, dir: File) {
    private val proc: Process = ProcessBuilder("sh").directory(dir).redirectErrorStream(true).start()
    private val buf = StringBuilder()
    private var dropped = 0

    init {
        Thread {
            try {
                val reader = proc.inputStream.reader(Charsets.UTF_8)
                val chunk = CharArray(4096)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    synchronized(buf) {
                        buf.append(chunk, 0, n)
                        if (buf.length > 200_000) {
                            val cut = buf.length - 150_000
                            buf.delete(0, cut)
                            dropped += cut
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }.apply { isDaemon = true; name = "shell-$id" }.start()
    }

    fun write(line: String) {
        val o = proc.outputStream
        o.write((line + "\n").toByteArray(Charsets.UTF_8))
        o.flush()
    }

    /** Everything printed after position `since`. Also returns the new position. */
    fun read(since: Int): Pair<String, Int> = synchronized(buf) {
        val from = since.coerceIn(dropped, dropped + buf.length)
        Pair(buf.substring(from - dropped), dropped + buf.length)
    }

    fun alive(): Boolean = try {
        proc.exitValue(); false
    } catch (e: IllegalThreadStateException) {
        true
    }

    fun kill() = proc.destroy()
}

object DebugShell {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, ShellSession>()

    fun create(dir: File): ShellSession {
        if (sessions.size >= 4) closeAll()
        val id = ByteArray(6).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return ShellSession(id, dir).also { sessions[id] = it }
    }

    fun get(id: String?): ShellSession? = if (id == null) null else sessions[id]

    fun remove(id: String?) {
        if (id != null) sessions.remove(id)?.kill()
    }

    fun closeAll() {
        sessions.values.forEach { it.kill() }
        sessions.clear()
    }
}
