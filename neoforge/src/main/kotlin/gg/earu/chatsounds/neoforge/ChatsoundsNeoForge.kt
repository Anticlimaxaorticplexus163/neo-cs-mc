package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.Chatsounds
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths

@Mod(Chatsounds.MOD_ID)
class ChatsoundsNeoForge(container: ModContainer) {
    init {
        Chatsounds.init(
            NeoForgePlatform(
                configDir = FMLPaths.CONFIGDIR.get().resolve("chatsounds"),
                isClient = FMLEnvironment.dist.isClient,
                modVersion = container.modInfo.version.toString(),
            )
        )
    }
}
