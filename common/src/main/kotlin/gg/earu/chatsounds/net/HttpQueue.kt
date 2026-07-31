package gg.earu.chatsounds.net

import gg.earu.chatsounds.Chatsounds
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpResult(
    val status: Int,
    val body: ByteArray,
    private val headers: Map<String, List<String>>,
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()
}

/**
 * HTTP GET with a global in-flight cap. Firing the whole on-join msgpack burst (plus rapid
 * new-sound downloads) from a single IP is what trips GitHub's per-IP rate limit; queueing
 * anything past the cap smooths that out. (Port of internal_modules/http.lua, MAX_CONCURRENT = 4.)
 */
object HttpQueue {
    private const val MAX_CONCURRENT = 4

    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * GMod parity (encode_sound_path): percent-encode digits in the last path segment
     * ("%3" .. digit is the %3X escape of ASCII 0x30-0x39) and spaces anywhere — a workaround
     * for GitHub raw/CDN URL quirks with certain sound filenames.
     */
    fun encodeSoundPath(url: String): String {
        val chunks = url.split("/").toMutableList()
        chunks[chunks.lastIndex] = chunks.last().replace(Regex("\\d")) { "%3${it.value}" }
        return chunks.joinToString("/").replace(" ", "%20")
    }

    suspend fun get(url: String, shouldEncode: Boolean = false): HttpResult {
        val finalUrl = if (shouldEncode) encodeSoundPath(url) else url
        return semaphore.withPermit {
            val request = HttpRequest.newBuilder(URI(finalUrl))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "neo-cs-mc/${runCatching { Chatsounds.platform.modVersion }.getOrDefault("dev")}")
                .GET()
                .build()
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
            HttpResult(response.statusCode(), response.body(), response.headers().map())
        }
    }
}
