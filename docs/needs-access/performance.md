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

## Results: Nothing Phone (1), 2026-11-21

Measured over adb with the debug benchmark hook (`AiBenchmarkReceiver`), screen on, charging, thermal status 0. Fix all on "hey can u send me teh report by tomorow morning pls" (3 generation steps). "Tokens/sec" counts streamed chunks, about one token each. Free memory was 1.1-3.0 GB and changed during the session, which adds noise.

| Backend | Model | Threads | Load (first) | First token | Tokens/sec | Fix all |
|---|---|---|---|---|---|---|
| CPU | INT8 | default | 1.0 s | 3.4 s | 3.1 | 20-23 s |
| CPU | INT8 | 2 | 0.9 s | 3.9-4.6 s | 3.2-3.3 | 22-23 s |
| CPU | INT8 | 4 | 0.9 s | 3.3 s | 3.1 | 20-21 s |
| CPU | INT8 | 6 | 0.9 s | 3.4 s | 2.9 | 21-22 s |
| CPU | INT8 | 8 | 0.9 s | 3.5-3.6 s | 2.8 | 23 s |
| CPU | INT8, fp32 activations | 4 | 0.9 s | 4.0-4.8 s | 2.4-2.6 | 25-30 s (lower-memory period) |
| CPU | INT4 | 4 | 3.8 s | 4.1-4.7 s | 2.5-2.9 | 19-22 s |
| **GPU (OpenCL)** | **INT8** | - | **11.6 s** | **1.3 s** (warm) | **6.3-6.4** (warm) | **9.4 s** (warm) |
| GPU | INT4 | - | not run: the app's memory check refused | | | |

Findings:
- **Thread count makes no difference** (2-8 threads all about 3 tokens/sec). The CPU was busy (about 4 cores for 4 threads), and the model was not being re-read from storage (no page faults during runs). So the CPU path is compute-limited in a way more cores do not fix. That is well below the 9-13 tokens/sec the model card lists for CPU on other phones (LiteRT-LM 0.13.1 there, 0.19.1 here).
- **First-token time is fixed overhead** (3.4-3.8 s on CPU, the same for a 51- and a 142-character input), mostly the instruction prompt, paid 3 times per Fix all.
- **GPU: about 2.2x faster overall and 3x faster to the first token.** The first load takes 11.6 s (OpenCL program build); a second load could not be timed because of the memory check below.
- **INT4** was a little faster on CPU and gave the same Fix all result, but some steps arrived in one piece instead of streaming.
- **fp32 activations** did not help.
- **Memory:** the keyboard process was about 290 MB without the model and 1.8-2.1 GB with it. The old 1.6 GB free-memory check refused to load even with 2.4 GB free by `dumpsys meminfo`, because Android's own number is lower.

Changes made from this:
- **GPU is now the default** (setting "Use the GPU" in AI Writing settings), with automatic fallback to the CPU if the GPU fails to load.
- **Free-memory check lowered to 800 MB**, and the message now shows the number.
- CPU threads stay at LiteRT-LM's default, since they made no difference.

Still to measure: GPU second load time (with the cache), GPU + INT4, GPU with a 1024-token context, and whether an emoji in GPU output causes problems.

## Realistic targets

For Qwen3 0.6B on this phone after tuning: about 7-12 tokens/sec is a reasonable target, 12-15 would be very good. Thermal state, RAM and the exact runtime matter, so nothing above 15 should be promised.
