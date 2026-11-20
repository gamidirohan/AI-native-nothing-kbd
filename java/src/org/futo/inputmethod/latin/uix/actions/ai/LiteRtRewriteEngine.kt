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
        // Emoji outside the BMP make the model hang, so they are taken out here and put back at
        // the end (see EmojiGuard).
        val stripped = EmojiGuard.strip(request.text)
        if (stripped.text.isBlank()) return request.text
        val keepPositions = request.style.category == RewriteCategory.Editor

        val bridge = ensureBridge(modelPath)
        val temperature = request.level.temperature(request.style.category)
        val system = RewritePrompts.system(request.style)
        val steps = request.style.instructions

        var current = stripped.text
        var lastPartialAt = 0L
        for ((index, step) in steps.withIndex()) {
            val isLast = index == steps.lastIndex
            val raw = StringBuilder()
            val instruction = RewritePrompts.instruction(request, step)
            // Timing for `adb logcat -s LiteRtRewriteEngine`. Each streamed chunk is about one token.
            val stepStart = System.currentTimeMillis()
            var firstChunkAt = 0L
            var chunks = 0

            try {
                bridge.generate(
                    system,
                    RewritePrompts.user(request.style, instruction, current),
                    TOP_K, TOP_P, temperature,
                    maxOutputChars(current), TIMEOUT_MILLIS
                ) { chunk ->
                    // Only the last step is shown while it streams; earlier steps are intermediate.
                    // The preview is updated at most every PARTIAL_UPDATE_MILLIS rather than on
                    // every token, to keep the UI work small.
                    raw.append(chunk)
                    chunks++
                    val now = System.currentTimeMillis()
                    if (firstChunkAt == 0L) firstChunkAt = now
                    if (isLast && now - lastPartialAt >= PARTIAL_UPDATE_MILLIS) {
                        lastPartialAt = now
                        onPartial(RewriteOutput.clean(raw.toString(), stripped.text))
                    }
                }
            } catch (e: InterruptedException) {
                dropIfStuck(bridge)
                throw CancellationException("Rewrite cancelled").also { it.initCause(e) }
            } catch (e: LiteRtBridge.OutputTooLongException) {
                throw RewriteException("The AI got stuck repeating itself. Try again.", e)
            } catch (e: LiteRtBridge.TimedOutException) {
                dropIfStuck(bridge)
                throw RewriteException("The AI took too long. Try again with shorter text.", e)
            } catch (e: Exception) {
                dropIfStuck(bridge)
                throw RewriteException("The model failed: ${e.message}", e)
            }

            logStepTiming(request.style, index, stepStart, firstChunkAt, chunks, current.length)

            val output = RewriteOutput.clean(raw.toString(), stripped.text)
            if (output.isBlank()) throw RewriteException("The model returned nothing. Try again.")

            // Editing should not change the length much. A wildly different length means the
            // model cut the text short or rambled, so keep the previous text for this step.
            current = if (request.style.category == RewriteCategory.Editor && !isPlausibleEdit(current, output)) {
                current
            } else {
                output
            }
        }

        // A reply is new text: the emoji came from the other person's message, so they are not
        // copied into it.
        if (request.style.usesClipboard) return current
        return EmojiGuard.restore(current, stripped, keepPositions)
    }

    /** Room for a rewrite that is a few times longer than the input, never more than 4000. */
    private fun maxOutputChars(input: String): Int = (input.length * 4 + 400).coerceAtMost(4000)

    /**
     * A generation that could not be stopped leaves the native side busy. That instance is
     * abandoned (not closed, which could crash) and a fresh one is loaded next time.
     */
    private fun dropIfStuck(current: LiteRtBridge) {
        if (current.isStuck && bridge === current) {
            Log.w(TAG, "Model did not stop; loading a fresh one next time")
            bridge = null
            loadedPath = null
        }
    }

    private fun logStepTiming(style: RewriteStyle, step: Int, start: Long, firstChunkAt: Long, chunks: Int, inputChars: Int) {
        val end = System.currentTimeMillis()
        val firstTokenMs = if (firstChunkAt > 0) firstChunkAt - start else -1
        val decodeMs = if (firstChunkAt > 0) end - firstChunkAt else 0
        val tokensPerSecond = if (decodeMs > 0 && chunks > 1) (chunks - 1) * 1000.0 / decodeMs else 0.0
        Log.i(
            TAG, "timing style=${style.name} step=$step inputChars=$inputChars " +
                    "firstTokenMs=$firstTokenMs totalMs=${end - start} tokens=$chunks " +
                    "tokensPerSec=${"%.1f".format(tokensPerSecond)}"
        )
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
            val loadStart = System.currentTimeMillis()
            val created = LiteRtBridge(modelPath, appContext.cacheDir.path)
            Log.i(TAG, "timing model loaded in ${System.currentTimeMillis() - loadStart} ms")
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
        // Each step normally takes 1-2 s on a desktop CPU; phones are slower.
        const val TIMEOUT_MILLIS = 60_000L
        const val PARTIAL_UPDATE_MILLIS = 100L
    }
}
