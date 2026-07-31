package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.platform.Platform
import java.nio.file.Path

class NeoForgePlatform(
    override val configDir: Path,
    override val isClient: Boolean,
    override val modVersion: String,
) : Platform
