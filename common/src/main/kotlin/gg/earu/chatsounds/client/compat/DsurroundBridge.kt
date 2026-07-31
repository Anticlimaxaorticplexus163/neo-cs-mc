package gg.earu.chatsounds.client.compat

import gg.earu.chatsounds.Chatsounds
import net.minecraft.client.resources.sounds.SoundInstance
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Optional Dynamic Surroundings (dsurround) integration: registers our OpenAL sources with
 * its enhanced-sound processor so chatsounds get the same environmental reverb/occlusion as
 * every other sound.
 *
 * DS keeps a static SourceContext[] indexed by AL source id; its worker thread raycasts the
 * environment per context, and Channel-tick mixins upload the EFX state via
 * SourceContext#tick(). Our voices aren't vanilla Channels, so we mirror the three touch
 * points ourselves: register on play, tick ~20 Hz from the audio thread, clear on stop.
 * Everything is reflective — no compile dependency, and any signature drift in DS just
 * disables the bridge with a log line.
 */
object DsurroundBridge {
    private var resolved = false

    private var isAvailable: Method? = null
    private var sourcesField: Field? = null
    private var ctxCtor: Constructor<*>? = null
    private var attachSound: Method? = null
    private var enable: Method? = null
    private var exec: Method? = null
    private var tickMethod: Method? = null

    /**
     * Must be called lazily (first sound play), never at client init: resolving eagerly
     * would run before DS's own entrypoint and trip its static initializers while its DI
     * container is still empty. initialize=false defers static init to first invocation.
     */
    fun init() {
        if (resolved) return
        try {
            val loader = javaClass.classLoader
            val processor = Class.forName("org.orecruncher.dsurround.runtime.audio.SoundFXProcessor", false, loader)
            val context = Class.forName("org.orecruncher.dsurround.runtime.audio.SourceContext", false, loader)
            isAvailable = processor.getDeclaredMethod("isAvailable")
            sourcesField = processor.getDeclaredField("sources").apply { isAccessible = true }
            ctxCtor = context.getDeclaredConstructor(Int::class.javaPrimitiveType)
            attachSound = context.getDeclaredMethod("attachSound", SoundInstance::class.java)
            enable = context.getDeclaredMethod("enable")
            exec = context.getDeclaredMethod("exec")
            tickMethod = context.getDeclaredMethod("tick")
            resolved = true
            Chatsounds.logger.info("Dynamic Surroundings detected — chatsounds voices will get environmental reverb")
        } catch (_: ClassNotFoundException) {
            // DS not installed; stay silent.
        } catch (e: Throwable) {
            Chatsounds.logger.warn("Dynamic Surroundings found but its internals changed; reverb bridge disabled ({})", e.toString())
        }
    }

    private fun active(): Boolean = resolved && try {
        isAvailable!!.invoke(null) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    /** Registers an AL source with DS; returns the SourceContext handle or null. */
    fun register(sourceId: Int, sound: SoundInstance): Any? {
        if (!active()) return null
        return try {
            val sources = sourcesField!!.get(null) as? Array<Any?> ?: return null
            val index = sourceId - 1
            if (index !in sources.indices) return null // beyond DS's tracked range
            val ctx = ctxCtor!!.newInstance(sourceId)
            attachSound!!.invoke(ctx, sound)
            enable!!.invoke(ctx)
            exec!!.invoke(ctx) // initial environment evaluation, DS onSourcePlay parity
            sources[index] = ctx
            ctx
        } catch (e: Throwable) {
            Chatsounds.logger.debug("dsurround register failed: {}", e.toString())
            null
        }
    }

    /** Uploads the computed EFX state to the source (safe from the audio thread). */
    fun tick(ctx: Any) {
        runCatching { tickMethod!!.invoke(ctx) }
    }

    fun unregister(sourceId: Int, ctx: Any) {
        runCatching {
            val sources = sourcesField!!.get(null) as? Array<Any?> ?: return
            val index = sourceId - 1
            if (index in sources.indices && sources[index] === ctx) sources[index] = null
        }
    }
}
