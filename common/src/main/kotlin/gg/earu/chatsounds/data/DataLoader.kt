package gg.earu.chatsounds.data

import gg.earu.chatsounds.Chatsounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class LoadingState(val text: String) {
    val current = AtomicInteger(0)
    @Volatile var target: Int = 0

    val percent: Int
        get() = (current.get() * 100 / maxOf(1, target)).coerceIn(0, 100)
}

/**
 * Orchestrates list compilation: builds every configured repo (msgpack or GitHub trees),
 * then merges them into the active [SoundLookup]. Port of data.lua's CompileLists.
 */
object DataLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Per-repo build timeout; GMod force-merges whatever finished after 5 minutes. */
    private const val REPO_TIMEOUT_MS = 5L * 60L * 1000L

    @Volatile var lookup: SoundLookup = SoundLookup.EMPTY
        private set
    @Volatile var trie: CompletionTrie? = null
        private set
    @Volatile var loading: LoadingState? = null
        private set
    @Volatile var repoConfig: List<RepoEntry> = emptyList()

    val isReady: Boolean
        get() = loading == null && lookup !== SoundLookup.EMPTY

    var onInitialized: (() -> Unit)? = null

    fun startup() {
        repoConfig = RepoConfig.load()
        compileLists()
    }

    fun compileLists(forceRecompile: Boolean = false): Job = scope.launch {
        val state = LoadingState("Loading chatsounds... %d%%")
        loading = state

        val compiler = RepositoryCompiler(Chatsounds.platform.configDir)
        compiler.onProgress = { done, added ->
            // Targets grow as manifests arrive; mirror GMod's incremental Loading.Target.
            if (state.current.addAndGet(done) > state.target) state.target = state.current.get()
            if (added > 0 && state.target < added) state.target += added
        }

        val results = repoConfig.map { entry ->
            async {
                try {
                    withTimeout(REPO_TIMEOUT_MS) {
                        if (entry.useMsgPack) {
                            compiler.buildFromMsgpack(entry.repo, entry.branch, entry.basePath, forceRecompile)
                        } else {
                            compiler.buildFromGithubTrees(entry.repo, entry.branch, entry.basePath, forceRecompile)
                        }
                    }
                } catch (e: Exception) {
                    Chatsounds.logger.error("Failed to compile {}/{}/{}: {}", entry.repo, entry.branch, entry.basePath, e.message)
                    false
                }
            }
        }.awaitAll()

        try {
            lookup = SoundLookup.merge(compiler.repositories)
            Chatsounds.logger.info("Done compiling all lists ({} keys)", lookup.list.size)

            if (Chatsounds.platform.isClient) {
                val trieFile = Chatsounds.platform.configDir.resolve("dyn_lookup.json")
                val hash = CompletionTrie.computeHash(compiler.repositories)
                val rebuild = forceRecompile || results.any { it }
                var loaded = if (rebuild) null else CompletionTrie.load(trieFile, hash)
                if (loaded == null) {
                    Chatsounds.logger.info("Building completion trie...")
                    loaded = CompletionTrie.build(lookup)
                    loaded.save(trieFile, hash)
                    Chatsounds.logger.info("Completion trie built ({} roots)", loaded.roots.size)
                }
                trie = loaded
            }
        } catch (e: Exception) {
            Chatsounds.logger.error("Failed to merge repositories", e)
        } finally {
            loading = null
            onInitialized?.invoke()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    fun clearCache() {
        Chatsounds.platform.configDir.resolve("cache").deleteRecursively()
        Chatsounds.logger.info("Cleared cache!")
    }

    @OptIn(ExperimentalPathApi::class)
    fun recompileLists(full: Boolean) {
        Chatsounds.platform.configDir.resolve("cache").deleteRecursively()
        if (full) Chatsounds.platform.configDir.resolve("repositories").deleteRecursively()
        compileLists(forceRecompile = full)
    }
}
