package gg.earu.chatsounds.neoforge

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.client.CompletionEngine
import gg.earu.chatsounds.client.HideText
import gg.earu.chatsounds.data.Blacklist
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.mixin.ChatScreenAccessor
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import org.lwjgl.glfw.GLFW
import java.util.UUID

object ClientEvents {
    private var lastChatInput: String? = null

    // ---- Incoming chat ----

    @SubscribeEvent(priority = EventPriority.LOW)
    fun onPlayerChat(event: ClientChatReceivedEvent.Player) {
        // signedContent is the raw typed message, before any server/client decoration.
        val text = event.playerChatMessage.signedContent()
        if (maybeHide(event, text, event.sender)) return
        AudioEngine.start()
        ChatsoundsPlayer.play(event.sender, text, isOwn = event.sender == Minecraft.getInstance().player?.uuid)
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    fun onSystemChat(event: ClientChatReceivedEvent.System) {
        if (event.isOverlay) return
        val raw = ChatFormatting.stripFormatting(event.message.string) ?: return
        for (patternStr in ClientConfig.data.senderPatterns) {
            val pattern = runCatching { Regex(patternStr) }.getOrNull() ?: continue
            val match = pattern.find(raw) ?: continue
            val (name, message) = match.destructured
            val senderId = resolvePlayerUuid(name)
            if (senderId == null && !ClientConfig.data.playUnpositioned) return
            if (maybeHide(event, message, senderId)) return
            AudioEngine.start()
            ChatsoundsPlayer.play(senderId, message, isOwn = name == Minecraft.getInstance().player?.gameProfile?.name)
            return
        }
    }

    private fun maybeHide(event: ClientChatReceivedEvent, text: String, senderId: UUID?): Boolean {
        if (!HideText.shouldHide(text)) return false
        event.isCanceled = true
        val name = senderId?.let { Minecraft.getInstance().connection?.getPlayerInfo(it)?.profile?.name } ?: "?"
        Minecraft.getInstance().gui.chat.addMessage(Component.literal("Hidden chatsounds message from $name"))
        // Still play the sounds from the hidden message.
        AudioEngine.start()
        ChatsoundsPlayer.play(senderId, text, isOwn = senderId == Minecraft.getInstance().player?.uuid)
        return true
    }

    private fun resolvePlayerUuid(name: String): UUID? =
        Minecraft.getInstance().connection?.getPlayerInfo(name)?.profile?.id

    // ---- Tick ----

    @SubscribeEvent
    fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
        ChatsoundsPlayer.clientTick()
    }

    // ---- Completion UI ----

    @SubscribeEvent
    fun onScreenRender(event: ScreenEvent.Render.Post) {
        val screen = event.screen as? ChatScreen ?: return
        if (!ClientConfig.data.enabled) return

        val input = (screen as ChatScreenAccessor).`chatsounds$getInput`()
        val value = input.value
        if (value != lastChatInput) {
            lastChatInput = value
            if (value.startsWith("/")) CompletionEngine.clear() else CompletionEngine.onTextChanged(value)
        }

        val graphics = event.guiGraphics
        val font = Minecraft.getInstance().font
        val baseY = screen.height - 30 // just above the chat input box
        val lineHeight = font.lineHeight + 1

        DataLoader.loading?.let { loading ->
            graphics.drawString(font, "Loading chatsounds... ${loading.percent}%", 4, baseY - lineHeight, 0xFFFFFF)
            return
        }

        val suggestions = CompletionEngine.suggestions
        if (suggestions.isEmpty()) return

        // GMod layout, flipped upward: selected first, wrap-around, separator before the wrapped head.
        val selected = CompletionEngine.index
        val maxRows = (baseY / lineHeight) - 2
        var row = 0

        fun draw(indexInList: Int, isSelected: Boolean) {
            if (row >= maxRows) return
            val suggestion = suggestions[indexInList]
            val y = baseY - row * lineHeight
            graphics.drawString(font, "%03d.".format(indexInList + 1), 4, y, 0xC8C8FF)
            val color = if (isSelected) 0xFF4040 else 0xFFFFFF
            graphics.drawString(font, suggestion.text, 34, y, color)
            suggestion.extra?.let {
                graphics.drawString(font, it, 34 + font.width(suggestion.text) + 12, y, 0xFFC850)
            }
            row++
        }

        val start = maxOf(0, selected)
        for (i in start until suggestions.size) draw(i, i == selected)
        if (start > 0) {
            if (row < maxRows) {
                graphics.drawString(font, "==================", 4, baseY - row * lineHeight, 0xB4B4FF)
                row++
            }
            for (i in 0 until start) draw(i, false)
        }
    }

    @SubscribeEvent
    fun onScreenKeyPressed(event: ScreenEvent.KeyPressed.Pre) {
        val screen = event.screen as? ChatScreen ?: return
        if (event.keyCode != GLFW.GLFW_KEY_TAB) return
        if (!ClientConfig.data.enabled) return

        val input = (screen as ChatScreenAccessor).`chatsounds$getInput`()
        if (input.value.startsWith("/")) return // vanilla command completion owns Tab there
        if (CompletionEngine.suggestions.isEmpty()) return

        val window = Minecraft.getInstance().window.window
        val reverse = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS

        CompletionEngine.cycle(reverse)?.let { replacement ->
            input.value = replacement
            lastChatInput = replacement // don't rebuild suggestions off our own replacement
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
