package gg.earu.chatsounds.data

import gg.earu.chatsounds.Chatsounds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * One sound repository to compile lists from. Serial names match the GMod addon's
 * repo_config.json so configs can be copied between the two verbatim.
 */
@Serializable
data class RepoEntry(
    @SerialName("Repo") val repo: String,
    @SerialName("Branch") val branch: String = "master",
    @SerialName("BasePath") val basePath: String,
    @SerialName("UseMsgPack") val useMsgPack: Boolean = false,
)

object RepoConfig {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    // GMod-default parity: valve lists only. Community repos (e.g.
    // Metastruct/garrysmod-chatsounds) are opt-in via repo_config.json.
    val default: List<RepoEntry> =
        listOf("csgo", "css", "ep1", "ep2", "hl1", "hl2", "l4d", "l4d2", "portal", "tf2").map {
            RepoEntry(repo = "PAC3-Server/chatsounds-valve-games", branch = "master", basePath = it, useMsgPack = true)
        }

    /** Loads config/chatsounds/repo_config.json, creating it with defaults on first run. */
    fun load(): List<RepoEntry> {
        val path = Chatsounds.platform.configDir.resolve("repo_config.json")
        if (!path.exists()) {
            path.parent.createDirectories()
            path.writeText(json.encodeToString(default))
            return default
        }
        return try {
            json.decodeFromString<List<RepoEntry>>(path.readText())
        } catch (e: Exception) {
            Chatsounds.logger.error("Failed to load repo_config.json: {}", e.message)
            default
        }
    }

    fun parse(jsonText: String): List<RepoEntry> = json.decodeFromString(jsonText)
    fun encode(entries: List<RepoEntry>): String = json.encodeToString(entries)
}
