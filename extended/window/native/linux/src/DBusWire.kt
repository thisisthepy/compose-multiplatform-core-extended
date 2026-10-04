package org.thisisthepy.compose.window.linux
// Local copy of the D-Bus wire types: replaced by the common D-Bus types once common part 2 lands.

/**
 * One connection to the session bus, already authenticated, that carries whole messages.
 *
 * Two renderers open one: the native image on Linux, through a Java socket channel, and the
 * Kotlin/Native renderer, through the C library's socket calls. Everything above this line
 * is the same for both, which is why it is written once here.
 */
interface BusConnection {
    /** Sends one complete message. False when the connection has gone. */
    fun send(message: ByteArray): Boolean

    /**
     * The next complete message, waiting at most [timeoutMillis]; null when none arrived in
     * that time or the connection has gone. Zero does not wait at all.
     */
    fun receive(timeoutMillis: Int): ByteArray?

    fun close()
}

/** Who a call goes to. */
class Destination(val name: String, val path: String, val interfaceName: String)

/**
 * The address of the session bus: a socket path, and whether it is in the abstract namespace.
 *
 * Read from `DBUS_SESSION_BUS_ADDRESS`, which can list several addresses and several kinds of
 * transport. The first Unix socket is taken; anything else is skipped, because a desktop
 * session always offers one. With no address at all, systemd's per-user socket is where the
 * bus is.
 */
