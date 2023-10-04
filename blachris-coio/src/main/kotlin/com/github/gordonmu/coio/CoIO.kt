package com.github.blachris.coio

import java.io.IOException
import java.net.SocketAddress
import java.nio.ByteBuffer

object CoIO {
    val DEFAULT_COIOSELECTOR = CoIOSelector("Default CoIOSelector", true)
}

typealias CoPort = suspend (buffer: ByteBuffer) -> Boolean

/**
 * A data port to receive data.
 */
interface CoIPort {
    /**
     * Reads up to remaining bytes from the stream. Returns as soon as at least one byte was read.
     * @return false if no bytes were read and the stream is limited
     */
    val read: CoPort
}

/**
 * A data port to send data.
 */
interface CoOPort {
    /**
     * Write up to remaining bytes to the stream. Returns as soon as at least one byte was written.
     * @return false if no bytes were written and the stream is limited
     */
    val write: CoPort
}

/**
 * An input stream has an input port and can be closed.
 */
interface CoIStream : CoIPort, AutoCloseable

/**
 * An output stream has an output port and can be closed.
 */
interface CoOStream : CoOPort, AutoCloseable

/**
 * An input output stream has an input and output port, and can be closed.
 */
interface CoIOStream : CoIStream, CoOStream

interface RemoteCoIOStream : CoIOStream {
    /**
     * The remote endpoint address of this stream.
     */
    val remoteAddress: SocketAddress
}

interface SeekableCoIOStream : CoIOStream {
    /**
     * The current read and write position. Can be larger than [size].
     */
    var position: Long

    /**
     * The total size. Can be set to a smaller value to truncate the stream.
     */
    var size: Long
}

abstract class AbstractCoIOStream : CoIOStream {
    override val read: CoPort = ::readActual
    override val write: CoPort = ::writeActual

    protected abstract suspend fun readActual(buffer: ByteBuffer): Boolean

    protected abstract suspend fun writeActual(buffer: ByteBuffer): Boolean
}

/**
 * Suspend until all remaining bytes have been read.
 * @return false if not all bytes could be read because the stream is limited
 */
suspend fun CoIPort.readFully(buffer: ByteBuffer): Boolean = read.fully(buffer)

/**
 * Suspend until all remaining bytes have been written.
 * @return false if not all bytes could be written because the stream is limited
 */
suspend fun CoOPort.writeFully(buffer: ByteBuffer): Boolean = write.fully(buffer)

private suspend fun CoPort.fully(buffer: ByteBuffer): Boolean {
    while (buffer.hasRemaining()) {
        if (invoke(buffer).not())
            return false
    }
    return true
}

/**
 * Suspend until all remaining bytes of all buffers has been written
 * @return the total number of bytes written, can be less than provided if the stream is limited
 */
suspend fun CoOPort.writeFully(bbs: Iterable<ByteBuffer>): Int {
    var written = 0
    bbs.forEach { bb ->
        val eof = write.fully(bb).not()
        written += bb.position()
        if (eof)
            return@forEach
    }
    return written
}

/**
 * Returns a wrapped port that reaches end of stream after the given number of bytes have been written or read,
 * or sooner if the underlying port has a smaller capacity for reading and writing.
 */
fun CoPort.withLimit(limit: Long): CoPort {
    val underlying = this
    var left = limit
    return { buffer ->
        if (left <= 0)
            false
        else {
            val oldPos = buffer.position()
            buffer.withRemainingAtMost(left) {
                val r = underlying(this)
                left -= (buffer.position() - oldPos)
                r
            }
        }
    }
}

/**
 * Returns a port that always throws an IOException.
 */
fun disabledPort(message: String = "disabled"): CoPort = { _ ->
    throw IOException(message)
}
