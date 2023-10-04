package com.github.blachris.coio

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import mu.KotlinLogging
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private val logger = KotlinLogging.logger {}

object CoIOTcp {
    /**
     * Opens a TCP connection client to a remote host and returns a [RemoteCoIOStream].
     */
    suspend fun connect(host: String, remotePort: Int): RemoteCoIOStream =
            CoIO.DEFAULT_COIOSELECTOR.connectTcp(InetSocketAddress(host, remotePort))

    /**
     * Opens a TCP connection client to a remote host and returns a [RemoteCoIOStream].
     */
    suspend fun connect(address: InetSocketAddress): RemoteCoIOStream =
            CoIO.DEFAULT_COIOSELECTOR.connectTcp(address)

    /**
     * Opens TCP server to listen on a port and provides a channel to handle incoming connections.
     */
    fun listen(localPort: Int): ReceiveChannel<RemoteCoIOStream> =
            CoIO.DEFAULT_COIOSELECTOR.listenTcp(InetSocketAddress(localPort))

    /**
     * Opens TCP server to listen on a port and provides a channel to handle incoming connections.
     */
    fun listen(localAddress: InetSocketAddress): ReceiveChannel<RemoteCoIOStream> =
            CoIO.DEFAULT_COIOSELECTOR.listenTcp(localAddress)
}

suspend fun CoIOSelector.connectTcp(socketAddress: SocketAddress): RemoteCoIOStream {
    val channel = SocketChannel.open()
    channel.configureBlocking(false)
    channel.connect(socketAddress)
    val ex = IOException("Failed to connect")
    return suspendCancellableCoroutine { cont ->
        val handler = NioChannelHandler(channel.remoteAddress, channel, channel)
        handler.onConnect = { e ->
            if (e != null) {
                ex.initCause(e)
                cont.cancel(ex)
            } else
                cont.resume(handler)
        }
        registerChannel(channel, SelectionKey.OP_CONNECT, handler)
        cont.invokeOnCancellation { handler.close() }
    }
}

fun CoIOSelector.listenTcp(socketAddress: SocketAddress): ReceiveChannel<RemoteCoIOStream> {
    val handlerChannel = Channel<RemoteCoIOStream>()
    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    serverChannel.socket().bind(socketAddress)
    val serverHandler = NioServerSocketHandler(serverChannel)
    serverHandler.onAccept = { channel ->
        val handler = NioChannelHandler(channel.remoteAddress, channel, channel)
        registerChannel(channel, SelectionKey.OP_READ or SelectionKey.OP_WRITE, handler)
        runBlocking {
            handlerChannel.send(handler)
        }
    }
    registerChannel(serverChannel, SelectionKey.OP_ACCEPT, serverHandler)
    handlerChannel.invokeOnClose { serverHandler.close() }

    return handlerChannel
}

private class NioServerSocketHandler(val serverChannel: ServerSocketChannel) : CoIOHandler {

    private val closed = AtomicBoolean(false)
    private lateinit var key: SelectionKey

    override var onClose: () -> Unit = {}
    var onAccept: (SocketChannel) -> Unit = {}

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            serverChannel.close()
            key.cancel()
            onClose()
        }
    }

    override fun initKey(k: SelectionKey) {
        key = k
    }

    override fun acceptable() {
        val channel = serverChannel.accept()
        channel.configureBlocking(false)
        onAccept(channel)
    }
}

internal class NioChannelHandler(override val remoteAddress: SocketAddress,
                                 val writeChannel: GatheringByteChannel,
                                 val readChannel: ScatteringByteChannel) : AbstractCoIOStream(), CoIOHandler, RemoteCoIOStream {

    private val closed = AtomicBoolean(false)
    private lateinit var key: SelectionKey

    private val writeLock = Object()
    private var writable = false
    private var waitingWriter: CancellableContinuation<Unit>? = null

    private val readLock = Object()
    private var readable = false
    private var waitingReader: CancellableContinuation<Unit>? = null

    override var onClose: () -> Unit = {}
    var onConnect: (Exception?) -> Unit = {}

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            readChannel.close()
            writeChannel.close()
            key.cancel()
            onClose()
        }
    }

    override fun initKey(k: SelectionKey) {
        key = k
    }

    override fun connectable() {
        val channel = key.channel() as SocketChannel
        try {
            channel.finishConnect()
            channel.configureBlocking(false)
            //key.interestOps(SelectionKey.OP_WRITE or SelectionKey.OP_READ)
            onConnect(null)
        } catch (e: Exception) {
            close()
            onConnect(e)
        }
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

    override fun writable() {
        synchronized(writeLock) {
            logger.trace { "writable" }
            if (writable.not()) {
                key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
                waitingWriter?.resume(Unit)
                waitingWriter = null
                writable = true
            }
        }
    }

    override suspend fun readActual(buffer: ByteBuffer): Boolean {
        if (buffer.hasRemaining().not())
            return true
        var read: Int
        do {
            read = runCatching {
                readChannel.read(buffer).takeIf { it >= 0 } ?: throw IOException("Channel terminated")
            }.onFailure {
                close()
            }.getOrThrow()
            logger.trace { "read $read" }
            if (read == 0) {
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
        } while (read == 0)
        return true
    }

    override suspend fun writeActual(buffer: ByteBuffer): Boolean {
        if (buffer.hasRemaining().not())
            return true
        var written: Int
        do {
            written = runCatching {
                writeChannel.write(buffer).takeUnless { it < 0 } ?: throw IOException("Channel terminated")
            }.onFailure {
                close()
            }.getOrThrow()
            logger.trace { "written $written" }
            if (written == 0) {
                writable = false
                key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                key.selector().wakeup()
                suspendCancellableCoroutine<Unit> {
                    synchronized(writeLock) {
                        if (writable) {
                            logger.trace { "write resume fast" }
                            it.resume(Unit)
                        } else {
                            logger.trace { "write suspend" }
                            if (waitingWriter != null)
                                it.cancel(IllegalStateException("Only single suspending write supported"))
                            else {
                                waitingWriter = it
                                it.invokeOnCancellation { waitingWriter = null }
                            }
                        }
                    }
                }
            }
        } while (written == 0)
        return true
    }
}