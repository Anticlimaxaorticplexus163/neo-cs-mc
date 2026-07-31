package gg.earu.chatsounds.playback

import gg.earu.chatsounds.data.SoundVariant
import kotlin.random.Random

/**
 * Picks which file to play for a matched key (port of cs_player.GetWantedSound). Seeded so
 * every client hearing the same message picks the same variant. When nothing forced a
 * choice, variants matching the previous sound's realm are preferred ("realm matching"
 * keeps one voice/game consistent across a sentence).
 */
object VariantSelector {
    fun select(
        variants: List<SoundVariant>,
        lastSound: SoundVariant?,
        seed: Long,
        forcedIndex: Int? = null,
        forcedRealm: String? = null,
    ): SoundVariant? {
        if (variants.isEmpty()) return null

        val rng = Random(seed)
        var pool = variants
        var index = rng.nextInt(pool.size)
        var modified = false

        if (forcedRealm != null) {
            val realmPool = pool.filter { it.realm == forcedRealm }
            if (realmPool.isNotEmpty()) {
                pool = realmPool
                index = rng.nextInt(pool.size)
                modified = true
            }
        }

        if (forcedIndex != null) {
            index = forcedIndex
            modified = true
        }

        if (!modified && lastSound != null) {
            val realmPool = pool.filter { it.realm == lastSound.realm }
            if (realmPool.isNotEmpty()) {
                pool = realmPool
                index = rng.nextInt(pool.size)
            }
        }

        return pool[index.coerceIn(0, pool.size - 1)]
    }
}
