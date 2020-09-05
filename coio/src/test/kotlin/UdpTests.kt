import com.github.gordonmu.coio.CoIOUdp
import kotlinx.coroutines.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.ByteBuffer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UdpTests {

    @Test
    fun `connectUdp`() {
        val pointA = CoIOUdp.connect("localhost", 12000, 12001)
        val pointB = CoIOUdp.connect("localhost", 12001, 12000)

        val j1 = GlobalScope.launch {
            val buf = ByteBuffer.allocate(1024)
            pointA.write(buf)
            assertThat(buf.position()).isEqualTo(1024)
            buf.clear()
            pointA.read(buf)
            assertThat(buf.position()).isEqualTo(1024)
        }

        val j2 = GlobalScope.launch {
            val buf = ByteBuffer.allocate(1024)
            pointB.read(buf)
            assertThat(buf.position()).isEqualTo(1024)
            buf.flip()
            pointB.write(buf)
            assertThat(buf.position()).isEqualTo(1024)
        }

        runBlocking {
            withTimeout(5000) {
                j1.join()
                j2.join()
            }
        }
    }

    @Test
    @Disabled
    fun test() {
        val server = CoIOUdp.open(6678)

        GlobalScope.launch {
            val buf = ByteBuffer.allocate(1024)
            while (true) {
                buf.clear()
                val remote = server.receive(buf)
                println("server rcv ${buf.position()}")
                buf.flip()
                server.send(buf, remote)
            }
        }

        runBlocking {
            val client = CoIOUdp.connect("localhost", 6678)

            launch {
                val buf = ByteBuffer.allocate(1024)
                client.read(buf)
                println("read ${buf.position()}")
            }

            delay(500)

            val buf = ByteBuffer.allocate(1024)
            client.write(buf)
            println("written ${buf.position()}")

            delay(2000)
            buf.clear()
            client.write(buf)
            println("written ${buf.position()}")


            while (true) {
                delay(1000)
            }
        }

    }
}