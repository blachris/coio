import com.github.gordonmu.coio.CoIOTcp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

fun main() {
    runBlocking {
        val inc = CoIOTcp.listen(8775)

        for (coios in inc) {
            println("new connection from ${coios.remoteAddress}")
            launch {
                val buf = ByteBuffer.allocate(1000 * 1024)
                while (true) {
                    try {
                        coios.read(buf)
                        println("Read ${buf.position()}")
                        buf.rewind()
                    } catch (ex: Exception) {
                        println("connection from ${coios.remoteAddress} closed")
                        break
                    }
                }
            }
        }
    }
}
