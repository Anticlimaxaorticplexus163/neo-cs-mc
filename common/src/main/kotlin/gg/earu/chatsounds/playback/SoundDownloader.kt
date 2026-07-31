package gg.earu.chatsounds.playback

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.data.ContentProviders
import gg.earu.chatsounds.data.SoundVariant
import gg.earu.chatsounds.net.HttpQueue
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

/** Lazily downloads a sound's ogg into the disk cache, walking the CDN fallback chain. */
object SoundDownloader {
    suspend fun ensure(variant: SoundVariant): Path? {
        val file = Chatsounds.platform.configDir.resolve(variant.path)
        if (file.exists()) return file

        for (url in ContentProviders.soundUrls(variant)) {
            val result = try {
                HttpQueue.get(url, shouldEncode = true)
            } catch (e: Exception) {
                Chatsounds.logger.debug("Failed to download {}: {}, trying next provider", url, e.message)
                continue
            }
            if (result.status != 200) {
                Chatsounds.logger.debug("Failed to download {}: {}, trying next provider", url, result.status)
                continue
            }
            file.parent.createDirectories()
            file.writeBytes(result.body)
            Chatsounds.logger.debug("Downloaded {}", url)
            return file
        }

        Chatsounds.logger.warn("Failed to download {}: all content providers failed", variant.url)
        return null
    }
}
