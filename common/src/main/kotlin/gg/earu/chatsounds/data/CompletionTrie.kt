package gg.earu.chatsounds.data

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.util.sha1Hex
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Autocomplete trie (port of data.lua's dynamic lookup). Iterating 100k+ keys per keystroke
 * is O(n) and freezes the game; instead every word of every sound key is indexed character
 * by character: roots['g'] holds sounds with a word starting with 'g', and once a node holds
 * too many sounds they get pushed down into keys['a'] (words starting with "ga") and so on.
 * Words that ARE the node prefix cannot go deeper and stay in that node. Building is
 * expensive, so the result is cached on disk and invalidated by a repo-set hash.
 */
@Serializable
class TrieNode {
    val sounds: MutableList<String> = ArrayList()
    val keys: MutableMap<String, TrieNode> = HashMap()
    var nextSplit: Int = 0
    /** Set (>1) on root nodes once any split happened; completion only descends then. */
    var depth: Int = 1
}

@Serializable
class TrieFile(val hash: String, val roots: MutableMap<String, TrieNode>)

class CompletionTrie(val roots: MutableMap<String, TrieNode> = HashMap()) {
    companion object {
        private const val MAX_DYN_CHUNK_SIZE = 1000
        private const val DYN_LOOKUP_VERSION = "2"
        private val json = Json { ignoreUnknownKeys = true }

        fun computeHash(repositories: Map<String, CachedRepository>): String =
            sha1Hex(repositories.keys.sorted().joinToString(";") + ";v$DYN_LOOKUP_VERSION")

        fun load(file: Path, expectedHash: String): CompletionTrie? {
            if (!file.exists()) return null
            return try {
                val parsed = json.decodeFromString<TrieFile>(file.readText())
                if (parsed.hash != expectedHash) null else CompletionTrie(parsed.roots)
            } catch (e: Exception) {
                Chatsounds.logger.warn("Discarding corrupt dyn lookup: {}", e.message)
                null
            }
        }

        suspend fun build(lookup: SoundLookup): CompletionTrie {
            val trie = CompletionTrie()
            val nodeSounds = HashMap<TrieNode, MutableSet<String>>()
            var i = 0
            for (soundKey in lookup.list.keys) {
                trie.add(soundKey, nodeSounds)
                if (++i % 512 == 0) yield()
            }
            return trie
        }
    }

    fun save(file: Path, hash: String) {
        file.parent.createDirectories()
        file.writeText(json.encodeToString(TrieFile(hash, roots)))
    }

    private fun addSoundToNode(node: TrieNode, soundKey: String, nodeSounds: MutableMap<TrieNode, MutableSet<String>>) {
        val set = nodeSounds.getOrPut(node) { HashSet() }
        if (set.add(soundKey)) node.sounds.add(soundKey)
    }

    /**
     * Pushes an oversized node's sounds down into children keyed by the character following
     * the node prefix; words that cannot go deeper stay. Returns how many sounds moved.
     */
    private fun splitNode(node: TrieNode, depth: Int, prefix: String, nodeSounds: MutableMap<TrieNode, MutableSet<String>>): Int {
        val remaining = ArrayList<String>()
        val remainingSet = HashSet<String>()
        var movedCount = 0

        for (chunkedKey in node.sounds) {
            var kept = false
            var moved = false
            for (word in chunkedKey.split(" ")) {
                if (word.take(depth) == prefix) {
                    if (word.length > depth) {
                        val child = node.keys.getOrPut(word[depth].toString()) { TrieNode() }
                        addSoundToNode(child, chunkedKey, nodeSounds)
                        moved = true
                    } else {
                        kept = true
                    }
                }
            }
            if ((kept || !moved) && remainingSet.add(chunkedKey)) remaining.add(chunkedKey)
            if (moved) movedCount++
        }

        node.sounds.clear()
        node.sounds.addAll(remaining)
        nodeSounds[node] = remainingSet
        return movedCount
    }

    private fun add(soundKey: String, nodeSounds: MutableMap<TrieNode, MutableSet<String>>) {
        for (word in soundKey.split(" ")) {
            if (word.isEmpty()) continue
            val root = roots.getOrPut(word[0].toString()) { TrieNode() }

            var cur = root
            var depth = 1
            while (depth < word.length) {
                val next = cur.keys[word[depth].toString()] ?: break
                cur = next
                depth++
            }

            addSoundToNode(cur, soundKey, nodeSounds)

            // nextSplit amortizes re-splitting nodes full of words that cannot go deeper.
            if (cur.sounds.size >= MAX_DYN_CHUNK_SIZE && cur.sounds.size >= cur.nextSplit) {
                val moved = splitNode(cur, depth, word.take(depth), nodeSounds)
                cur.nextSplit = cur.sounds.size + MAX_DYN_CHUNK_SIZE
                if (moved > 0) root.depth = maxOf(root.depth, depth + 1)
            }
        }
    }
}
