import com.github.blachris.coio.*
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileTests {

    lateinit var testFile: File
    val testFileSize = 56766L

    @BeforeAll
    fun setup() {
        testFile = File.createTempFile("coiofiletests", ".tmp")
        testFile.outputStream().use {
            ClassLoader.getSystemResource("testfile.txt").openStream().copyTo(it)
        }
    }

    @Test
    fun `can read a file`() {
        val bb = ByteBuffer.allocateDirect(60000)
        runBlocking {
            CoIOFile.read(testFile.toPath()).readFully(bb)
            assertThat(bb.position()).isEqualTo(testFileSize)
        }
    }

    @Test
    fun `can read file line by line`() {
        val flow = CoIOFile.read(testFile.toPath()).readSeparated()
        runBlocking {
            assertThat(flow.count()).isEqualTo(646)
        }
    }

    @Test
    fun `can read file limited`() {
        val bb = ByteBuffer.allocateDirect(600)
        runBlocking {
            val s = CoIOFile.read(testFile.toPath())
            val wrapped = object : SeekableCoIOStream by s {
                override val read: CoPort = s.read.withLimit(2000L)
            }
            wrapped.readFully(bb)
            assertThat(bb.position()).isEqualTo(600)
            bb.clear()
            wrapped.readFully(bb)
            assertThat(bb.position()).isEqualTo(600)
            bb.clear()
            wrapped.readFully(bb)
            assertThat(bb.position()).isEqualTo(600)
            bb.clear()
            wrapped.readFully(bb)
            assertThat(bb.position()).isEqualTo(200)
            bb.clear()
        }
    }

    @Test
    fun `can restrict write operation`() {
        val bb = ByteBuffer.allocateDirect(600)
        runBlocking {
            val s = CoIOFile.open(testFile.toPath())
            val wrapped = object : SeekableCoIOStream by s {
                override val write: CoPort = disabledPort()
            }
            wrapped.readFully(bb)
            assertThat(bb.position()).isEqualTo(600)
            bb.flip()
            assertThatCode { runBlocking { wrapped.writeFully(bb) } }.isInstanceOf(IOException::class.java)
        }
    }

    /**
     * Reads the file completely and passes the file contents to the write port.
     */
    private suspend fun CoOPort.writeFile(path: Path): Boolean {
        val bb = ByteBuffer.allocate(8 * 1024)
        CoIOFile.read(path).use {
            while (it.read(bb)) {
                bb.flip()
                if (writeFully(bb).not())
                    return false
                bb.clear()
            }
        }
        return true
    }

    @Test
    fun `basic file cache test`() {
        runBlocking {
            val fc = FileCache(Paths.get("./filecachetest"), 100000)
            val h = fc.get("xyz", 0) { f ->
                CoOStreamAdapter(f).use { out ->
                    Files.copy(testFile.toPath(), out)
                }
            }
            assertThat(fc.filesCount).isEqualTo(1)
            assertThat(fc.bytesCount).isEqualTo(testFileSize)
            val h1 = fc.get("xyz", 1000000) { _ ->
                fail("should not regenerate cached file")
            }
            assertThat(fc.filesCount).isEqualTo(1)
            assertThat(fc.bytesCount).isEqualTo(testFileSize)
            h.close()
            assertThat(fc.filesCount).isEqualTo(1)
            assertThat(fc.bytesCount).isEqualTo(testFileSize)
            fc.close()
            assertThat(fc.filesCount).isEqualTo(0)
            assertThat(fc.bytesCount).isEqualTo(0)
            h1.close()
            assertThat(fc.filesCount).isEqualTo(0)
            assertThat(fc.bytesCount).isEqualTo(0)
        }
        Files.delete(Paths.get("./filecachetest"))
    }
}