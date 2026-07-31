package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.data.DataLoader
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge

@Mod(Chatsounds.MOD_ID)
class ChatsoundsNeoForge(container: ModContainer, modBus: IEventBus) {
    init {
        Chatsounds.init(
            NeoForgePlatform(
                configDir = FMLPaths.CONFIGDIR.get().resolve("chatsounds"),
                isClient = FMLEnvironment.dist.isClient,
                modVersion = container.modInfo.version.toString(),
            )
        )

        if (FMLEnvironment.dist.isClient) {
            NeoForge.EVENT_BUS.register(ClientEvents)
            modBus.register(ModBusEvents)
        }
    }

    object ModBusEvents {
        @SubscribeEvent
        fun onClientSetup(@Suppress("UNUSED_PARAMETER") event: FMLClientSetupEvent) {
            // List compilation is fully async; playback and completion gate on DataLoader state.
            DataLoader.startup()
        }
    }
}
