package gg.earu.chatsounds.data

import gg.earu.chatsounds.Chatsounds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Client-side block lists (port of blacklist.lua): by sound variant, realm, or repository. */
object Blacklist {
    @Serializable
    class Config(
        val repositories: MutableSet<String> = HashSet(),
        val realms: MutableSet<String> = HashSet(),
        /** sound key -> blocked variant cache paths. */
        val sounds: MutableMap<String, MutableSet<String>> = HashMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Volatile var config: Config = Config()
        private set

    private val file get() = Chatsounds.platform.configDir.resolve("blacklist.json")

    fun load() {
        if (!file.exists()) return
        config = try {
            json.decodeFromString<Config>(file.readText())
        } catch (e: Exception) {
            Chatsounds.logger.error("Failed to load blacklist.json: {}", e.message)
            Config()
        }
    }

    private fun save() {
        file.parent.createDirectories()
        file.writeText(json.encodeToString(config))
    }

    fun isSoundBlocked(soundKey: String, variant: SoundVariant): Boolean {
        config.sounds[soundKey]?.let { if (variant.path in it) return true }
        if (variant.realm in config.realms) return true
        if (variant.repository in config.repositories) return true
        return false
    }

    /** Returns an error message, or null on success (blacklist.Update port). */
    fun update(block: Boolean, type: String, args: List<String>): String? {
        when (type.lowercase()) {
            "repository", "repo" -> {
                val repo = args.joinToString("").trim().lowercase()
                if (repo.isEmpty()) return "Invalid repository name"
                if (block) config.repositories.add(repo) else config.repositories.remove(repo)
            }
            "realm" -> {
                val realm = args.joinToString("").trim().lowercase()
                if (realm.isEmpty()) return "Invalid realm name"
                if (block) config.realms.add(realm) else config.realms.remove(realm)
            }
            "sound" -> {
                val index = args.firstOrNull()?.toIntOrNull() ?: return "Invalid sound index, not a number"
                val key = args.drop(1).joinToString(" ").trim().lowercase()
                if (key.isEmpty()) return "Invalid sound key"
                val variants = DataLoader.lookup.list[key] ?: return "Invalid sound key, sound does not exist"
                val variant = variants.getOrNull(index - 1) ?: return "Invalid sound index, sound does not exist"
                if (block) {
                    config.sounds.getOrPut(key) { HashSet() }.add(variant.path)
                } else {
                    val set = config.sounds[key] ?: return "Sound key isn't blocked"
                    set.remove(variant.path)
                    if (set.isEmpty()) config.sounds.remove(key)
                }
            }
            else -> return "Invalid block type '$type', valid types are: repository, realm, sound"
        }
        save()
        return null
    }
}
