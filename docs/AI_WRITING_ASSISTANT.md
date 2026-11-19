# AI Writing Assistant: progress tracker

Copilot-keyboard-style rewriting for this keyboard, running on a local on-device model.
Tick a box only after it has been checked (compiled, or tested on a device where noted).

Branch: `feature/ai-writing-assistant`

## Decisions

- **Engine order:** (c) LiteRT-LM (Google's on-device runtime, `.litertlm` models) first, then (a) upgrade the vendored llama.cpp, then (b) a second llama.cpp build as its own `.so`. (c) works via the `litert-bridge` module, so (a) and (b) are not needed unless it fails on a device.
- **ML Kit GenAI (Gemini Nano via AICore):** optional extra later. Supported devices only, beta, six fixed styles.
- **Default model candidate:** Qwen3 0.6B (`litert-community/Qwen3-0.6B`, about 0.6 GB). Optional bigger download: Gemma 3 1B or Qwen3 1.7B.
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

- [x] LiteRT-LM chosen (option c). It works with this project only through a workaround, see "Build notes" below
- [x] `litert-bridge` module: Java-only wrapper, because LiteRT-LM is built with Kotlin 2.x and this project uses Kotlin 1.9.20
- [x] `LiteRtRewriteEngine` (single owner thread, fresh conversation per request, cancel via interrupt, idle release after 90 s)
- [x] Streaming partial output into the result card
- [x] Output cleanup (`<think>` blocks, "Here is..." lines, wrapping quotes), skipped when the user's own text starts that way
- [x] Compiles, and LiteRT-LM classes are present in the dex output
- [ ] Run on a device with a real model (nothing has been run yet)
- [ ] Check the Java default arguments copied from LiteRT-LM 0.16.1 behave (CPU backend, no history)
- [ ] Turn off Qwen3 "thinking" to save time (currently only stripped from the output)
- [ ] Prompt tuning per style with the real model (small models over-edit)
- [ ] Release the model on low memory (`RewriteEngineProvider.release()` exists, not wired to a trigger)
- [ ] Decide whether to restrict ABIs to `arm64-v8a` (LiteRT-LM only ships arm64-v8a and x86_64; 32-bit devices get a "not supported" message)
- [ ] Try the GPU backend

### Build notes

- Gradle wrapper raised 8.2 -> 8.5 and the build now needs **JDK 21**: LiteRT-LM ships Java 21 class files and JDK 17's `javac` cannot read them. CI images that use JDK 17 need updating.
- LiteRT-LM 0.16.1 is the newest release whose dependencies the Kotlin 1.9 compiler can read. Releases 0.17+ pull in Kotlin 2.4 libraries. Moving to a newer release means upgrading the project's Kotlin first.
- `android.jetifier.ignorelist=litertlm-android` in `gradle.properties` stops Jetifier from choking on that library.
- Model file location for now: `<app files dir>/llm-models/Qwen3-0.6B.litertlm` (from `litert-community/Qwen3-0.6B` on Hugging Face).

## Vertical 3: Model management

- [ ] Model storage separate from the keyboard LM (`filesDir/llm-models/`)
- [ ] In-app download from Hugging Face with progress, resume and cancel
- [ ] Manual import of a `.litertlm` / `.gguf` file as a fallback
- [ ] Settings page: choose model, delete model, show size
- [ ] First-use state in the panel: "Download model" prompt instead of an error
- [ ] Wi-Fi-only download option

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
