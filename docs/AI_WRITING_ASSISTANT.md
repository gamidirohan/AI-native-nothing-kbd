# AI Writing Assistant: progress tracker

Copilot-keyboard-style rewriting for this keyboard, running on a local on-device model.
Tick a box only after it has been checked (compiled, or tested on a device where noted).

Branch: `feature/ai-writing-assistant`

## Decisions

- **Engine order:** (c) LiteRT-LM (Google's on-device runtime, `.litertlm` models) first, then (a) upgrade the vendored llama.cpp, then (b) a second llama.cpp build as its own `.so`. (c) works via the `litert-bridge` module, so (a) and (b) are not needed unless it fails on a device.
- **ML Kit GenAI (Gemini Nano via AICore):** optional extra later. Supported devices only, beta, six fixed styles.
- **Default model:** Qwen3 0.6B INT8 (`litert-community/Qwen3-0.6B`, 586 MB, Apache-2.0). Optional bigger download later: Gemma 3 1B or Qwen3 1.7B.
- **Model is never bundled in the APK.** Downloaded or imported on first use.
- Tone list: Professional, Casual, Polite, Funny, LinkedIn, Translate (Witty and Social dropped).

## Vertical 1: Panel UI and text I/O

- [x] Toolbar action "AI Writing" registered (appended to `AllActionsMap`, added to default favorites)
- [x] Editor tab: Fix all, Grammar, Spelling, Punctuation
- [x] Tone tab: Professional, Casual, Polite, Funny, LinkedIn
- [x] Read selected text, or the whole field when nothing is selected (`getTextTarget`)
- [x] Replace swaps the selection, or the whole field (`replaceTextTarget`), and refuses if the text changed
- [x] Copy button writes to the system clipboard (clipboard history picks it up)
- [x] Placeholder engine (echoes text with the style name) so the flow can be tried
- [x] `:compileStableDebugKotlin` passes
- [ ] Try on a device or emulator: selection replace, whole-field replace, Copy
- [ ] Check behavior in apps where `getTextBeforeCursor` is limited (long text, password fields)
- [ ] Better toolbar icon (current one is a simple sparkle)

## Vertical 2: Real on-device engine

- [x] LiteRT-LM 0.19.1 chosen (option c). It works with this project only through a workaround, see "Build notes" below
- [x] `litert-bridge` module: Java-only wrapper, because LiteRT-LM is built with Kotlin 2.x and this project uses Kotlin 1.9.20
- [x] `LiteRtRewriteEngine` (single owner thread, fresh conversation per request, cancel via interrupt, idle release after 90 s)
- [x] Streaming partial output into the result card
- [x] Output cleanup (`<think>` blocks, "Here is..." lines, wrapping quotes), skipped when the user's own text starts that way
- [x] Qwen3 "thinking" switched off: a rewrite went from 20-35 s to 1-2 s on the test machine
- [x] Prompts tuned against the real Qwen3 0.6B model (see "Test results"); "Fix all" is spelling, then grammar, then punctuation
- [x] Guard: an Editor step whose output is much shorter or longer than its input is ignored
- [x] Compiles, and LiteRT-LM classes are present in the dex output
- [x] Real model run through `LiteRtBridge` on desktop Linux (x86_64 JVM) with the app's Kotlin library versions
- [ ] Run on a real Android device (nothing has been run on Android yet; timings there will differ)
- [ ] Release the model on low memory (`RewriteEngineProvider.release()` exists, not wired to a trigger)
- [ ] Decide whether to restrict ABIs to `arm64-v8a` (LiteRT-LM only ships arm64-v8a and x86_64; 32-bit-only phones get a "not supported" message)
- [ ] Try the GPU backend
- [ ] Check memory use on a phone (the model file is 586 MB; the README lists about 2.7 GB peak on CPU for a Samsung S25)

### Test results (desktop CPU, Qwen3 0.6B INT8, thinking off)

| Input | Result |
|---|---|
| im on my way ill be there in ten minutes dont start without me | Im on my way. Ill be there in ten minutes. Don't start without me. |
| me and him goes to the store yesterday and buyed two apple | Me and him went to the store yesterday and bought two apples. |
| hey can u send me teh report by tomorow morning pls | Hey can you send me the report by tomorrow morning, please. |
| Thanks for the update. I will review it today. | unchanged |

A full "Fix all" took about 5 s here. Known weak spots: missing apostrophes ("Im", "Ill"), occasionally drops a word ("it were definately damaged" became "it definitely damaged"). Tone rewrites read well. The smaller 4-bit model (328 MiB) cut text short and was rejected.

### Build notes

- Gradle wrapper raised 8.2 -> 8.5 and the build now needs **JDK 21**: LiteRT-LM ships Java 21 class files and JDK 17's `javac` cannot read them. CI images that use JDK 17 need updating.
- The root `build.gradle` puts a newer R8 (8.7.18) on the buildscript classpath: AGP 8.2's own R8 cannot convert LiteRT-LM's invokedynamic bytecode for minSdk 24.
- LiteRT-LM 0.18.0 or newer is required to load the current `Qwen3-0.6B.litertlm` file (0.16.1 says "unknown file format").
- `litert-bridge/build.gradle` excludes the library's own Kotlin and coroutines libraries so the Kotlin 1.9 compiler never sees Kotlin 2.x metadata. At runtime the app's Kotlin 1.9.23 stdlib and coroutines 1.7.1 are used. This was checked on a real model, using only the callback API that `LiteRtBridge` calls.
- `android.jetifier.ignorelist=litertlm-android` in `gradle.properties` stops Jetifier from choking on that library.
- LiteRT-LM picks the file format from the extension, so the model file must end in `.litertlm`.

## Vertical 3: Model management

- [x] Model storage separate from the keyboard LM (`filesDir/llm-models/`)
- [x] In-app download from Hugging Face with progress, cancel and resume (HTTP Range)
- [x] Pinned size and SHA-256 (checked against the real file); the file only gets its final name after the hash matches
- [x] Free-space check before downloading
- [x] First-use state in the panel: a download card instead of an error
- [ ] Try the download on a device (the Range resume was checked with curl against the real server, not from the app)
- [ ] Manual import of a `.litertlm` file as a fallback
- [ ] Settings page: delete model, show size
- [ ] Wi-Fi-only option (needs the `ACCESS_NETWORK_STATE` permission to detect mobile data)
- [ ] Download currently stops if the keyboard process is killed; it resumes from the partial file on Retry. A WorkManager job would survive that.

## Vertical 4: Formatting level

- [ ] Strength setting from "minimal edits" to "rewrite freely"
- [ ] Map the setting to prompt wording and sampling temperature
- [ ] Persist the choice

## Vertical 5: More tones and translate

- [ ] Translate chip with a target language picker (reuse the existing language list)
- [ ] Optional extra styles: Shorten, Friendly, Elaborate
- [ ] Optional ML Kit GenAI backend on supported devices

## Vertical 6: Clipboard extras

Clipboard history already exists (`ClipboardHistoryAction.kt`).

- [ ] Rewrite or translate a clipboard item from the history view
- [ ] Auto-translate newly copied text into the text box (opt-in)

## Known limits

- Only Kotlin compile and dexing have been run here; the native (ndk-build) part and a full APK build have not.
- Nothing has been run on a device yet.
