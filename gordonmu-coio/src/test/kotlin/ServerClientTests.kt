import com.github.gordonmu.coio.CoIOTcp
import com.github.gordonmu.coio.RemoteCoIOStream
import com.github.gordonmu.coio.readFully
import com.github.gordonmu.coio.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import java.nio.ByteBuffer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerClientTests {
    val server = CoIOTcp.listen(18775)

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class `client connects to server`() {
        lateinit var clientConn: RemoteCoIOStream
        lateinit var serverConn: RemoteCoIOStream

        @BeforeAll
        fun setup() = runBlocking {
            withTimeout(2000) {
                val j1 = launch {
                    clientConn = CoIOTcp.connect("localhost", 18775)
                }
                val j2 = launch {
                    serverConn = server.receive()
                }
                j1.join()
                j2.join()
            }
        }

        @Test
        fun `client sent data is received by server`() = runBlocking {
            val totalData = 10000000
            withTimeout(2000) {
                val j1 = launch {
                    val buf = ByteBuffer.allocate(totalData / 20)
                    repeat(20) {
                        clientConn.writeFully(buf)
                        println("Sent ${buf.position()}")
                        buf.rewind()
                    }
                }
                val j2 = launch {
                    val buf = ByteBuffer.allocate(totalData / 5)
                    repeat(5) {
                        serverConn.readFully(buf)
                        println("Read ${buf.position()}")
                        buf.rewind()
                    }
                }
                j1.join()
                j2.join()
            }
        }

        @Test
        fun `server sent data is received by client`() = runBlocking {
            val totalData = 10000000
            withTimeout(2000) {
                val j1 = launch {
                    val buf = ByteBuffer.allocate(totalData / 2)
                    repeat(2) {
                        serverConn.writeFully(buf)
                        println("Sent ${buf.position()}")
                        buf.rewind()
                    }
                }
                val j2 = launch {
                    val buf = ByteBuffer.allocate(totalData / 10)
                    repeat(10) {
                        clientConn.readFully(buf)
                        println("Read ${buf.position()}")
                        buf.rewind()
                    }
                }
                j1.join()
                j2.join()
            }
        }

        @AfterAll
        fun close() {
            if (::clientConn.isInitialized)
                clientConn.close()
        }
    }

    @AfterAll
    fun close() {
        server.cancel()
    }
}