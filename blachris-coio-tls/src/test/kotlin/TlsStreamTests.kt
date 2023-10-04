import com.github.blachris.coio.CoIOMemoryStream
import com.github.blachris.coio.tls.wrapTlsClient
import com.github.blachris.coio.tls.wrapTlsServer
import com.github.blachris.coio.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.SequenceInputStream
import java.nio.ByteBuffer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsStreamTests {

    @Test
    fun `tls client connects to server and receives data`() {
        val memStream = CoIOMemoryStream()

        val message = ByteArray(10000) { (it % 256).toByte() }

        val clz = TlsStreamTests::class.java

        runBlocking {
            withTimeout(10000) {

                val serverStream = memStream.front.wrapTlsServer(
                        clz.getResourceAsStream("testserver-cert.pem")!!,
                        clz.getResourceAsStream("testserver-key_pkcs8.pem")!!)
                val clientStream = memStream.back.wrapTlsClient("testserver",
                        SequenceInputStream(
                                clz.getResourceAsStream("testroot-cert.pem"),
                                clz.getResourceAsStream("testserver-cert.pem")))

                launch {
                    serverStream.writeFully(ByteBuffer.wrap(message))
                    println("server done")
                }
                launch {
                    val rcvBuf = ByteBuffer.allocate(message.size * 2)
                    while (true) {
                        clientStream.read(rcvBuf)
                        println("Received on client ${rcvBuf.position()}")
                        if (rcvBuf.position() >= message.size) {
                            rcvBuf.flip()
                            val bs = ByteArray(rcvBuf.remaining())
                            rcvBuf.get(bs)
                            rcvBuf.compact()
                            assertThat(bs.sliceArray(message.indices).contentEquals(message))
                            break
                        }
                    }
                }
            }
        }
    }
}