package org.futo.inputmethod.latin.uix.actions.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.BuildConfig

private const val TAG = "AiBenchmark"

/**
 * Debug builds only: runs AI Writing rewrites from adb and logs the timing, so speed can be
 * measured without touching the screen. Protected by the DUMP permission, which `adb shell` has
 * and normal apps cannot get.
 *
 * adb shell am broadcast -n com.arunk140.inputmethod.latin/org.futo.inputmethod.latin.uix.actions.ai.AiBenchmarkReceiver \
 *     --ei threads 4 --es style FixAll --ei runs 1 --ei sample 0
 *
 * Results: adb logcat -s AiBenchmark LiteRtRewriteEngine
 */
class AiBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        val threads = intent.getIntExtra("threads", 0)
        val options = org.futo.inputmethod.litert.LiteRtBridge.Options().apply {
            cpuThreads = threads
            gpu = intent.getBooleanExtra("gpu", false)
            activation = intent.getStringExtra("activation")
            maxTokens = intent.getIntExtra("maxTokens", 0)
        }
        // Optional model file name inside files/llm-models (e.g. a different quantization).
        val modelFile = intent.getStringExtra("model")
        val runs = intent.getIntExtra("runs", 1).coerceIn(1, 3)
        val style = runCatching { RewriteStyle.valueOf(intent.getStringExtra("style") ?: "FixAll") }
            .getOrDefault(RewriteStyle.FixAll)
        val text = intent.getStringExtra("text") ?: SAMPLES[intent.getIntExtra("sample", 0).coerceIn(0, SAMPLES.lastIndex)]

        val appContext = context.applicationContext
        // While a broadcast is pending the process is not "cached", so Android does not freeze
        // it mid-benchmark (it otherwise freezes background apps about 10 s after they go idle).
        // Background broadcasts must finish within about 60 s, so send one run per broadcast.
        val pending = goAsync()
        scope.launch {
          try {
            val engine = RewriteEngineProvider.get(appContext) as? LiteRtRewriteEngine ?: return@launch
            // --ez defaults true: use the app's normal settings (GPU setting, CPU fallback).
            engine.optionsOverride = if (intent.getBooleanExtra("defaults", false)) null else options
            engine.modelPathOverride = modelFile?.let { java.io.File(LlmModelStore.directory(appContext), it).absolutePath }
            Log.i(TAG, "bench start $options model=${modelFile ?: "default"} style=${style.name} runs=$runs chars=${text.length}")
            for (run in 1..runs) {
                val start = System.currentTimeMillis()
                try {
                    val result = engine.rewrite(RewriteRequest(style, text)) {}
                    Log.i(TAG, "bench run=$run threads=$threads style=${style.name} ms=${System.currentTimeMillis() - start} result=\"$result\"")
                } catch (e: Exception) {
                    Log.w(TAG, "bench run=$run threads=$threads failed: ${e.message}")
                }
            }
            Log.i(TAG, "bench done threads=$threads style=${style.name}")
          } finally {
            pending.finish()
          }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val SAMPLES = listOf(
            "hey can u send me teh report by tomorow morning pls",
            "me and him goes to the store yesterday and buyed two apple",
            "i recieved teh package yesterday but it were definately damaged can you refund me, i ordered it two weeks ago and it was supposed to be a gift"
        )
    }
}
