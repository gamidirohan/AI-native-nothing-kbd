# AI Writing Assistant: progress tracker

Copilot-keyboard-style rewriting for this keyboard, running on a local on-device model.
Tick a box only after it has been checked (compiled, or tested on a device where noted).

Branch: `feature/ai-writing-assistant`

## Decisions

- **Engine order:** (c) LiteRT-LM (Google's on-device runtime, `.litertlm` models) first, then (a) upgrade the vendored llama.cpp, then (b) a second llama.cpp build as its own `.so`.
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

- [ ] Check LiteRT-LM compatibility with Kotlin 1.9.20 / Compose compiler 1.5.5 (its newest release uses Kotlin 2.x metadata)
- [ ] If it fails: try an older LiteRT-LM release, then fall back to llama.cpp (a), then (b)
- [ ] Restrict ABIs to `arm64-v8a` (build currently compiles all ABIs)
- [ ] `LiteRtRewriteEngine` implementing `RewriteEngine`, inference on its own single thread
- [ ] Streaming partial output into the result card
- [ ] Cancel on style change or window close
- [ ] Release the engine when idle or on low memory
- [ ] Prompt tuning per style with the real model (small models over-edit; keep edits minimal)
- [ ] Output cleanup (strip quotes, "Here is..." preambles)

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

- The Gradle build here only compiles Kotlin; the native (ndk-build) part and an APK build have not been run.
- Nothing has been run on a device yet.
