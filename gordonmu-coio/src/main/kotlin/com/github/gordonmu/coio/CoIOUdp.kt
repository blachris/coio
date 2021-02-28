package com.github.gordonmu.coio

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private val logger = KotlinLogging.logger {}

object CoIOUdp {
    fun connect(host: String, remotePort: Int, localPort: Int? = null): RemoteCoIOStream =
            CoIO.DEFAULT_COIOSELECTOR.connectUdp(InetSocketAddress(host, remotePort), localPort?.let { InetSocketAddress(it) })

    fun open(localPort: Int? = null): CoIOUdpSocket =
            CoIO.DEFAULT_COIOSELECTOR.openUdp(localPort?.let { InetSocketAddress(it) })
}

/**
 * Simple connection between two UDP endpoints.
 * The other endpoint can be a UDP server or a socket opened with the same method as this.
 */
fun CoIOSelector.connectUdp(remoteAddress: SocketAddress,
                            listenAddress: SocketAddress? = null): RemoteCoIOStream {
    val datagramChannel = DatagramChannel.open()
    if (listenAddress != null)
        datagramChannel.socket().bind(listenAddress)
    datagramChannel.connect(remoteAddress)
    datagramChannel.configureBlocking(false)
    val handler = NioChannelHandler(remoteAddress, datagramChannel, datagramChannel)
    registerChannel(datagramChannel, SelectionKey.OP_READ or SelectionKey.OP_WRITE, handler)
    return handler
}

/**
 * Generic UDP endpoint that can send to and receive from different remote endpoints simultaneously.
 */
interface CoIOUdpSocket : AutoCloseable {
    suspend fun send(buffer: ByteBuffer, dest: SocketAddress)
    suspend fun receive(buffer: ByteBuffer): SocketAddress
}

fun CoIOSelector.openUdp(listenAddress: SocketAddress? = null): CoIOUdpSocket {
    val datagramChannel = DatagramChannel.open()
    datagramChannel.configureBlocking(false)
    if (listenAddress != null)
        datagramChannel.bind(listenAddress)
    return NioDatagramChannelHandler(datagramChannel)
}

private class NioDatagramChannelHandler(val datagramChannel: DatagramChannel) : CoIOHandler, CoIOUdpSocket {

    private val closed = AtomicBoolean(false)
    private lateinit var key: SelectionKey

    override var onClose: () -> Unit = {}
    private val sendMutex = Mutex(false)

    private val readLock = Object()
    private var readable = false
    private var waitingReader: CancellableContinuation<Unit>? = null

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            datagramChannel.close()
            key.cancel()
            onClose()
        }
    }

    override fun initKey(k: SelectionKey) {
        key = k
    }

    override fun readable() {
        synchronized(readLock) {
            logger.trace { "readable" }
            if (readable.not()) {
                key.interestOps(key.interestOps() and SelectionKey.OP_READ.inv())
                waitingReader?.resume(Unit)
                waitingReader = null
                readable = true
            }
        }
    }

    override suspend fun receive(buffer: ByteBuffer): SocketAddress {
        require(buffer.hasRemaining()) { "Buffer must have space remaining" }
        var remoteAddress: SocketAddress?
        do {
            remoteAddress = runCatching {
                datagramChannel.receive(buffer)
            }.onFailure {
                close()
            }.getOrThrow()
            logger.trace { "read from $remoteAddress" }
            if (remoteAddress == null) {
                readable = false
                key.interestOps(key.interestOps() or SelectionKey.OP_READ)
                key.selector().wakeup()
                suspendCancellableCoroutine<Unit> {
                    synchronized(readLock) {
                        if (readable) {
                            logger.trace { "read resume fast" }
                            it.resume(Unit)
                        } else {
                            logger.trace { "read suspend" }
                            if (waitingReader != null)
                                it.cancel(IllegalStateException("Only single suspending read supported"))
                            else {
                                waitingReader = it
                                it.invokeOnCancellation { waitingReader = null }
                            }
                        }
                    }
                }
            }
        } while (remoteAddress == null)
        return remoteAddress
    }

    override suspend fun send(buffer: ByteBuffer, dest: SocketAddress) = sendMutex.withLock {
        val p = buffer.position()
        val x = datagramChannel.send(buffer, dest)
        assert(x == buffer.position() - p)
        Unit
    }
}
