package gg.earu.chatsounds.data

import kotlinx.serialization.Serializable

/**
 * One playable file for a sound key. A key with several variants means several files
 * (usually one per realm/game); selection happens at play time (select/realm modifiers,
 * realm-matching, seeded random).
 */
@Serializable
data class SoundVariant(
    /** Canonical raw.githubusercontent.com URL — cache-key seed and dedupe identity. */
    val url: String,
    val realm: String,
    /** Disk cache path relative to the chatsounds config dir: `cache/{realm}/{sha1(url)}.ogg`. */
    val path: String,
    val repo: String = "",
    val branch: String = "",
    /** Path inside the repo, used to rebuild provider URLs at download time. */
    val contentPath: String = "",
) {
    /** Set after merge: "repo/branch/basePath" this variant came from (blacklist by repository). */
    var repository: String = ""
}
