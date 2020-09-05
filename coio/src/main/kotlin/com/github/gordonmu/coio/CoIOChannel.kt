package com.github.gordonmu.coio

import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer

private const val MAX_PACKET_SIZE: Int = 8 * 1024

class CoIOChannel(channelCapacity: Int = 8) : AutoCloseable {
    private val channel: Channel<ByteBuffer> = Channel(channelCapacity)

    private var readBuffer: ByteBuffer = ByteBuffer.allocate(0)

    val output: CoPort = ::writeActual

    val input: CoPort = ::readActual

    private suspend fun writeActual(buffer: ByteBuffer): Boolean {
        val packet = ByteBuffer.allocate(MAX_PACKET_SIZE)
        buffer.withRemainingAtMost(MAX_PACKET_SIZE) {
            packet.put(buffer)
            packet.flip()
        }
        channel.send(packet)
        return true
    }

    private suspend fun readActual(buffer: ByteBuffer): Boolean {
        if (readBuffer.hasRemaining().not()) {
            readBuffer = channel.receive()
        }
        buffer.putPossible(readBuffer)
        return true
    }

    override fun close() {
        channel.close()
    }
}