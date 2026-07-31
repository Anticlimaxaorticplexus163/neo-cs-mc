package gg.earu.chatsounds.data

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.net.HttpQueue
import gg.earu.chatsounds.net.HttpResult
import gg.earu.chatsounds.util.sha1Hex
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
class CachedRepository(
    val hash: String,
    val list: MutableMap<String, MutableList<SoundVariant>> = HashMap(),
)

/**
 * Downloads repo manifests (msgpack lists or GitHub tree walks), compiles them into
 * key -> variants maps, and caches the result on disk keyed by a hash of the raw manifest.
 * Port of data.lua's BuildFromGitHubMsgPack / BuildFromGithub.
 */
class RepositoryCompiler(private val baseDir: Path) {
    companion object {
        /** Bump to invalidate cached repository lists when the stored schema changes. */
        const val LIST_SCHEMA_VERSION = "2"

        /**
         * GMod parity key normalization: lowercase, strip .ogg, underscores/dashes to spaces,
         * collapse whitespace runs. (The msgpack path in data.lua does not trim; the GitHub
         * tree path does — hence the flag.)
         */
        fun normalizeKey(raw: String, trim: Boolean): String {
            var s = raw.lowercase()
                .replace(Regex("\\.ogg$"), "")
                .replace(Regex("[_-]"), " ")
                .replace(Regex("\\s+"), " ")
            if (trim) s = s.trim()
            return s
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Compiled repositories keyed by "repo/branch/basePath". */
    val repositories: MutableMap<String, CachedRepository> = LinkedHashMap()

    var onProgress: ((done: Int, added: Int) -> Unit)? = null

    private fun repoCacheFile(repo: String, branch: String, basePath: String): Path =
        baseDir.resolve("repositories").resolve(sha1Hex(repo + branch + basePath) + ".json")

    private fun cacheRepository(repo: String, branch: String, basePath: String) {
        val key = "$repo/$branch/$basePath"
        val file = repoCacheFile(repo, branch, basePath)
        file.parent.createDirectories()
        file.writeText(json.encodeToString(repositories[key] ?: return))
    }

    private fun loadCachedRepository(repo: String, branch: String, basePath: String): Boolean {
        val file = repoCacheFile(repo, branch, basePath)
        if (!file.exists()) return false
        return try {
            repositories["$repo/$branch/$basePath"] = json.decodeFromString<CachedRepository>(file.readText())
            true
        } catch (e: Exception) {
            Chatsounds.logger.warn("Discarding corrupt repository cache {}: {}", file, e.message)
            false
        }
    }

    /** Returns (cacheValid, hash) — hash covers the manifest body plus the schema version. */
    private fun checkCacheValidity(body: ByteArray, repo: String, branch: String, basePath: String): Pair<Boolean, String> {
        val hash = sha1Hex(body + ";v$LIST_SCHEMA_VERSION".toByteArray())
        val file = repoCacheFile(repo, branch, basePath)
        if (file.exists()) {
            val cachedHash = try {
                json.decodeFromString<CachedRepository>(file.readText()).hash
            } catch (_: Exception) {
                null
            }
            return (cachedHash == hash) to hash
        }
        return false to hash
    }

    /**
     * Fetches the first provider URL that returns HTTP 200, falling back to the next on any
     * non-200 or transport error. Returns the 200 response, or the LAST response if none
     * succeeded (so rate-limit handling can still honor Retry-After); throws if every
     * request errored outright.
     */
    private suspend fun getWithFallback(urls: List<String>, shouldEncode: Boolean): HttpResult {
        var lastResult: HttpResult? = null
        for (url in urls) {
            val result = try {
                HttpQueue.get(url, shouldEncode)
            } catch (e: Exception) {
                Chatsounds.logger.debug("Content provider failed ({}) for {}, trying next", e.message, url)
                continue
            }
            if (result.status == 200) return result
            Chatsounds.logger.debug("Content provider returned {} for {}, trying next", result.status, url)
            lastResult = result
        }
        return lastResult ?: error("All content providers failed")
    }

    /**
     * On 429/503/403 with a Retry-After header, waits then retries [retry]; without the
     * header, fails. Returns null when not rate-limited.
     */
    private suspend fun <T> handleRateLimit(result: HttpResult, retry: suspend () -> T): T? {
        if (result.status !in intArrayOf(429, 503, 403)) return null
        val delaySecs = result.header("Retry-After")?.toLongOrNull()
            ?: error("GitHub API rate limit exceeded")
        Chatsounds.logger.info("GitHub API rate limit exceeded, retrying in {} seconds", delaySecs)
        delay((delaySecs + 1) * 1000)
        return retry()
    }

    private fun addSound(repoKey: String, hash: String, soundKey: String, realm: String, repo: String, branch: String, contentPath: String) {
        if (soundKey.isEmpty()) return
        val repository = repositories.getOrPut(repoKey) { CachedRepository(hash) }
        val url = ContentProviders.canonicalUrl(repo, branch, contentPath)
        repository.list.getOrPut(soundKey) { ArrayList() }.add(
            SoundVariant(
                url = url,
                realm = realm,
                path = "cache/$realm/${sha1Hex(url)}.ogg",
                repo = repo,
                branch = branch,
                contentPath = contentPath,
            )
        )
    }

    /** Returns true when the repo was (re)compiled, false when the disk cache was up to date. */
    suspend fun buildFromMsgpack(repo: String, branch: String, basePath: String, forceRecompile: Boolean): Boolean {
        val result = getWithFallback(ContentProviders.buildUrls(repo, branch, "$basePath/list.msgpack"), shouldEncode = false)
        handleRateLimit(result) { buildFromMsgpack(repo, branch, basePath, forceRecompile) }?.let { return it }
        if (result.status != 200) error("Failed to download list.msgpack for $repo/$branch/$basePath: ${result.status}")

        val (cacheValid, hash) = checkCacheValidity(result.body, repo, branch, basePath)
        if (cacheValid && !forceRecompile && loadCachedRepository(repo, branch, basePath)) {
            Chatsounds.logger.info("{}/{}/{} is up to date, not re-compiling lists", repo, branch, basePath)
            return false
        }

        val startTime = System.nanoTime()
        val entries = Msgpack.readSoundList(result.body)
        val repoKey = "$repo/$branch/$basePath"
        repositories.remove(repoKey)
        for (entry in entries) {
            val realm = entry.realm.lowercase()
            val soundKey = normalizeKey(entry.name, trim = false)
            addSound(repoKey, hash, soundKey, realm, repo, branch, "$basePath/${entry.path}")
            onProgress?.invoke(1, entries.size)
        }
        cacheRepository(repo, branch, basePath)
        Chatsounds.logger.info(
            "Compiled {} sounds from {} in {}s", entries.size, repoKey, "%.2f".format((System.nanoTime() - startTime) / 1e9)
        )
        return true
    }

    /** GitHub git-trees API walk for repos without a msgpack manifest. */
    suspend fun buildFromGithubTrees(repo: String, branch: String, basePath: String, forceRecompile: Boolean): Boolean {
        val apiUrl = "https://api.github.com/repos/$repo/git/trees/$branch?recursive=1"
        val result = HttpQueue.get(apiUrl)
        handleRateLimit(result) { buildFromGithubTrees(repo, branch, basePath, forceRecompile) }?.let { return it }

        val (cacheValid, hash) = checkCacheValidity(result.body, repo, branch, basePath)
        if (cacheValid && !forceRecompile && loadCachedRepository(repo, branch, basePath)) {
            Chatsounds.logger.info("{}/{}/{} is up to date, not re-compiling lists", repo, branch, basePath)
            return false
        }

        val tree = json.parseToJsonElement(result.body.decodeToString()).jsonObject["tree"]?.jsonArray
            ?: error("Invalid response from GitHub for $repo/$branch")

        val startTime = System.nanoTime()
        val repoKey = "$repo/$branch/$basePath"
        repositories.remove(repoKey)
        var soundCount = 0
        for (element in tree) {
            val filePath = element.jsonObject["path"]?.jsonPrimitive?.content ?: continue
            if (!filePath.endsWith(".ogg", ignoreCase = true)) continue
            soundCount++

            // Path shape after stripping the base path: "/realm/key.ogg" (or deeper). Chunk
            // indexing mirrors the Lua 1-based logic, where chunk 1 is the empty leading segment.
            val chunks = filePath.removePrefix(basePath).split("/")
            if (chunks.size < 3) continue
            val realm = chunks[1].lowercase()
            var soundKey = normalizeKey(chunks[2], trim = true)

            // Files deep inside the folder structure use the parent folder name as the key.
            if (chunks.size > 4) soundKey = normalizeKey(chunks[chunks.size - 2], trim = true)

            // "!" prefix on the file name means: prefer the file name over the folder.
            if (chunks.last().startsWith("!")) soundKey = normalizeKey(chunks.last(), trim = true).removePrefix("!").trim()

            addSound(repoKey, hash, soundKey, realm, repo, branch, filePath)
            onProgress?.invoke(1, tree.size)
        }
        cacheRepository(repo, branch, basePath)
        Chatsounds.logger.info(
            "Compiled {} sounds from {} in {}s", soundCount, repoKey, "%.2f".format((System.nanoTime() - startTime) / 1e9)
        )
        return true
    }
}
