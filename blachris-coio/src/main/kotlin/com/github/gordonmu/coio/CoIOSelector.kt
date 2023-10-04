package com.github.blachris.coio

import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue

interface CoIOHandler : AutoCloseable {
    var onClose: () -> Unit

    fun initKey(k: SelectionKey)

    fun readable() = Unit

    fun writable() = Unit

    fun acceptable() = Unit

    fun connectable() = Unit
}

class CoIOSelector(name: String = "CoIOSelector", daemon: Boolean = true) : AutoCloseable {
    private val selector = Selector.open()
    private val selectorAccessors = ConcurrentLinkedQueue<() -> Unit>()

    init {
        Thread(::runSelector, name).apply {
            isDaemon = daemon
            start()
        }
    }

    override fun close() {
        selector.close()
    }

    fun registerChannel(channel: SelectableChannel, ops: Int, handler: CoIOHandler) {
        selectorAccessors.add {
            val key = channel.register(selector, ops, handler)
            handler.initKey(key)
        }
        selector.wakeup()
    }

    private fun runSelector() {
        while (true) {
            while (selectorAccessors.isNotEmpty())
                selectorAccessors.poll().invoke()
            try {
                selector.select()
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next() as SelectionKey
                    keys.remove()
                    val handler = key.attachment() as CoIOHandler
                    if (!key.isValid) {
                        handler.close()
                        continue
                    }
                    when {
                        key.isAcceptable -> handler.acceptable()
                        key.isConnectable -> handler.connectable()
                        key.isReadable -> handler.readable()
                        key.isWritable -> handler.writable()
                    }
                }
            } catch (ex: ClosedSelectorException) {
                break
            } catch (ex: Exception) {
                println("Selector Exception, bad")
                ex.printStackTrace()
            }
        }
    }
}
