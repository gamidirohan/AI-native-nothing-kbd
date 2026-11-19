package org.futo.inputmethod.latin.uix.actions.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.futo.inputmethod.litert.LiteRtBridge
import java.util.concurrent.Executors

private const val TAG = "LiteRtRewriteEngine"

/** The model stays loaded for a while after use so repeated taps are fast, then is released. */
private const val IDLE_RELEASE_MS = 90_000L

/**
 * Runs rewrites on a local `.litertlm` model through LiteRT-LM, via the Java-only
 * `:litert-bridge` module (LiteRT-LM cannot be used directly from this module's Kotlin 1.9).
 *
 * LiteRT-LM wants one thread to own the engine, so every native call runs on [dispatcher].
 * Each request uses a fresh conversation, so earlier text never leaks into a later rewrite.
 */
class LiteRtRewriteEngine(context: Context) : RewriteEngine {
    override val name = "LiteRT-LM"

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-rewrite") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Mutex()

    private var bridge: LiteRtBridge? = null
    private var loadedPath: String? = null
    private var idleJob: Job? = null

    override suspend fun rewrite(request: RewriteRequest, onPartial: (String) -> Unit): String {
        if (!LlmModelStore.isInstalled(appContext)) throw RewriteException.ModelMissing()
        val modelPath = LlmModelStore.defaultModelFile(appContext).absolutePath

        return lock.withLock {
            idleJob?.cancel()
            try {
                // runInterruptible turns coroutine cancellation into a thread interrupt, which
                // the bridge uses to stop generation.
                runInterruptible(dispatcher) { generate(modelPath, request, onPartial) }
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    private fun generate(
        modelPath: String,
        request: RewriteRequest,
        onPartial: (String) -> Unit
    ): String {
        val bridge = ensureBridge(modelPath)
        val sampler = samplerFor(request.style)
        val steps = request.style.instructions

        var current = request.text
        for ((index, instruction) in steps.withIndex()) {
            val isLast = index == steps.lastIndex
            val raw = StringBuilder()

            try {
                bridge.generate(
                    RewritePrompts.SYSTEM,
                    RewritePrompts.user(instruction, current),
                    sampler.topK, sampler.topP, sampler.temperature
                ) { chunk ->
                    // Only the last step is shown while it streams; earlier steps are intermediate.
                    raw.append(chunk)
                    if (isLast) onPartial(RewriteOutput.clean(raw.toString(), request.text))
                }
            } catch (e: InterruptedException) {
                throw CancellationException("Rewrite cancelled").also { it.initCause(e) }
            } catch (e: Exception) {
                throw RewriteException("The model failed: ${e.message}", e)
            }

            val output = RewriteOutput.clean(raw.toString(), request.text)
            if (output.isBlank()) throw RewriteException("The model returned nothing. Try again.")

            // Editing should not change the length much. A wildly different length means the
            // model cut the text short or rambled, so keep the previous text for this step.
            current = if (request.style.category == RewriteCategory.Editor && !isPlausibleEdit(current, output)) {
                current
            } else {
                output
            }
        }

        return current
    }

    private fun isPlausibleEdit(before: String, after: String): Boolean {
        val ratio = after.length.toDouble() / before.length.coerceAtLeast(1)
        return ratio in 0.6..1.6
    }

    private fun ensureBridge(modelPath: String): LiteRtBridge {
        bridge?.let { if (loadedPath == modelPath) return it }
        releaseBridge()

        try {
            val created = LiteRtBridge(modelPath, appContext.cacheDir.path)
            bridge = created
            loadedPath = modelPath
            return created
        } catch (e: UnsatisfiedLinkError) {
            throw RewriteException("This device is not supported by the on-device model runtime.", e)
        } catch (e: Exception) {
            throw RewriteException("Could not load the model: ${e.message}", e)
        }
    }

    private fun releaseBridge() {
        val old = bridge ?: return
        bridge = null
        loadedPath = null
        runCatching { old.close() }.onFailure { Log.w(TAG, "Model close failed", it) }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            lock.withLock { releaseBridge() }
        }
    }

    /** Frees the model's memory now, e.g. when the system is low on memory. */
    fun release() {
        scope.launch { lock.withLock { releaseBridge() } }
    }

    private class Sampler(val topK: Int, val topP: Double, val temperature: Double)

    private fun samplerFor(style: RewriteStyle): Sampler {
        // Editing should stay close to the input; tone changes may be freer.
        val temperature = if (style.category == RewriteCategory.Editor) 0.2 else 0.7
        return Sampler(topK = 40, topP = 0.9, temperature = temperature)
    }
}
