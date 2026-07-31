package gg.earu.chatsounds

import gg.earu.chatsounds.platform.Platform
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Chatsounds {
    const val MOD_ID = "chatsounds"

    val logger: Logger = LoggerFactory.getLogger("chatsounds")

    lateinit var platform: Platform
        private set

    fun init(platform: Platform) {
        this.platform = platform
        logger.info("neo-cs-mc initializing (config dir: {})", platform.configDir)
    }
}
