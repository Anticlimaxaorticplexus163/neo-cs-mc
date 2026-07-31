package gg.earu.chatsounds.client.compat

import gg.earu.chatsounds.Chatsounds
import net.minecraft.client.resources.sounds.SoundInstance
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * Optional Dynamic Surroundings (dsurround) integration: registers our OpenAL sources with
 * its enhanced-sound processor so chatsounds get the same environmental reverb/occlusion as
 * every other sound.
 *
 * DS normally schedules SourceContexts through a static array indexed by AL source id and
 * mixins on vanilla Channels. Our voices are neither vanilla Channels nor guaranteed to
 * have ids inside that array, so we drive the two halves ourselves: the environment
 * raycast (SourceContext#call) on a private worker thread ~3x/s, and the EFX upload
 * (SourceContext#tick) from the audio thread ~20x/s — the same cadences DS uses.
 * Everything is reflective — no compile dependency, and any signature drift in DS just
 * disables the bridge with a log line.
 */
object DsurroundBridge {
    private var resolved = false

    private var isAvailable: Method? = null
    private var ctxCtor: Constructor<*>? = null
    private var attachSound: Method? = null
    private var enable: Method? = null
    private var callMethod: Method? = null
    private var tickMethod: Method? = null

    /** Off-thread environment raycasting, mirroring DS's own worker pool. */
    private val calcExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "chatsounds-dsurround").apply { isDaemon = true } }
    }

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
            ctxCtor = context.getDeclaredConstructor(Int::class.javaPrimitiveType)
            attachSound = context.getDeclaredMethod("attachSound", SoundInstance::class.java)
            enable = context.getDeclaredMethod("enable")
            callMethod = context.getDeclaredMethod("call")
            tickMethod = context.getDeclaredMethod("tick")
            resolved = true
            Chatsounds.logger.info("Dynamic Surroundings detected — chatsounds voices will get environmental reverb")
        } catch (_: ClassNotFoundException) {
            // DS not installed; stay silent.
        } catch (e: Throwable) {
            Chatsounds.logger.warn("Dynamic Surroundings found but its internals changed; reverb bridge disabled ({})", e.toString())
        }
    }

    private var loggedInactive = false
    private var loggedFirstRegister = false

    private fun active(): Boolean = resolved && try {
        isAvailable!!.invoke(null) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    /** Creates a DS SourceContext for an AL source; returns the handle or null. */
    fun register(sourceId: Int, sound: SoundInstance): Any? {
        if (!resolved) return null
        if (!active()) {
            if (!loggedInactive) {
                loggedInactive = true
                Chatsounds.logger.info("Dynamic Surroundings present but its enhanced sound processor is not active — no reverb on chatsounds (check enableEnhancedSounds)")
            }
            return null
        }
        return try {
            val ctx = ctxCtor!!.newInstance(sourceId)
            attachSound!!.invoke(ctx, sound)
            enable!!.invoke(ctx)
            calc(ctx) // initial environment evaluation
            if (!loggedFirstRegister) {
                loggedFirstRegister = true
                Chatsounds.logger.info("Chatsounds voice registered with Dynamic Surroundings enhanced sounds (source {})", sourceId)
            }
            ctx
        } catch (e: Throwable) {
            Chatsounds.logger.warn("dsurround register failed: {}", e.toString())
            null
        }
    }

    /** Schedules the environment raycast off-thread (SourceContext#call). */
    fun calc(ctx: Any) {
        if (!active()) return
        calcExecutor.execute {
            runCatching { callMethod!!.invoke(ctx) }
        }
    }

    /** Uploads the computed EFX state to the source (safe from the audio thread). */
    fun tick(ctx: Any) {
        runCatching { tickMethod!!.invoke(ctx) }
    }

    fun unregister(@Suppress("UNUSED_PARAMETER") sourceId: Int, @Suppress("UNUSED_PARAMETER") ctx: Any) {
        // Nothing to clear: the context was never handed to DS's scheduler, and its AL
        // filter objects die with the source.
    }
}
