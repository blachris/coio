package com.github.gordonmu.coio

class CoIOMemoryStream(channelCapacity: Int = 8) : AutoCloseable {
    val frontToBackChannel = CoIOChannel(channelCapacity)
    val backToFrontChannel = CoIOChannel(channelCapacity)

    val front = object : CoIOStream {
        override val read: CoPort = backToFrontChannel.input

        override val write: CoPort = frontToBackChannel.output

        override fun close() {
            this@CoIOMemoryStream.close()
        }
    }

    val back = object : CoIOStream {
        override val read: CoPort = frontToBackChannel.input

        override val write: CoPort = backToFrontChannel.output

        override fun close() {
            this@CoIOMemoryStream.close()
        }
    }

    override fun close() {
        frontToBackChannel.close()
        backToFrontChannel.close()
    }
}