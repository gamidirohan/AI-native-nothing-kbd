# AI Writing Assistant: progress tracker

Copilot-keyboard-style rewriting for this keyboard, running on a local on-device model.
Tick a box only after it has been checked. "Checked" means compiled, or run on a desktop JVM with the real
model, as noted. **Nothing has been run on an Android device yet**; every unchecked "device" item is for that.

Branch: `feature/ai-writing-assistant`

## Status in one paragraph

All six verticals are implemented, the app builds into a debug APK (arm64), and the rewrite engine was run
against the real model on a desktop JVM. The remaining work is testing on the Nothing phone, plus two known
quality limits: translation into Indian languages does not work with this model, and the "Elaborate" style was
dropped because it invents details.

## Decisions

- **Engine:** LiteRT-LM (Google's on-device runtime, `.litertlm` models), through the `litert-bridge` module. The llama.cpp options (a, b) were not needed.
- **Default model:** Qwen3 0.6B INT8 (`litert-community/Qwen3-0.6B`, 586 MB, Apache-2.0). Never bundled in the APK.
- **ML Kit GenAI (Gemini Nano via AICore):** not used. Supported devices only, beta, six fixed styles.
- **Tone list:** Professional, Casual, Polite, Funny, LinkedIn, Shorten, Translate (Witty and Social dropped; Elaborate tried and dropped).
- **ABIs:** the repo build config is unchanged (all ABIs). LiteRT-LM ships only arm64-v8a and x86_64, so 32-bit-only phones get a "not supported" message. Nothing Phone (1) and later are 64-bit.
- **GPU backend: not enabled.** It cannot be tested here, and a native GPU crash would take the whole keyboard down with it. The CPU backend is fast enough (see timings).

## How to build

Android Studio (Ladybug or newer, bundled JDK 21) can open the project as is. From a terminal with JDK 21 and the Android SDK:

```
./gradlew :assembleStableDebug -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
```

The first `-P` flag builds only arm64, which is much faster. It is an Android Studio property, and it also marks the APK test-only, which the phone's installer rejects with "App not installed as package appears to be invalid". The second flag turns that off; do not drop it. Output: `build/intermediates/apk/stable/debug/*.apk`.
Built here on 2026-11-19: native part about 1.5 minutes, APK 122 MB (debug, arm64, includes the keyboard's own bundled model).

## Vertical 1: Panel UI and text I/O

- [x] Toolbar action "AI Writing" registered (appended to `AllActionsMap`, added to default favorites)
- [x] Editor tab: Fix all, Grammar, Spelling, Punctuation
- [x] Tone tab: Professional, Casual, Polite, Funny, LinkedIn, Shorten, Translate
- [x] Read selected text, or the whole field when nothing is selected (`getTextTarget`)
- [x] Replace swaps the selection, or the whole field (`replaceTextTarget`), and refuses if the text changed
- [x] Copy button writes to the system clipboard (clipboard history picks it up)
- [x] Compiles and builds into the APK
- [ ] **Device:** selection replace, whole-field replace, Copy
- [ ] **Device:** behavior in apps that limit how much text the keyboard can read (long text, password fields)
- [ ] **Device:** does the panel fit the keyboard height? (rows: tabs and strength, style chips, info line, result card, buttons)
- [ ] Better toolbar icon (current one is a simple sparkle)

## Vertical 2: On-device engine

- [x] LiteRT-LM 0.19.1 through the Java-only `litert-bridge` module (the app's Kotlin 1.9 cannot read the library's Kotlin 2.x metadata)
- [x] `LiteRtRewriteEngine`: one owner thread, fresh conversation per request, cancel via interrupt, model unloads after 90 s idle
- [x] Streaming output into the result card; cancelled when a new style is tapped
- [x] Output cleanup (`<think>` blocks, "Here is..." lines, wrapping quotes), skipped when the user's own text starts that way. Checked on the JVM.
- [x] Qwen3 "thinking" switched off: a rewrite went from 20-35 s to 1-2 s on the desktop
- [x] "Fix all" is spelling, then grammar, then punctuation; prompts tuned against the real model
- [x] Guard: an Editor step whose output is much shorter or longer than its input is ignored
- [x] Model released when Android trims memory (`AiWritePersistentState.cleanUp`, called from `LatinIME.onTrimMemory`)
- [x] Refuses to load with under 1.6 GB free RAM, with a clear message, instead of risking the keyboard being killed
- [x] Missing native library (32-bit phone) gives a "not supported" message (catches `LinkageError`)
- [x] Real model run through `LiteRtBridge` on desktop Linux (x86_64 JVM) with the app's Kotlin library versions
- [ ] **Device:** first load time and rewrite speed on the Nothing phone (desktop: load 1.6 s, rewrite 1-2 s, "Fix all" 5 s)
- [ ] **Device:** memory. Desktop: 1.37 GB after loading, 2.04 GB after a few rewrites. The 1.6 GB free-RAM limit is based on that and may need adjusting.
- [ ] Prompt quality on the phone should match the desktop, but is not confirmed

### Test results (desktop CPU, Qwen3 0.6B INT8, thinking off)

Fix all (spelling, then grammar, then punctuation):

| Input | Result |
|---|---|
| im on my way ill be there in ten minutes dont start without me | Im on my way. Ill be there in ten minutes. Don't start without me. |
| me and him goes to the store yesterday and buyed two apple | Me and him went to the store yesterday and bought two apples. |
| hey can u send me teh report by tomorow morning pls | Hey can you send me the report by tomorrow morning, please. |
| Thanks for the update. I will review it today. | unchanged |

Weak spots: missing apostrophes in "Im" and "Ill", and it sometimes drops a word ("it were definately damaged" became "it definitely damaged"). Tone rewrites read well. The smaller 4-bit 0.6B model (328 MiB) cut text short and was rejected.

### Build notes

- Gradle wrapper raised 8.2 -> 8.5. The build needs **JDK 21**: LiteRT-LM ships Java 21 class files and JDK 17's `javac` cannot read them. CI images that use JDK 17 need updating.
- The root `build.gradle` puts a newer R8 (8.7.18) on the buildscript classpath: AGP 8.2's own R8 cannot convert LiteRT-LM's invokedynamic bytecode for minSdk 24.
- LiteRT-LM 0.18.0 or newer is required to load the current `Qwen3-0.6B.litertlm` (0.16.1 says "unknown file format").
- `litert-bridge/build.gradle` excludes the library's own Kotlin and coroutines libraries, so the app's Kotlin 1.9.23 stdlib and coroutines 1.7.1 are used at runtime. Checked on a real model with only the callback API that `LiteRtBridge` calls.
- `android.jetifier.ignorelist=litertlm-android` in `gradle.properties` stops Jetifier from choking on that library.
- LiteRT-LM picks the file format from the extension, so the model file must end in `.litertlm`.
- The manifest's WorkManager foreground-service description now also mentions the model download.

## Vertical 3: Model management

- [x] Model storage separate from the keyboard LM (`filesDir/llm-models/`)
- [x] In-app download from Hugging Face with progress, cancel and resume (HTTP Range); pinned size and SHA-256 (checked against the real file); free-space check
- [x] Download runs as a WorkManager foreground job with a notification and Cancel, so it survives closing the keyboard and a process kill (resumes from the partial file)
- [x] Wi-Fi only (default on): blocks metered connections with a "Download anyway" button; setting in AI Writing settings (new `ACCESS_NETWORK_STATE` permission)
- [x] Manual import of a `.litertlm` file; the header is checked, so a GGUF or other file is rejected
- [x] Settings page "AI Writing" (home screen): model status and size, download/cancel, import, delete with confirmation, Wi-Fi only, auto-translate
- [x] The panel's download card has an "Import file" button that opens that settings page, and the panel title bar has a settings icon
- [ ] **Device:** download from the app, including cancel and resume, and the notification
- [ ] **Device:** import a model file with the file picker
- [ ] **Device:** delete frees the space and the panel goes back to the download card

Not handled: an imported file is only header-checked, so a damaged file fails later with "Could not load the model". A different model than Qwen would use the Qwen-tuned prompts.

## Vertical 4: Formatting level (edit strength)

- [x] Tap the "Edits: Balanced" pill in the panel to cycle Light, Balanced, Strong; saved in settings
- [x] Maps to the prompt and the sampling temperature:

| Level | Editor styles | Tone styles |
|---|---|---|
| Light | temperature 0.1 | temperature 0.4, adds "Change as little as possible. Keep the original wording and sentence structure." |
| Balanced | temperature 0.2 | temperature 0.7, prompt unchanged |
| Strong | temperature 0.4, grammar step adds "You may also improve unclear wording." | temperature 0.9, adds "You may restructure sentences and change the wording freely." |

- [x] Prompt building checked on the JVM for every style and level
- [x] Tried on the real model. Tone: "hey can u send me the report by tomorrow morning pls" gave "send me the report by tomorrow morning" (Light), "Can you send the report by tomorrow morning please?" (Balanced), "Can you send the report by tomorrow morning?" (Strong). The differences are small and not always in the expected direction, because the model is small. Editor results were nearly the same at all levels.
- [ ] **Device:** judge whether the three levels feel different enough; the wording and temperatures are easy to change in `FormatLevel` (`RewriteTypes.kt`)

## Vertical 5: More tones and translate

- [x] **Shorten** style (tested: a 31-word message came back as 22 words with the meaning kept)
- [x] **Translate** style with a language picker (appears when Translate is selected). Uses its own system prompt, because the normal one says "keep the language of the original text".
- [x] Languages offered: English, Spanish, French, Portuguese, Japanese
- [ ] ~~Elaborate~~ **Dropped.** On the real model it either returned the text unchanged or invented details ("many participants attending"), which is wrong for a messaging keyboard.
- [ ] ML Kit GenAI backend: not done (see Decisions)

### Translation results (Qwen3 0.6B, temperature 0.1)

- English into Spanish, French, Portuguese, Japanese: readable but with mistakes (for example "la informe" instead of "el informe", and "Voulez-vous un bon jour?" for "Happy birthday!").
- English into German and Italian: frequent mistakes, so not offered.
- English into **Hindi, Telugu, Tamil, Bengali: unusable**. The output is invented words, often repeated until the length limit.
- Hindi, Telugu, Tamil into English: unusable. French, German, Spanish, Japanese into English: good.
- The larger Qwen3 1.7B (4-bit, 977 MB) was also tried. European languages got clearly better, but Hindi, Telugu and Tamil were still unusable. So a larger model of this kind does not fix Indian languages.

To translate into or out of Indian languages, a dedicated translation model is needed. The existing Translate toolbar button still works for that, but it sends text online through a third-party relay (`CloudTranslationHandler`). Google ML Kit's offline translation (about 30 MB per language, 59 languages) is the likely local alternative, with a privacy trade-off to decide. Not built.

## Vertical 6: Clipboard extras

Clipboard history already exists (`ClipboardHistoryAction.kt`).

- [x] "Clipboard" button in the AI Writing panel: use the current clipboard text instead of the text field. The Replace button becomes **Insert**, which puts the result in at the cursor. Sensitive clipboard items are skipped.
- [x] A sparkle button on each clipboard history item opens AI Writing with that item loaded
- [x] Opt-in setting "Auto-translate copied text" (off by default): when AI Writing opens with newly copied text, it translates that text into the chosen language straight away, ready to Insert. Each copied text is handled once.
- [ ] **Device:** all three of the above
- Not built, by design: translating in the background the moment text is copied. Android only lets the keyboard read the clipboard while it is the active keyboard, and reading every copied item (passwords, one-time codes) in the background would be a privacy risk. The auto-translate runs only when you open the panel.

## Testing and CI (checked 2026-11-20)

- **APK:** debug, arm64, app code from commit `ea52abfcf`. The first build was marked test-only and would not install; the rebuilt one has SHA-256 `2a89305013d5eb6969d33af5a32096535636dd3855d1362977c3bfc4c84e1d06`. Package `com.arunk140.inputmethod.latin`, minSdk 24. Signed with the repo's shared debug key (`java/shared.keystore`), so an install over a copy signed with another key fails: uninstall that one first.
- **Gradle tests:** none can run. There are no JVM unit tests (every `test*UnitTest` task reports NO-SOURCE). The upstream instrumented suite in `tests/` needs a device and does not compile: AGP 8 rejects the `package=` attribute in `tests/AndroidManifest.xml`, and after fixing that, 62 compile errors in 20 files remain (the tests reference code that no longer exists). Left as is.
- **Lint:** `:lintStableDebug` fails with 2100 errors, almost all in upstream resources (missing translations and base-folder dimens). The only errors in the AI Writing files are "string not translated" for the new `ai_write_*` strings, the same error every upstream string has. Two warnings: `ClipDescription.EXTRA_IS_SENSITIVE` needs API 33 (the same line already exists in the clipboard code), and a suggestion about `getUsableSpace`.
- **Emulator here:** not possible. The sandbox has no `/dev/kvm` and no CPU virtualization flags. `adb` works but there are no devices.
- **Cloud emulator test:** `.github/workflows/android-debug.yml` and `.github/ci/emulator-smoke.sh` build the APK (arm64 + x86_64), upload it as an artifact, then install it on an API 34 emulator, enable the keyboard, open the AI Writing settings page, open the panel, load the model, run Fix all, tap Replace, and save screenshots. **It has never run**: the first attempt failed before any step with "The job was not started because your account is locked due to a billing issue". Fix the billing lock at github.com/settings/billing, then run it from the Actions tab ("Run workflow"). The script is untested, so expect to adjust it after the first run.
- **Other services** (all need your own account, none were set up):
  - Firebase Test Lab: free tier is 10 virtual and 5 physical test runs per day. A Robo test only crawls the launcher screen, so it cannot drive the keyboard.
  - Appetize.io: runs APKs in a phone browser. Sources disagree on the free tier (30 or 100 minutes a month, short sessions), too little for a 600 MB model. It likely needs an x86_64 build.
  - BrowserStack App Live, AWS Device Farm and LambdaTest: real devices, free trials only.

## Round 2 changes (2026-11-20, after the first install)

- **Fix all only.** The separate Grammar, Spelling and Punctuation options are gone, and so are the Editor and Tone tabs. One row of options: Fix all, Professional, Casual, Polite, Funny, LinkedIn, Shorten, Translate.
- **Starts automatically.** Opening AI Writing runs Fix all on the selected text, or on the whole field, straight away. It also runs once a missing model finishes downloading. Switching to the clipboard, or tapping Refresh, re-runs the last option. Text handed over from the clipboard history, and auto-translate, still take priority.
- **"Edits" renamed "Strength"** (Light, Balanced, Strong). Tapping it switches level, shows a short explanation and re-runs the current option. The selected/whole-field/clipboard label moved into the result card, and the control row scrolls sideways.
- **Tone prompts: tried, not changed.** On the real 0.6B model:
  - Adding an example to each tone prompt made it worse. It copied the example ("Could you please review this at your earliest convenience?" for every input) or reversed the meaning ("Could you please be late?").
  - More specific wording without examples did no better.
  - The larger Qwen3 1.7B (4-bit, 977 MB) gave clearly better tones with the existing prompts ("I am unable to attend the party tonight", "You're a true hero! 🎉").
  - But the 1.7B was worse at Fix all: it left "me and him goes" and "two apple". It was also slower: 42 s against 30 s for six messages, plus 6.6 s to load against 1.7 s.
  - A single-step Fix all was faster (10 s against 30 s) but missed most capitals and punctuation, so Fix all stays three steps.
- **Spacebar.**
  - Sliding up or down now moves the cursor between lines (40 dp of finger travel per line). It only does this in multi-line text fields, because arrow keys in a single-line field can move focus elsewhere in the app.
  - The default spacebar mode is now "Swiping and long-pressing only moves cursor". Long-press gives a short vibration, then sliding moves the cursor. Long-press no longer opens the keyboard picker, unless that mode is picked again in Keyboard & Typing settings.
- **Vibration strength.**
  - New slider in Keyboard & Typing: Default, or 1-100%. It uses `VibrationEffect` amplitude, which needs Android 8+ and a motor that can vary its strength; otherwise the slider has no effect.
  - The existing slider is renamed "Vibration length".
  - With the strength set and the length at Default, each press is 10 ms.
- Not tested on a device: all of the above.

## Known limits

- Nothing has been run on an Android device. The APK builds, but whether it installs, starts and rewrites correctly is untested.
- The engine was exercised on a desktop JVM, not through the Android code paths (`Context`, `ActivityManager`, WorkManager).
- Translation quality: see above.
- Debug-signed APK; a release build was not made.
