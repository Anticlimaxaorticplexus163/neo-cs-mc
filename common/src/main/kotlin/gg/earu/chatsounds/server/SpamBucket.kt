package gg.earu.chatsounds.server

import java.util.UUID

/**
 * Token-bucket-ish chat rate limiter (port of player.lua's spam_watch). Loader-agnostic;
 * the server module feeds it real time in seconds.
 */
class SpamBucket {
    private companion object {
        const val SPAM_STEP = 0.1 // messages per second after the burst
        const val SPAM_MAX = 1.0  // burst size
    }

    private class Entry(var time: Double, var message: String)

    private val lookup = HashMap<UUID, Entry>()

    private fun messageCost(message: String, isSameMessage: Boolean): Double {
        val realLength = message.codePointCount(0, message.length)
        return when {
            realLength > 1024 -> SPAM_MAX - 1
            isSameMessage && realLength > 128 -> 1.0
            else -> 0.0
        }
    }

    /** Returns true when the message should be dropped as spam. */
    fun isSpam(playerId: UUID, message: String, nowSeconds: Double, exempt: Boolean = false): Boolean {
        if (exempt) return false

        val last = lookup.getOrPut(playerId) { Entry(0.0, "") }
        if (last.time < nowSeconds) last.time = nowSeconds

        val isSameMessage = last.message == message
        last.message = message

        var newTime = last.time + SPAM_STEP + messageCost(message, isSameMessage)
        if (newTime > nowSeconds + SPAM_MAX) {
            // Don't let the rate limit build up forever.
            newTime = minOf(newTime, nowSeconds + SPAM_MAX + 1)
            last.time = newTime
            return true
        }

        last.time = newTime
        return false
    }

    fun forget(playerId: UUID) {
        lookup.remove(playerId)
    }
}
