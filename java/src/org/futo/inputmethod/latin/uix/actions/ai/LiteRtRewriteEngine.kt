package org.futo.inputmethod.latin.uix.actions.ai

import android.app.ActivityManager
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
 * The model process measured 1.4 GB after loading and 2.0 GB after a few rewrites (desktop JVM;
 * see docs/AI_WRITING_ASSISTANT.md). Below this much free RAM, loading risks Android killing the
 * keyboard, so we refuse with a clear message instead.
 */
private const val MIN_FREE_RAM_BYTES = 1600L * 1024L * 1024L

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
        val temperature = request.level.temperature(request.style.category)
        val system = RewritePrompts.system(request.style)
        val steps = request.style.instructions

        var current = request.text
        for ((index, step) in steps.withIndex()) {
            val isLast = index == steps.lastIndex
            val raw = StringBuilder()
            val instruction = RewritePrompts.instruction(request, step)

            try {
                bridge.generate(
                    system,
                    RewritePrompts.user(instruction, current),
                    TOP_K, TOP_P, temperature
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

        val memory = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        if (memory.availMem < MIN_FREE_RAM_BYTES) {
            throw RewriteException("Not enough free memory to load the AI model. Close some apps and try again.")
        }

        try {
            val created = LiteRtBridge(modelPath, appContext.cacheDir.path)
            bridge = created
            loadedPath = modelPath
            return created
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError when the native library is missing for this CPU (32-bit
            // phones), NoClassDefFoundError on later attempts after that failure.
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

    private companion object {
        const val TOP_K = 40
        const val TOP_P = 0.9
    }
}
