package gg.earu.chatsounds.neoforge

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.client.CompletionOverlay
import gg.earu.chatsounds.client.IncomingChat
import gg.earu.chatsounds.data.Blacklist
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.mixin.ChatScreenAccessor
import gg.earu.chatsounds.net.ChatsoundsPayloads
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import org.lwjgl.glfw.GLFW

object ClientEvents {
    // ---- Incoming chat ----

    @SubscribeEvent(priority = EventPriority.LOW)
    fun onPlayerChat(event: ClientChatReceivedEvent.Player) {
        // signedContent is the raw typed message, before any server/client decoration.
        val result = IncomingChat.onPlayerChat(event.playerChatMessage.signedContent(), event.sender)
        if (result.hidden) event.isCanceled = true
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    fun onSystemChat(event: ClientChatReceivedEvent.System) {
        if (event.isOverlay) return
        val raw = ChatFormatting.stripFormatting(event.message.string) ?: return
        val result = IncomingChat.onSystemChat(raw) ?: return
        if (result.hidden) event.isCanceled = true
    }

    // ---- Tick ----

    @SubscribeEvent
    fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
        ChatsoundsPlayer.clientTick()
    }

    // ---- Completion UI ----

    @SubscribeEvent
    fun onScreenRender(event: ScreenEvent.Render.Post) {
        val screen = event.screen as? ChatScreen ?: return
        CompletionOverlay.pollInput((screen as ChatScreenAccessor).`chatsounds$getInput`().value)
        CompletionOverlay.render(event.guiGraphics, screen.height)
    }

    @SubscribeEvent
    fun onScreenKeyPressed(event: ScreenEvent.KeyPressed.Pre) {
        val screen = event.screen as? ChatScreen ?: return
        if (event.keyCode != GLFW.GLFW_KEY_TAB) return

        val input = (screen as ChatScreenAccessor).`chatsounds$getInput`()
        val window = Minecraft.getInstance().window.window
        val reverse = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS

        CompletionOverlay.onTab(input.value, reverse)?.let { replacement ->
            input.value = replacement
            event.isCanceled = true
        }
    }

    // ---- Commands ----

    @SubscribeEvent
    fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        fun feedback(ctx: CommandContext<CommandSourceStack>, message: String) {
            ctx.source.sendSystemMessage(Component.literal("[chatsounds] $message"))
        }

        event.dispatcher.register(
            Commands.literal("chatsounds")
                .then(Commands.literal("sh").executes { ChatsoundsPlayer.stopAll(); 1 })
                .then(
                    Commands.literal("say").then(
                        Commands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                            AudioEngine.start()
                            ChatsoundsPlayer.play(null, StringArgumentType.getString(ctx, "text"))
                            1
                        }
                    )
                )
                .then(
                    Commands.literal("broadcast").then(
                        Commands.argument("text", StringArgumentType.greedyString()).executes { ctx ->
                            val text = StringArgumentType.getString(ctx, "text")
                            val connection = Minecraft.getInstance().connection
                            if (connection != null && connection.hasChannel(ChatsoundsPayloads.SaySoundPayload.TYPE)) {
                                connection.send(ServerboundCustomPayloadPacket(ChatsoundsPayloads.SaySoundPayload(text)))
                            } else {
                                AudioEngine.start()
                                ChatsoundsPlayer.play(null, text)
                            }
                            1
                        }
                    )
                )
                .then(Commands.literal("toggle").executes { ctx ->
                    ClientConfig.update { it.copy(enabled = !it.enabled) }
                    feedback(ctx, if (ClientConfig.data.enabled) "enabled" else "disabled")
                    1
                })
                .then(
                    Commands.literal("volume").then(
                        Commands.argument("volume", DoubleArgumentType.doubleArg(0.0, 4.0)).executes { ctx ->
                            ClientConfig.update { it.copy(volume = DoubleArgumentType.getDouble(ctx, "volume")) }
                            feedback(ctx, "volume set to ${ClientConfig.data.volume}")
                            1
                        }
                    )
                )
                .then(Commands.literal("hidetext").executes { ctx ->
                    ClientConfig.update { it.copy(hideText = !it.hideText) }
                    feedback(ctx, "hide-text ${if (ClientConfig.data.hideText) "on" else "off"}")
                    1
                })
                .then(
                    Commands.literal("shmode").then(
                        Commands.argument("mode", IntegerArgumentType.integer(0, 2)).executes { ctx ->
                            ClientConfig.update { it.copy(shMode = IntegerArgumentType.getInteger(ctx, "mode")) }
                            feedback(ctx, "sh mode ${ClientConfig.data.shMode}")
                            1
                        }
                    )
                )
                .then(
                    Commands.literal("block").then(
                        Commands.argument("type", StringArgumentType.word()).then(
                            Commands.argument("args", StringArgumentType.greedyString()).executes { ctx ->
                                blockCommand(ctx, block = true)
                            }
                        )
                    )
                )
                .then(
                    Commands.literal("unblock").then(
                        Commands.argument("type", StringArgumentType.word()).then(
                            Commands.argument("args", StringArgumentType.greedyString()).executes { ctx ->
                                blockCommand(ctx, block = false)
                            }
                        )
                    )
                )
                .then(Commands.literal("reload").executes { DataLoader.recompileLists(full = false); 1 })
                .then(Commands.literal("reloadfull").executes { DataLoader.recompileLists(full = true); 1 })
                .then(Commands.literal("clearcache").executes { ctx ->
                    DataLoader.clearCache()
                    feedback(ctx, "cache cleared")
                    1
                })
        )
    }

    private fun blockCommand(ctx: CommandContext<CommandSourceStack>, block: Boolean): Int {
        val type = StringArgumentType.getString(ctx, "type")
        val args = StringArgumentType.getString(ctx, "args").split(" ")
        val error = Blacklist.update(block, type, args)
        val message = error ?: "${if (block) "blocked" else "unblocked"} $type ${args.joinToString(" ")}"
        ctx.source.sendSystemMessage(Component.literal("[chatsounds] $message"))
        return if (error == null) 1 else 0
    }
}
