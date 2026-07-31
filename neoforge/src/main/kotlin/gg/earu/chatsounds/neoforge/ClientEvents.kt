package gg.earu.chatsounds.neoforge

import com.mojang.brigadier.arguments.StringArgumentType
import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import java.util.UUID

object ClientEvents {
    /**
     * Sender-extraction patterns for system-formatted chat (Paper/plugins reformat player
     * chat into system messages). First group = name, second group = message. User-editable
     * config lands in M3.
     */
    private val senderPatterns = listOf(
        Regex("^<([A-Za-z0-9_]{1,16})>\\s?(.*)$"),
        Regex("^\\[?([A-Za-z0-9_]{1,16})]?[:>]\\s(.*)$"),
    )

    @SubscribeEvent
    fun onPlayerChat(event: ClientChatReceivedEvent.Player) {
        AudioEngine.start()
        // signedContent is the raw typed message, before any server/client decoration.
        ChatsoundsPlayer.play(event.sender, event.playerChatMessage.signedContent())
    }

    @SubscribeEvent
    fun onSystemChat(event: ClientChatReceivedEvent.System) {
        if (event.isOverlay) return
        val raw = ChatFormatting.stripFormatting(event.message.string) ?: return
        for (pattern in senderPatterns) {
            val match = pattern.find(raw) ?: continue
            val (name, message) = match.destructured
            AudioEngine.start()
            ChatsoundsPlayer.play(resolvePlayerUuid(name), message)
            return
        }
    }

    private fun resolvePlayerUuid(name: String): UUID? =
        Minecraft.getInstance().connection?.getPlayerInfo(name)?.profile?.id

    @SubscribeEvent
    fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
        ChatsoundsPlayer.clientTick()
    }

    @SubscribeEvent
    fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("chatsounds")
                .then(Commands.literal("sh").executes {
                    ChatsoundsPlayer.stopAll()
                    1
                })
                .then(
                    Commands.literal("say").then(
                        Commands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                            AudioEngine.start()
                            ChatsoundsPlayer.play(null, StringArgumentType.getString(ctx, "text"))
                            1
                        }
                    )
                )
                .then(Commands.literal("toggle").executes {
                    ChatsoundsPlayer.enabled = !ChatsoundsPlayer.enabled
                    Chatsounds.logger.info("Chatsounds {}", if (ChatsoundsPlayer.enabled) "enabled" else "disabled")
                    1
                })
                .then(Commands.literal("reload").executes {
                    DataLoader.recompileLists(full = false)
                    1
                })
                .then(Commands.literal("reloadfull").executes {
                    DataLoader.recompileLists(full = true)
                    1
                })
                .then(Commands.literal("clearcache").executes {
                    DataLoader.clearCache()
                    1
                })
        )
    }
}
