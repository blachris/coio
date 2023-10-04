package com.github.blachris.coio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.LinkedHashMap
import kotlin.collections.MutableIterator
import kotlin.collections.MutableMap
import kotlin.collections.iterator
import kotlin.collections.set

/**
 * A cache for files that are generated ad-hoc. Upon access, the file is generated if needed and a read-only stream is
 * provided to the file. Generated files are stored in the specified [cacheDir] as temporary files and automatically
 * cleaned up by the cache.
 */
class FileCache(val cacheDir: Path, val maxCacheSize: Long) : AutoCloseable {

    private class GeneratedEntry private constructor(val path: Path, val size: Long, private var retired: Boolean = false) {
        val generationTime = System.currentTimeMillis()
        val openHandles = HashSet<SeekableCoIOStream>()
        val isRetired: Boolean get() = retired

        fun newHandle(): SeekableCoIOStream = synchronized(this) {
            check(!retired) { "entry is obsolete" }
            val fstream = CoIOFile.read(path)
            return object : SeekableCoIOStream by fstream {
                init {
                    openHandles.add(this)
                }

                override fun close() {
                    fstream.close()
                    retireHandle(this)
                }
            }
        }

        fun retireHandle(h: SeekableCoIOStream) = synchronized(this) {
            openHandles.remove(h)
            if (retired && openHandles.isEmpty())
                Files.delete(path)
        }

        fun retire() {
            synchronized(this) {
                if (retired)
                    return
                retired = true
            }
            if (openHandles.isEmpty())
                Files.delete(path)
        }

        companion object {
            fun create(path: Path): GeneratedEntry = GeneratedEntry(path, Files.size(path))

            val RETIRED: GeneratedEntry = GeneratedEntry(Paths.get("."), 0L, true)
        }
    }

    private inner class Entry {
        private var active: GeneratedEntry = GeneratedEntry.RETIRED
        private val mutex = Mutex()
        val size: Long get() = active.size

        suspend fun newHandle(maxAgeMs: Long, generator: suspend (SeekableCoIOStream) -> Unit): SeekableCoIOStream {
            mutex.withLock {
                if (System.currentTimeMillis() - active.generationTime > maxAgeMs)
                    active.retire()
                if (active.isRetired) {
                    val file = Files.createTempFile(cacheDir, "cache", null)
                    CoIOFile.open(file).use {
                        generator(it)
                    }
                    active = GeneratedEntry.create(file)
                }
            }
            return active.newHandle()
        }

        fun retire() {
            active.retire()
        }
    }

    private val entries = LinkedHashMap<String, Entry>(13, 0.7f, true)
    private val size = AtomicLong(0L)

    val filesCount: Int get() = entries.size
    val bytesCount: Long get() = size.get()

    init {
        if (Files.notExists(cacheDir))
            Files.createDirectory(cacheDir)
    }

    override fun close() {
        synchronized(this) {
            val iter = entries.iterator()
            while (iter.hasNext())
                iter.removeNextFromCache()
        }
    }

    /**
     * Get a file entry from the cache. If the entry does not exist yet, or if it is older than [maxAgeMs], it will be
     * generated using the provided [generator]. The result may be kept in the cache and deleted at some point.
     * The returned [SeekableCoIOStream] is read-only must be closed before the cache will delete the file.
     */
    suspend fun get(key: String, maxAgeMs: Long, generator: suspend (SeekableCoIOStream) -> Unit): SeekableCoIOStream {
        val e: Entry = synchronized(this) {
            var entry = entries[key]
            if (entry == null) {
                entry = Entry()
                entries[key] = entry
            }
            entry
        }
        val oldSize = -e.size // size might change during newHandle
        val h = e.newHandle(maxAgeMs, generator) // has its own sync
        if (size.addAndGet(oldSize + e.size) > maxCacheSize)
            pruneCache()
        return h
    }

    private fun pruneCache() {
        synchronized(this) {
            val iter = entries.iterator()
            while (size.get() > maxCacheSize && iter.hasNext())
                iter.removeNextFromCache()
        }
    }

    private fun MutableIterator<MutableMap.MutableEntry<String, Entry>>.removeNextFromCache() {
        val e = next().value
        remove()
        size.addAndGet(-e.size)
        e.retire()
    }

}