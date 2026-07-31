package gg.earu.chatsounds

import gg.earu.chatsounds.audio.PcmCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class ClientConfigData(
    val enabled: Boolean = true,
    /** Extra volume multiplier on top of the PLAYERS sound category. */
    val volume: Double = 1.0,
    /** Hide chat messages that are (almost) entirely sound triggers. */
    val hideText: Boolean = false,
    /** 0: sh disabled, 1: only your own "sh" stops sounds, 2: anyone's does. */
    val shMode: Int = 1,
    /** Attenuation range in blocks for positional chatsounds. */
    val maxDistance: Double = 64.0,
    val pcmCacheMb: Int = 256,
    /** Sender-extraction regexes for system-formatted chat; group 1 = name, group 2 = message. */
    val senderPatterns: List<String> = listOf(
        "^<([A-Za-z0-9_]{1,16})>\\s?(.*)$",
        "^\\[?([A-Za-z0-9_]{1,16})]?[:>]\\s(.*)$",
    ),
    /** Play sounds from senders we cannot resolve to an entity (unpositioned). */
    val playUnpositioned: Boolean = true,
)

object ClientConfig {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Volatile var data: ClientConfigData = ClientConfigData()
        private set

    private val file get() = Chatsounds.platform.configDir.resolve("client_config.json")

    fun load() {
        data = if (file.exists()) {
            try {
                json.decodeFromString<ClientConfigData>(file.readText())
            } catch (e: Exception) {
                Chatsounds.logger.error("Failed to load client_config.json: {}", e.message)
                ClientConfigData()
            }
        } else {
            ClientConfigData().also { save(it) }
        }
        apply()
    }

    fun save(newData: ClientConfigData = data) {
        data = newData
        file.parent.createDirectories()
        file.writeText(json.encodeToString(newData))
        apply()
    }

    fun update(mutate: (ClientConfigData) -> ClientConfigData) = save(mutate(data))

    private fun apply() {
        PcmCache.budgetBytes = data.pcmCacheMb.toLong() * 1024 * 1024
    }
}
