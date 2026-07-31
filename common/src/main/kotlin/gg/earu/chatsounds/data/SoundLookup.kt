package gg.earu.chatsounds.data

import kotlinx.coroutines.yield

/**
 * The merged, play-time lookup: flat key -> variants map plus the max key length the parser
 * uses to bail out of chunk growth. The autocomplete trie is layered on in a later milestone.
 */
class SoundLookup(
    val list: Map<String, List<SoundVariant>>,
    val maxKeyLength: Int,
) {
    companion object {
        /** "sh" is hardcoded as the stop-all-sounds key. */
        val EMPTY = SoundLookup(mapOf("sh" to emptyList()), maxKeyLength = 2)

        /** Port of data.lua merge_repos (minus the dynamic trie): dedupe by URL, sort by URL for stable variant indexes. */
        suspend fun merge(repositories: Map<String, CachedRepository>): SoundLookup {
            val list = HashMap<String, MutableList<SoundVariant>>()
            list["sh"] = ArrayList()

            var maxKeyLength = 0
            for ((repoName, repo) in repositories) {
                for ((soundKey, variants) in repo.list) {
                    val merged = list.getOrPut(soundKey) { ArrayList() }
                    if (soundKey.length > maxKeyLength) maxKeyLength = soundKey.length

                    for (variant in variants) {
                        if (merged.none { it.url == variant.url }) {
                            variant.repository = repoName
                            merged.add(variant)
                        }
                    }
                    // Preserve variant indexes (select modifier) unless a new sound is added.
                    merged.sortBy { it.url }
                    yield()
                }
            }
            return SoundLookup(list, maxKeyLength)
        }
    }
}
