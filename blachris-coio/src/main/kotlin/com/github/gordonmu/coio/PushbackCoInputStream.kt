package com.github.blachris.coio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*

private val EMPTY_BUF = ByteBuffer.allocate(0)

/**
 * Wrapper stream similar to a [BufferedInputStream] which allows reading a stream and resetting the read position to an
 * earlier position in the stream.
 */
/*
class BufferedCoInputStream(private val inputStream: CoInputStream): CoInputStream {
    private var buf: ByteBuffer = EMPTY_BUF

    fun mark(readAheadLimit: Int) {
        if (buf.capacity() - buf.position() >= readAheadLimit)
            Unit // nothing to do
        else if (buf.capacity() >= readAheadLimit) {
            // current buffer is fine but we need to make room
            buf.compact()
            buf.flip()
        }
        else {
            // current buffer is not fine, allocate new and copy stuff
            val t = ByteBuffer.allocate(readAheadLimit)
            t.put(buf)
            t.flip()
            buf = t
        }
        buf.mark()
    }

    fun reset() {
        check(buf.capacity() > 0) { "mark has not been called or the readAheadLimit has been exceeded" }
        buf.reset()
    }

    suspend fun skip(bytes: Int) {
        require(buf.capacity() >= buf.position() + bytes) { "Skipping over the readAheadLimit is not supported" }

        var leftToSkip = bytes
        while (leftToSkip > 0) {
            // buf must have capacity
            if (buf.hasRemaining().not()) {
                // read something from underlying
                val oldPos = buf.position()
                buf.limit(buf.capacity())
                inputStream.read(buf)
                buf.limit(buf.position())
                buf.position(oldPos)
            }

            val skip = leftToSkip.coerceAtMost(buf.remaining())
            buf.position(buf.position() + skip)
            leftToSkip -= skip
        }
    }

    override suspend fun read(buffer: ByteBuffer) {
        if (buf.capacity() == buf.position()) {
            // mark invalid, just read directly from underlying
            inputStream.read(buffer)
            return
        }

        // buf must have capacity
        if (buf.hasRemaining().not()) {
            // read something from underlying
            val oldPos = buf.position()
            buf.limit(buf.capacity())
            inputStream.read(buf)
            buf.limit(buf.position())
            buf.position(oldPos)
        }

        buf.putPossible(buffer)
    }

    override fun close() {
        inputStream.close()
    }
}
*/
class PushbackCoIPort(private val inputPort: CoIPort) : CoIPort {

    private val unreadBuffers = LinkedList<ByteBuffer>()

    override val read: CoPort = ::readActual

    private suspend fun readActual(buffer: ByteBuffer): Boolean {
        if (unreadBuffers.isEmpty()) {
            return inputPort.read(buffer)
        }

        val buf = unreadBuffers.first()
        buffer.putPossible(buf)
        if (buf.hasRemaining().not())
            unreadBuffers.removeFirst()
        return true
    }

    /**
     * Adds the remaining bytes of [buffer] to the front of the unread queue meaning they will be returned by the next [read].
     * This function references the passed buffer without making a copy. Do not change the buffer state to avoid side effects!
     */
    fun unreadUnsafe(buffer: ByteBuffer) {
        if (buffer.hasRemaining())
            unreadBuffers.addFirst(buffer)
    }

    /**
     * Copies the remaining bytes of [buffer] to the front of the unread queue meaning they will be returned by the next [read].
     */
    fun unread(buffer: ByteBuffer) {
        if (buffer.hasRemaining()) {
            val buf = ByteBuffer.allocate(buffer.remaining())
            buf.put(buffer)
            buf.flip()
            unreadBuffers.addFirst(buf)
        }
    }
}

private class ByteBufferSequenceStream(buffers: Collection<ByteBuffer>) : InputStream() {

    // invariant: all buffers have remaining bytes
    private val seq = LinkedList(buffers)

    override fun read(): Int {
        if (seq.isEmpty())
            return -1
        val b = seq.first()
        val res = b.get().toUInt().toInt()
        if (b.hasRemaining().not())
            seq.removeFirst()
        return res
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (seq.isEmpty())
            return -1
        val rb = ByteBuffer.wrap(b, off, len)
        val bb = seq.first()
        rb.putPossible(bb)
        if (bb.hasRemaining().not())
            seq.removeFirst()
        return rb.position()
    }
}

fun CoIPort.readSeparated(separator: Byte = '\n'.code.toByte()): Flow<InputStream> = flow {
    val pbport = PushbackCoIPort(this@readSeparated)
    val res = LinkedList<ByteBuffer>()
    while (true) {
        // read a block
        val bbuf = ByteBuffer.allocate(1024)
        try {
            if (pbport.read(bbuf).not()) {
                emit(ByteBufferSequenceStream(res))
                break
            }
        } catch (ex: Exception) {
            emit(ByteBufferSequenceStream(res))
            throw ex
        }
        bbuf.flip()
        // search through the block till we find the separator
        val sepIdx = bbuf.array().indexOf(separator)
        if (sepIdx < 0)
        // not found: add entire buffer to next result
            res.add(bbuf)
        else {
            // found: unread bytes after the separator, put bytes before to result and emit
            bbuf.position(sepIdx + 1)
            pbport.unread(bbuf)
            // don't wanna add empty buffers to result
            if (sepIdx > 0) {
                bbuf.position(0)
                bbuf.limit(sepIdx)
                res.add(bbuf)
            }
            emit(ByteBufferSequenceStream(res))
            res.clear()
        }
    }
}