data class BusAddress(val path: String, val abstract: Boolean) {
    companion object {
        fun parse(value: String?, userId: Long): BusAddress? {
            if (value.isNullOrEmpty()) return BusAddress("/run/user/$userId/bus", abstract = false)
            for (entry in value.split(';')) {
                if (!entry.startsWith("unix:")) continue
                for (pair in entry.removePrefix("unix:").split(',')) {
                    val (key, raw) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                    when (key) {
                        "path" -> return BusAddress(unescape(raw), abstract = false)
                        "abstract" -> return BusAddress(unescape(raw), abstract = true)
                    }
                }
            }
            return null
        }

        /** Addresses escape bytes as `%xx`. */
        private fun unescape(raw: String): String {
            if ('%' !in raw) return raw
            val out = mutableListOf<Byte>()
            var index = 0
            val bytes = raw.encodeToByteArray()
            while (index < bytes.size) {
                if (bytes[index] == '%'.code.toByte() && index + 2 < bytes.size) {
                    out += raw.substring(index + 1, index + 3).toInt(16).toByte()
                    index += 3
                } else {
                    out += bytes[index]
                    index += 1
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}

/**
 * The bus's authentication, done once before the first message: this process says who it is
 * by its user id, which the bus checks against the socket's peer credentials.
 *
 * [write] sends bytes and [readLine] answers one line without its line ending, or null when
 * the connection closed.
 */
fun authenticateToBus(userId: Long, write: (ByteArray) -> Boolean, readLine: () -> String?): Boolean {
    val hexUser = userId.toString().encodeToByteArray().joinToString("") {
        it.toInt().and(0xff).toString(16).padStart(2, '0')
    }
    if (!write(byteArrayOf(0))) return false
    if (!write("AUTH EXTERNAL $hexUser\r\n".encodeToByteArray())) return false
    val answer = readLine() ?: return false
    if (!answer.startsWith("OK ")) return false
    return write("BEGIN\r\n".encodeToByteArray())
}

/**
 * How long the message starting with these sixteen bytes is, or null when they are not the
 * start of one this side reads. Used by both connections to cut a stream into messages.
 */
fun dbusMessageLength(header: ByteArray): Int? {
    if (header.size < 16 || header[0] != 'l'.code.toByte()) return null
    val body = DBusReader(header, 4).u32()
    val fields = DBusReader(header, 12).u32()
    val end = 16 + fields
    val padded = (end + 7) / 8 * 8
    val total = padded + body
    return if (total in 16..MAX_MESSAGE_BYTES) total.toInt() else null
}

/** The bus refuses messages larger than this, so a header that claims more is garbage. */
private const val MAX_MESSAGE_BYTES = 128L * 1024 * 1024

/** One message, parsed as far as this side needs. */
class DBusMessage private constructor(
    private val bytes: ByteArray,
    val type: Int,
    val serial: Long,
    val replySerial: Long?,
    val interfaceName: String?,
    val member: String?,
    private val bodyStart: Int,
) {
    /** A reader at the start of the body. */
    fun body(): DBusReader = DBusReader(bytes, bodyStart)

    companion object {
        const val METHOD_CALL = 1
        const val METHOD_RETURN = 2
        const val ERROR = 3
        const val SIGNAL = 4

        fun methodCall(
            serial: Int,
            destination: Destination,
            member: String,
            signature: String?,
            body: ByteArray,
        ): ByteArray {
            val message = DBusWriter()
            message.byte('l'.code)
            message.byte(METHOD_CALL)
            message.byte(0)
            message.byte(1)
            message.u32(body.size.toLong())
            message.u32(serial.toLong())
            message.array(8) {
                field(1, "o") { string(destination.path) }
                field(2, "s") { string(destination.interfaceName) }
                field(3, "s") { string(member) }
                field(6, "s") { string(destination.name) }
                if (signature != null) field(8, "g") { signature(signature) }
            }
            message.align(8)
            message.raw(body)
            return message.bytes()
        }

        private fun DBusWriter.field(code: Int, signature: String, value: DBusWriter.() -> Unit) {
            struct {
                byte(code)
                signature(signature)
                value()
            }
        }

        /** Null for anything this side does not read: the wrong byte order, or a truncation. */
        fun parse(bytes: ByteArray): DBusMessage? {
            val length = dbusMessageLength(bytes) ?: return null
            if (bytes.size < length) return null
            val type = bytes[1].toInt()
            val reader = DBusReader(bytes, 12)
            val fieldsLength = reader.u32().toInt()
            val fieldsEnd = 16 + fieldsLength
            var replySerial: Long? = null
            var interfaceName: String? = null
            var member: String? = null
            reader.position = 16
            try {
                while (reader.position < fieldsEnd) {
                    reader.align(8)
                    if (reader.position >= fieldsEnd) break
                    val code = reader.byte()
                    val signature = reader.signature()
                    val value: Any? = when (signature) {
                        "s", "o" -> reader.string()
                        "g" -> reader.signature()
                        "u" -> reader.u32()
                        "y" -> reader.byte()
                        else -> return null
                    }
                    when (code) {
                        2 -> interfaceName = value as? String
                        3 -> member = value as? String
                        5 -> replySerial = value as? Long
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
                return null
            }
            val bodyStart = (fieldsEnd + 7) / 8 * 8
            return DBusMessage(
                bytes,
                type,
                DBusReader(bytes, 8).u32(),
                replySerial,
                interfaceName,
                member,
                bodyStart,
            )
        }
    }
}

/** Writes the bus's little-endian wire format, with its alignment rules. */
class DBusWriter {
    private var buffer = ByteArray(256)
    private var size = 0

    fun bytes(): ByteArray = buffer.copyOf(size)

    fun raw(bytes: ByteArray) {
        bytes.forEach { put(it) }
    }

    fun align(boundary: Int) {
        while (size % boundary != 0) put(0)
    }

    fun byte(value: Int) = put(value.toByte())

    fun u32(value: Long) {
        align(4)
        for (shift in 0 until 4) put((value ushr (8 * shift)).toByte())
    }

    fun i32(value: Int) = u32(value.toLong() and 0xffff_ffffL)

    fun string(value: String) {
        val bytes = value.encodeToByteArray()
        u32(bytes.size.toLong())
        raw(bytes)
        put(0)
    }

    fun signature(value: String) {
        val bytes = value.encodeToByteArray()
        put(bytes.size.toByte())
        raw(bytes)
        put(0)
    }

    /** An array whose elements align to [elementAlignment]. The length excludes the padding. */
    fun array(elementAlignment: Int, elements: DBusWriter.() -> Unit) {
        align(4)
        val lengthAt = size
        u32(0)
        align(elementAlignment)
        val start = size
        elements()
        val length = (size - start).toLong()
        for (shift in 0 until 4) buffer[lengthAt + shift] = (length ushr (8 * shift)).toByte()
    }

    fun struct(fields: DBusWriter.() -> Unit) {
        align(8)
        fields()
    }

    private fun put(value: Byte) {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = value
    }
}

/** Reads the bus's little-endian wire format from [position] on. */
class DBusReader(private val bytes: ByteArray, var position: Int) {
    fun align(boundary: Int) {
        while (position % boundary != 0) position++
    }

    fun byte(): Int = bytes[position++].toInt() and 0xff

    fun u32(): Long {
        align(4)
        var value = 0L
        for (shift in 0 until 4) value = value or ((bytes[position + shift].toLong() and 0xff) shl (8 * shift))
        position += 4
        return value
    }

    fun string(): String {
        val length = u32().toInt()
        val value = bytes.copyOfRange(position, position + length).decodeToString()
        position += length + 1
        return value
    }

    fun signature(): String {
        val length = byte()
        val value = bytes.copyOfRange(position, position + length).decodeToString()
        position += length + 1
        return value
    }

    fun array(elementAlignment: Int, element: () -> Unit) {
        val length = u32().toInt()
        align(elementAlignment)
        val end = position + length
        while (position < end) element()
    }
}
