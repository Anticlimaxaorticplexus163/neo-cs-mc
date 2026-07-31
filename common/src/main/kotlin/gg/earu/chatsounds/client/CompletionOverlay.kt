package gg.earu.chatsounds.client

import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.data.DataLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * The chat-screen suggestion overlay, loader-agnostic (only vanilla GuiGraphics). GMod's
 * layout flipped upward: selected first, wrap-around, separator before the wrapped head.
 */
object CompletionOverlay {
    private var lastChatInput: String? = null

    /** Call every chat-screen frame with the current input value before [render]. */
    fun pollInput(value: String) {
        if (value == lastChatInput) return
        lastChatInput = value
        if (value.startsWith("/")) CompletionEngine.clear() else CompletionEngine.onTextChanged(value)
    }

    fun render(graphics: GuiGraphics, screenHeight: Int) {
        if (!ClientConfig.data.enabled) return
        val font = Minecraft.getInstance().font
        val baseY = screenHeight - 30 // just above the chat input box
        val lineHeight = font.lineHeight + 1

        DataLoader.loading?.let { loading ->
            graphics.drawString(font, "Loading chatsounds... ${loading.percent}%", 4, baseY - lineHeight, 0xFFFFFF)
            return
        }

        val suggestions = CompletionEngine.suggestions
        if (suggestions.isEmpty()) return

        val selected = CompletionEngine.index
        val maxRows = (baseY / lineHeight) - 2
        var row = 0

        fun draw(indexInList: Int, isSelected: Boolean) {
            if (row >= maxRows) return
            val suggestion = suggestions[indexInList]
            val y = baseY - row * lineHeight
            graphics.drawString(font, "%03d.".format(indexInList + 1), 4, y, 0xC8C8FF)
            graphics.drawString(font, suggestion.text, 34, y, if (isSelected) 0xFF4040 else 0xFFFFFF)
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

    /** Tab pressed with [currentValue] in the box; returns the replacement text or null. */
    fun onTab(currentValue: String, reverse: Boolean): String? {
        if (!ClientConfig.data.enabled) return null
        if (currentValue.startsWith("/")) return null // vanilla command completion owns Tab there
        if (CompletionEngine.suggestions.isEmpty()) return null
        return CompletionEngine.cycle(reverse)?.also { lastChatInput = it }
    }
}
