package org.thisisthepy.compose.window.graalvm.linux

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.thisisthepy.compose.window.atspi.BusAddress
import org.thisisthepy.compose.window.atspi.DBusMessage
import org.thisisthepy.compose.window.atspi.Destination

/**
 * The GraalVM X11 window's bus connection against a bus of the test's own: a Unix socket that
 * answers the authentication line and then sends and takes whole messages.
 */
class JvmBusConnectionTest {
    private val target = Destination("org.example.Test", "/org/example/Test", "org.example.Test")

    private fun message(serial: Int, member: String) =
        DBusMessage.methodCall(serial, target, member, null, ByteArray(0))

    /** Runs [serve] on a bus at a fresh path, after answering the authentication. */
    private fun withBus(serve: (SocketChannel) -> Unit, client: (BusAddress) -> Unit) {
        val directory = Files.createTempDirectory("bus")
        val path = directory.resolve("socket")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path))
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val bus = thread(isDaemon = true) {
            try {
                server.accept().use { channel ->
                    val line = StringBuilder()
                    val single = ByteBuffer.allocate(1)
                    // The NUL byte, then "AUTH EXTERNAL <hex uid>\r\n".
                    while (!line.endsWith("\r\n")) {
                        single.clear()
                        if (channel.read(single) < 0) error("closed during authentication")
                        val c = single.get(0).toInt().toChar()
                        if (c != '\u0000') line.append(c)
                    }
                    check(line.startsWith("AUTH EXTERNAL ")) { "unexpected line $line" }
                    channel.write(ByteBuffer.wrap("OK 0123456789abcdef\r\n".encodeToByteArray()))
                    val begin = ByteBuffer.allocate("BEGIN\r\n".length)
                    while (begin.hasRemaining()) if (channel.read(begin) < 0) error("closed before BEGIN")
                    serve(channel)
                    done.await(10, TimeUnit.SECONDS)
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        try {
            client(BusAddress(path.toString(), abstract = false))
        } finally {
            done.countDown()
            bus.join(10_000)
            server.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
        failure.get()?.let { throw it }
    }

    @Test
    fun receivesWholeMessagesWhateverPiecesTheyArriveIn() {
        val first = message(1, "First")
        val second = message(2, "Second")
        withBus(
            serve = { channel ->
                // Both messages in one write, then a third cut in two.
                channel.write(ByteBuffer.wrap(first + second))
                val third = message(3, "Third")
                channel.write(ByteBuffer.wrap(third.copyOf(5)))
                Thread.sleep(50)
                channel.write(ByteBuffer.wrap(third.copyOfRange(5, third.size)))
            },
            client = { address ->
                val connection = assertNotNull(JvmBusConnection.open(address))
                try {
                    assertContentEquals(first, connection.receive(2_000))
                    assertContentEquals(second, connection.receive(2_000))
                    var third: ByteArray? = null
                    repeat(40) { if (third == null) third = connection.receive(100) }
                    assertEquals("Third", DBusMessage.parse(assertNotNull(third))?.member)
                } finally {
                    connection.close()
                }
            },
        )
    }

    @Test
    fun sendsWholeMessages() {
        val sent = message(7, "Hello")
        val heard = AtomicReference<ByteArray?>(null)
        withBus(
            serve = { channel ->
                val buffer = ByteBuffer.allocate(sent.size)
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) break
                heard.set(buffer.array())
            },
            client = { address ->
                val connection = assertNotNull(JvmBusConnection.open(address))
                try {
                    assertTrue(connection.send(sent))
                    repeat(100) { if (heard.get() == null) Thread.sleep(20) }
                    assertContentEquals(sent, heard.get())
                } finally {
                    connection.close()
                }
            },
        )
    }

    @Test
    fun receiveWithoutWaitingAnswersAtOnceWhenNothingCame() {
        withBus(
            serve = { },
            client = { address ->
                val connection = assertNotNull(JvmBusConnection.open(address))
                try {
                    val started = System.nanoTime()
                    assertNull(connection.receive(0))
                    assertTrue(System.nanoTime() - started < 500_000_000L, "receive(0) waited")
                } finally {
                    connection.close()
                }
            },
        )
    }

    @Test
    fun anAbstractAddressIsNoConnection() {
        // Java cannot name an abstract socket, so the window carries on without a reader.
        assertNull(JvmBusConnection.open(BusAddress("/tmp/nothing", abstract = true)))
    }

    @Test
    fun aClosedConnectionNeitherSendsNorReceives() {
        withBus(
            serve = { },
            client = { address ->
                val connection = assertNotNull(JvmBusConnection.open(address))
                connection.close()
                assertEquals(false, connection.send(message(1, "Late")))
                assertNull(connection.receive(0))
            },
        )
    }
}
