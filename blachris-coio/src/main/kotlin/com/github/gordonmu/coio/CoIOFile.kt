package com.github.blachris.coio

import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object CoIOFile {
    /**
     * Read from a file.
     */
    fun read(file: Path): SeekableCoIOStream = CoIOFileStreamWrapper(AsynchronousFileChannel.open(file, StandardOpenOption.READ))

    /**
     * Write to a file, if the file does not exist it will be automatically created.
     */
    fun write(file: Path): SeekableCoIOStream = CoIOFileStreamWrapper(AsynchronousFileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE))

    /**
     * Open a file for both reading and writing, if the file does not exist it will be automatically created.
     */
    fun open(file: Path): SeekableCoIOStream = CoIOFileStreamWrapper(AsynchronousFileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE))
}

class CoIOFileStreamWrapper(private val channel: AsynchronousFileChannel) : AbstractCoIOStream(), SeekableCoIOStream {

    private val pos: AtomicLong = AtomicLong(0)

    override fun close() {
        channel.close()
    }

    override var position: Long
        get() = pos.get()
        set(value) {
            pos.set(value)
        }

    override var size: Long
        get() = channel.size()
        set(value) {
            channel.truncate(value)
        }

    private val completionHandler: CompletionHandler<Int, Continuation<Int>> = object : CompletionHandler<Int, Continuation<Int>> {
        override fun completed(result: Int, cont: Continuation<Int>) {
            if (result >= 0)
                pos.addAndGet(result.toLong())
            cont.resume(result)
        }

        override fun failed(exc: Throwable, cont: Continuation<Int>) {
            cont.resumeWithException(exc)
        }
    }

    override suspend fun writeActual(buffer: ByteBuffer): Boolean {
        val r = suspendCoroutine<Int> { cont ->
            channel.write(buffer, pos.get(), cont, completionHandler)
        }
        return r >= 0
    }

    override suspend fun readActual(buffer: ByteBuffer): Boolean {
        val r = suspendCoroutine<Int> { cont ->
            channel.read(buffer, pos.get(), cont, completionHandler)
        }
        return r >= 0
    }
}