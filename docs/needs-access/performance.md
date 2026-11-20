# Performance on the Nothing Phone (1) (needs the phone and `adb`)

Reported: about 3-4 words/sec on a Nothing Phone (1). One English word is roughly 1.3 tokens, so that is about **4-5 tokens/sec**. That is plausible for Qwen3 0.6B when the Snapdragon 778G+ CPU does all the work; GPU/NPU acceleration cannot be assumed.

**Do not swap in a larger model until this one is above about 8 tokens/sec.**

## The runtime is LiteRT-LM, not llama.cpp

The app runs `Qwen3-0.6B.litertlm` through Google's LiteRT-LM 0.19.1 (`litert-bridge/`, called from `LiteRtRewriteEngine.kt`). It is not a GGUF model and does not use llama.cpp, so llama.cpp flags such as `n_threads`, `n_batch` or `-DGGML_NATIVE` do not apply directly. The equivalent knobs, and what is set today:

| Suggestion | LiteRT-LM equivalent | Today | Plan |
|---|---|---|---|
| Measure tokens/sec | Each streamed chunk is about one token | **Done**: `adb logcat -s LiteRtRewriteEngine` prints `firstTokenMs`, `tokens`, `tokensPerSec` per step and the model load time | Read on the phone |
| Fewer CPU threads (try 2/4/6/8) | `Backend.CPU(threadCount, ...)` in `EngineConfig` | Not set; LiteRT-LM picks its own default (not checked which) | Make the thread count a setting, benchmark 2, 4, 6, 8 with the phone cool and unplugged, keep the fastest |
| ARM-optimized release native build | `liblitertlm_jni.so` is a prebuilt release library from Google's AAR, arm64-v8a | **Done**: APK contains `lib/arm64-v8a/liblitertlm_jni.so` (22.7 MB); the build is arm64 only | Also benchmark a release (non-debuggable) APK; the inference code is native, so the difference should be small, but measure it |
| Smaller context (512) | `EngineConfig.maxNumTokens` | Not set: the model's own limit (4096) | Set to 1024 and cap the text sent to the model (today up to 20,000 characters of the field can be read). 512 is too tight: the Fix all prompt alone is roughly 100-150 tokens |
| Short prompts, only the current sentence | Prompt building in `RewriteTypes.kt` | Prompts are one short instruction; but the **whole text field** is sent | Send at most the selection, or the last few sentences, for long fields |
| Keep the model loaded | Engine lifetime in `LiteRtRewriteEngine` | Loaded on first use, kept for 90 s after the last request, released when Android trims memory; one background thread; a new request cancels the old | Measure load time on the phone first. Loading at keyboard start would keep ~1.4 GB in the keyboard process all the time (measured on desktop), which risks Android killing the keyboard. Options: preload when a text field gets focus, or keep it loaded for 5 minutes |
| Compare quantizations | litert-community ships INT8 (`Qwen3-0.6B.litertlm`, 586 MB, current) and dynamic INT4 (`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`, 328 MB). There are no Q4_0/Q4_K_M files for LiteRT-LM | INT8 | Benchmark both on the phone. On desktop the INT4 file cut some text short, so check quality too |
| Thinking off | `ThinkingConfig(false)` | **Done** (rewrites went from 20-35 s to 1-2 s on desktop) | - |
| Low temperature | `SamplerConfig` | Fix all 0.2 (top_k 40, top_p 0.9); tones 0.7 | Try top_k 20 / top_p 0.8 for Fix all |
| Limit output length | Output cap in `LiteRtBridge.generate` | **Done**: stops past 4x the input + 400 characters, and after 60 s | Lower it for Reply (one or two sentences) |
| Stream into the UI, batched | `onPartial` | **Done**: streams, preview updated at most every 100 ms | - |
| One answer per request | - | **Done**: one result per request | - |

Fix all runs three passes (spelling, grammar, punctuation), so it costs three generations. A single pass was 3x faster on desktop but missed most capitals and punctuation. If the phone is too slow, a two-pass version is the next thing to try.

## Benchmark procedure (once adb is available)

1. Phone cool, unplugged, other apps closed, battery saver off.
2. `adb logcat -c`, then in any text field type: `hey can u send me teh report by tomorow morning pls`.
3. Open AI Writing (Fix all runs by itself). Then tap Professional.
4. `adb logcat -d -s LiteRtRewriteEngine | grep timing` and record `model loaded in`, `firstTokenMs`, `tokensPerSec` per step.
5. Repeat step 3 two more times (warm model), then once after 2 minutes idle (the model unloads after 90 s).
6. For each thread count and each model file, repeat 2-5.

Record results here:

| Build | Model | Threads | Load ms | First token ms | Tokens/sec | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |

## Realistic targets

For Qwen3 0.6B on this phone after tuning: about 7-12 tokens/sec is a reasonable target, 12-15 would be very good. Thermal state, RAM and the exact runtime matter, so nothing above 15 should be promised.
