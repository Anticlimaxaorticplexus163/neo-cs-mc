package gg.earu.chatsounds.data

/**
 * Ordered list of content providers, tried in this order. No single free GitHub CDN is
 * reliable, so callers fall back to the next one on any non-200 response or failure.
 * raw.githubusercontent.com is LAST because it is the origin that actually rate-limits
 * (per-IP 429s) — the caching CDNs are exhausted first. Reordering is safe: the on-disk
 * cache key derives from [canonicalUrl], not from this order.
 */
object ContentProviders {
    val providers: List<(repo: String, branch: String, path: String) -> String> = listOf(
        { repo, branch, path -> "https://cdn.jsdelivr.net/gh/$repo@$branch/$path" },
        { repo, branch, path -> "https://cdn.statically.io/gh/$repo@$branch/$path" },
        { repo, branch, path -> "https://raw.githack.com/$repo/$branch/$path" },
        { repo, branch, path -> "https://raw.githubusercontent.com/$repo/$branch/$path" },
    )

    /** Stable canonical URL, independent of provider order; used only for cache-key derivation. */
    fun canonicalUrl(repo: String, branch: String, path: String): String =
        "https://raw.githubusercontent.com/$repo/$branch/$path"

    fun buildUrls(repo: String, branch: String, path: String): List<String> =
        providers.map { it(repo, branch, path) }

    /** Ordered provider URLs for a compiled sound entry; legacy entries fall back to their baked-in URL. */
    fun soundUrls(variant: SoundVariant): List<String> =
        if (variant.repo.isEmpty() || variant.branch.isEmpty() || variant.contentPath.isEmpty()) listOf(variant.url)
        else buildUrls(variant.repo, variant.branch, variant.contentPath)
}
