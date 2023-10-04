package com.github.blachris.coio

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer


class CoIStreamAdapter(private val cois: CoIStream) : InputStream() {

    override fun close() {
        cois.close()
    }

    private val singleByteBuffer = ByteBuffer.allocate(1)

    override fun read(): Int {
        singleByteBuffer.clear()
        return runBlocking {
            if (cois.read(singleByteBuffer).not())
                -1
            else
                singleByteBuffer.get(0).toInt()
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val bb = ByteBuffer.wrap(b, off, len)
        return runBlocking {
            if (cois.read(bb).not())
                -1
            else
                bb.position()
        }
    }
}

class CoOStreamAdapter(private val coos: CoOStream) : OutputStream() {

    private val buf = ByteBuffer.allocate(256)

    override fun close() {
        flush()
        coos.close()
    }

    override fun write(b: Int) {
        buf.put(b.toUInt().toByte())
        if (buf.hasRemaining().not()) {
            buf.flip()
            runBlocking {
                if (coos.write(buf).not())
                    throw IOException("end of stream")
            }
            buf.compact()
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(b, off, len)
        runBlocking {
            flushBuf()
            if (coos.writeFully(bb).not())
                throw IOException("end of stream")
        }
    }

    private suspend fun flushBuf() {
        if (buf.hasRemaining()) {
            buf.flip()
            if (coos.writeFully(buf).not())
                throw IOException("end of stream")
            buf.clear()
        }
    }

    override fun flush() {
        runBlocking {
            flushBuf()
        }
    }
}