package org.thisisthepy.compose.window.graalvm.linux

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import org.thisisthepy.compose.window.atspi.BusAddress
import org.thisisthepy.compose.window.atspi.BusConnection
import org.thisisthepy.compose.window.atspi.authenticateToBus
import org.thisisthepy.compose.window.atspi.dbusMessageLength

/**
 * A D-Bus connection for the GraalVM X11 window: a Unix socket channel read on the window's
 * own thread.
 *
 * The same shape as the Kotlin/Native window's connection, and for the same reason: the
 * window's loop turns several dozen times a second, each turn asks the bus for whatever it
 * has said without waiting, and a call waits for its own answer. Nothing on this socket is
 * read from two threads, so there is no reader thread and no queue.
 *
 * The Java socket API cannot name an abstract socket. Every current desktop session publishes
 * a path for both buses, so an abstract address is answered with no connection, and the window
 * carries on without a screen reader, as it does on a machine with no accessibility bus.
 */
class JvmBusConnection private constructor(
    private val channel: SocketChannel,
) : BusConnection {
    private val selector: Selector = Selector.open()
    private val chunk = ByteBuffer.allocate(CHUNK_BYTES)

    /** Bytes read that do not yet make a whole message. */
    private var pending = ByteArray(0)
    private var open = true

    init {
        channel.configureBlocking(false)
        channel.register(selector, SelectionKey.OP_READ)
    }

    override fun send(message: ByteArray): Boolean {
        if (!open) return false
        return try {
            val buffer = ByteBuffer.wrap(message)
            var stalled = 0
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) {
                    // The socket's buffer is full. The bus drains it within microseconds;
                    // one that does not within a second is not answering at all.
                    if (++stalled > MAX_STALLS) {
                        close()
                        return false
                    }
                    Thread.sleep(1)
                } else {
                    stalled = 0
                }
            }
            true
        } catch (_: IOException) {
            close()
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun receive(timeoutMillis: Int): ByteArray? {
        takeMessage()?.let { return it }
        if (!open) return null
        return try {
            val ready = if (timeoutMillis > 0) selector.select(timeoutMillis.toLong()) else selector.selectNow()
            selector.selectedKeys().clear()
            if (ready <= 0) return null
            chunk.clear()
            val count = channel.read(chunk)
            if (count < 0) {
                close()
                return null
            }
            if (count > 0) pending += chunk.array().copyOf(count)
            takeMessage()
        } catch (_: IOException) {
            close()
            null
        }
    }

    override fun close() {
        if (!open) return
        open = false
        try {
            selector.close()
        } catch (_: IOException) {
            // Already gone, which is what closing was for.
        }
        try {
            channel.close()
        } catch (_: IOException) {
            // The same.
        }
    }

    private fun takeMessage(): ByteArray? {
        if (pending.size < 16) return null
        val length = dbusMessageLength(pending.copyOf(16))
        if (length == null) {
            // Not a stream this side can cut into messages any more.
            close()
            return null
        }
        if (pending.size < length) return null
        val message = pending.copyOf(length)
        pending = pending.copyOfRange(length, pending.size)
        return message
    }

    companion object {
        private const val CHUNK_BYTES = 4096
        private const val MAX_STALLS = 1000

        /** Connects and authenticates to the session bus, or answers null where there is none. */
        fun open(): JvmBusConnection? {
            val user = userId() ?: return null
            val address = BusAddress.parse(System.getenv("DBUS_SESSION_BUS_ADDRESS"), user) ?: return null
            return open(address)
        }

        /**
         * Connects and authenticates to the bus at [address]: the session bus, or the
         * accessibility bus, which the session bus names and which is a socket of its own.
         */
        fun open(address: BusAddress): JvmBusConnection? {
            val user = userId() ?: return null
            if (address.abstract || address.path.isEmpty()) return null
            return try {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(address.path))
                val single = ByteBuffer.allocate(1)
                val authenticated = authenticateToBus(
                    user,
                    write = { bytes ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        true
                    },
                    // A byte at a time, so the line is all that is read: the messages after it
                    // are the connection's to cut up.
                    readLine = {
                        val line = StringBuilder()
                        var done = false
                        while (!done) {
                            single.clear()
                            if (channel.read(single) < 0) break
                            val character = single.get(0).toInt().toChar()
                            if (character == '\n') done = true else if (character != '\r') line.append(character)
                        }
                        if (done) line.toString() else null
                    },
                )
                if (!authenticated) {
                    channel.close()
                    null
                } else {
                    JvmBusConnection(channel)
                }
            } catch (_: Exception) {
                null
            }
        }

        /** This process's user id, from the kernel's own description of it. */
        fun userId(): Long? = try {
            Files.readAllLines(Path.of("/proc/self/status"))
                .firstOrNull { it.startsWith("Uid:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
        } catch (_: Exception) {
            null
        }

        /** The executable's name, which is what a desktop entry for it is usually called. */
        fun applicationName(): String = try {
            Files.readSymbolicLink(Path.of("/proc/self/exe")).fileName.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
