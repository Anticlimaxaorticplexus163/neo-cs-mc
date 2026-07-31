package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.client.IncomingChat
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.data.RepoConfig
import gg.earu.chatsounds.net.ChatsoundsPayloads

/** Client-only payload handling; this class must never load on a dedicated server. */
object ClientPayloadHandler {
    fun handleRepoConfig(payload: ChatsoundsPayloads.RepoConfigPayload) {
        Chatsounds.logger.info("Received server repo config!")
        IncomingChat.serverAuthoritative = true
        try {
            DataLoader.repoConfig = RepoConfig.parse(payload.json)
            DataLoader.compileLists()
        } catch (e: Exception) {
            Chatsounds.logger.error("Invalid server repo config: {}", e.message)
        }
    }

    fun handleRelay(payload: ChatsoundsPayloads.RelayPayload) {
        IncomingChat.onRelay(payload.sender, payload.text)
    }
}
