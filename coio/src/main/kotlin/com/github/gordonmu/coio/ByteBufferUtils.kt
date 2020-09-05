package com.github.gordonmu.coio

import java.nio.ByteBuffer

inline fun <R> ByteBuffer.withRemainingAtMost(maxRemaining: Int, block: ByteBuffer.() -> R): R {
    val previousLimit = limit()
    try {
        if (maxRemaining < remaining()) {
            limit(maxRemaining)
        }
        return block()
    } finally {
        limit(previousLimit)
    }
}

inline fun <R> ByteBuffer.withRemainingAtMost(maxRemaining: Long, block: ByteBuffer.() -> R): R {
    val previousLimit = limit()
    try {
        if (maxRemaining < remaining()) {
            limit(maxRemaining.toInt())
        }
        return block()
    } finally {
        limit(previousLimit)
    }
}

/**
 * Same as [put] but never fails because it only puts as much bytes from [src] as possible into this.
 */
fun ByteBuffer.putPossible(src: ByteBuffer) {
    src.withRemainingAtMost(src.remaining()) {
        this@putPossible.put(this)
    }
}

/*
class UnboundDirectByteBufferPool(val size: Int) {

    private val freeBuffers = ConcurrentLinkedQueue<ByteBuffer>()
    private val usedBuffers = Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())

    fun acquire(): ByteBuffer {
        val bb = freeBuffers.poll() ?: ByteBuffer.allocateDirect(size)
        synchronized(usedBuffers) {
            usedBuffers.add(bb)
        }
        return bb
    }

    fun release(bb: ByteBuffer) {
        val removed = synchronized(usedBuffers) {
            usedBuffers.remove(bb)
        }
        require(removed) { "ByteBuffer not in use by this pool" }
        bb.clear()
        freeBuffers.add(bb)
    }
}

class BoundDirectByteBufferPool(val size: Int, val maxBuffers: Int) {
    private val freeBuffers = Channel<ByteBuffer>(maxBuffers).apply {
        repeat(maxBuffers) {
            sendBlocking(ByteBuffer.allocateDirect(size))
        }
    }
    private val usedBuffers = Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())

    suspend fun acquire(): ByteBuffer {
        val bb = freeBuffers.receive()
        synchronized(usedBuffers) {
            usedBuffers.add(bb)
        }
        return bb
    }

    fun release(bb: ByteBuffer) {
        val removed = synchronized(usedBuffers) {
            usedBuffers.remove(bb)
        }
        require(removed) { "ByteBuffer not in use by this pool" }
        bb.clear()
        freeBuffers.sendBlocking(bb)
    }
}
 */