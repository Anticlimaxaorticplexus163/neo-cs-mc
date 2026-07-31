package gg.earu.chatsounds.audio

import gg.earu.chatsounds.Chatsounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * Byte-budget LRU of decoded PCM keyed by canonical URL. 100k+ sounds exist upstream;
 * decode-on-demand with eviction keeps memory bounded.
 */
object PcmCache {
    @Volatile var budgetBytes: Long = 256L * 1024 * 1024

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, PcmClip>(64, 0.75f, true)
    private var usedBytes = 0L

    suspend fun getOrDecode(url: String, file: Path): PcmClip {
        mutex.withLock { entries[url] }?.let { return it }

        val clip = withContext(Dispatchers.IO) { OggDecoder.decode(file.readBytes()) }

        mutex.withLock {
            entries[url]?.let { return it } // decoded concurrently by another sound
            entries[url] = clip
            usedBytes += clip.byteSize
            val it = entries.entries.iterator()
            while (usedBytes > budgetBytes && it.hasNext()) {
                val eldest = it.next()
                if (eldest.value === clip) continue // never evict what we just inserted
                usedBytes -= eldest.value.byteSize
                it.remove()
            }
        }
        Chatsounds.logger.debug("Decoded {} ({}s)", url, "%.2f".format(clip.durationSeconds))
        return clip
    }

    suspend fun clear() = mutex.withLock {
        entries.clear()
        usedBytes = 0
    }
}
