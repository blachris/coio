package com.github.blachris.coio.tls

import com.github.blachris.coio.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import tlschannel.*
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

private val logger = KotlinLogging.logger {}

class TlsChannelAdapter(sslEngine: SSLEngine,
                        private val cipherStream: CoIOStream) : AbstractCoIOStream() {

    private val logId: String = "TlsCA${hashCode()}"

    @Volatile
    private var needHandshake = true
    private val bca = ByteChannelAdapter(logId)
    private val tlsChannel: TlsChannel

    init {
        tlsChannel = if (sslEngine.useClientMode)
            ClientTlsChannel.newBuilder(bca, sslEngine)
                    .withEncryptedBufferAllocator(HeapBufferAllocator())
                    .build()
        else
            ServerTlsChannel.newBuilder(bca, SSLContext.getDefault()).withEngineFactory { sslEngine }
                    .withEncryptedBufferAllocator(HeapBufferAllocator())
                    .build()
    }

    private val sendMutex = Mutex(false)
    private val rcvMutex = Mutex(false)

    private suspend fun doHandshake() {
        logger.trace { "[$logId] Starting handshake" }
        while (true) {
            try {
                tlsChannel.handshake()
                bca.sendTo(cipherStream)
                needHandshake = false
                logger.trace { "[$logId] Finished handshake" }
                return
            } catch (ex: NeedsReadException) {
                bca.sendTo(cipherStream)
                bca.receiveFrom(cipherStream)
            } catch (ex: NeedsWriteException) {
                bca.sendTo(cipherStream)
            }
        }
    }

    override suspend fun writeActual(buffer: ByteBuffer): Boolean = sendMutex.withLock {
        if (needHandshake) {
            rcvMutex.withLock {
                doHandshake()
            }
        }

        while (true) {
            try {
                tlsChannel.write(buffer)
                bca.sendTo(cipherStream)
                break
            } catch (ex: NeedsWriteException) {
                bca.sendTo(cipherStream)
            }
        }
        return true
    }

    override suspend fun readActual(buffer: ByteBuffer): Boolean = rcvMutex.withLock {
        while (needHandshake) {
            if (sendMutex.tryLock()) {
                try {
                    doHandshake()
                } finally {
                    sendMutex.unlock()
                }
            } else {
                // deadlock with write, we back off
                rcvMutex.unlock()
                delay(100)
                rcvMutex.lock()
            }
        }

        while (true) {
            try {
                tlsChannel.read(buffer)
                break
            } catch (ex: NeedsReadException) {
                bca.receiveFrom(cipherStream)
            }
        }
        return true
    }

    override fun close() {
        tlsChannel.close()
    }

    private class ByteChannelAdapter(private val logId: String = "") : ByteChannel {

        override fun isOpen(): Boolean = true

        private val toSend: ByteBuffer = ByteBuffer.allocate(4096)
        private val toReceive: ByteBuffer = ByteBuffer.allocate(4096).apply { position(capacity()) }

        suspend fun sendTo(writePort: CoOPort) {
            toSend.flip()
            writePort.writeFully(toSend)
            toSend.clear()
        }

        suspend fun receiveFrom(readPort: CoIPort) {
            toReceive.clear()
            readPort.read(toReceive)
            toReceive.flip()
        }

        override fun write(src: ByteBuffer): Int {
            if (toSend.hasRemaining().not())
                return 0
            val r = toSend.remaining().coerceAtMost(src.remaining())
            logger.trace { "[$logId] Sending $r of ${src.remaining()}" }
            val oldLimit = src.limit()
            src.limit(src.position() + r)
            toSend.put(src)
            src.limit(oldLimit)
            return r
        }

        override fun close() {
        }

        override fun read(dst: ByteBuffer): Int {
            if (toReceive.hasRemaining().not())
                return 0
            logger.trace { "[$logId] Want to read ${dst.remaining()}" }
            val min: Int = toReceive.remaining().coerceAtMost(dst.remaining())
            val oldLimit = toReceive.limit()
            toReceive.limit(toReceive.position() + min)
            dst.put(toReceive)
            toReceive.limit(oldLimit)
            logger.trace { "[$logId] Reading $min" }
            return min
        }
    }
}