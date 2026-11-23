<div align="center">

# Zuno

### AI-native keyboard for Android: efficient and local

Fix your grammar, change your tone, translate and draft replies, right in the keyboard.
The AI runs on your phone, so what you type stays on your phone.

[Features](#features) · [Screenshots](#screenshots) · [Install](#install) · [Build](#build-from-source) · [How it works](#how-it-works) · [Credits](#credits) · [License](#license)

</div>

---

## Features

**AI Writing**: tap the sparkle button on the toolbar, or long-press the emoji button.

- **Fix Grammar, Spelling, and Punctuation.** Runs by itself as soon as the panel opens, on the selected text or the whole text field. One tap on **Replace** puts the corrected text back.
- **Tones:** Professional, Casual, Polite, Funny, LinkedIn, Shorten.
- **Translate** into English, Spanish, French, Portuguese or Japanese.
- **Reply** to a chat you copied, for example from WhatsApp. Zuno reads who said what, asks once which name is yours, and writes the reply as you. Type a rough idea in the text box first and Reply turns it into a proper message.
- **Copy** any result instead of replacing.

**On-device model**
- [Qwen3 0.6B](https://huggingface.co/litert-community/Qwen3-0.6B) running through Google's [LiteRT-LM](https://ai.google.dev/edge/litert-lm). It is downloaded once in the app (586 MB, checked against a pinned SHA-256), or imported from a file.
- Runs on the **GPU** (OpenCL) and falls back to the CPU. On a Nothing Phone (1), Fix all takes about 9 s on the GPU against about 21 s on the CPU ([benchmarks](docs/needs-access/performance.md)).
- **Kept loaded** in the background, so the panel starts without waiting (can be turned off to save about 1.5 GB of RAM).
- Nothing you type is sent anywhere for AI Writing.

**A keyboard that learns how you type**
- **Words you keep:** undo an autocorrection (backspace right after it) and Zuno stops correcting that word. The second time, it is added to your personal dictionary and suggested. This is useful for Telugu, Hindi or Tamil typed in English letters, names and slang.
- **Your typos:** delete a word and type a similar one instead, or tap a suggestion in place of what you typed. Zuno learns the pair ("kr" -> "it", "whys" -> "why's") and corrects it for you after the second time.
- Both are listed and editable in Settings -> Text Prediction -> Learned Words, and are never learned in password or incognito fields.

**Typing**
- **Spacebar trackpad:** long-press the spacebar, then slide left and right to move the cursor, or up and down to move between lines.
- **Vibration strength** and length settings.
- Swipe typing, voice input, clipboard history, themes, emoji and everything else from [FUTO Keyboard](https://keyboard.futo.org/).

## Screenshots

<p align="center">
  <img src="docs/images/ai-writing-fix.png" width="30%" alt="AI Writing fixing grammar, spelling and punctuation" />
  <img src="docs/images/ai-writing-tone.png" width="30%" alt="AI Writing rewriting in a professional tone" />
  <img src="docs/images/ai-writing-settings.png" width="30%" alt="AI Writing settings" />
</p>

<sub>Taken on a Nothing Phone (1), Android 15.</sub>

## Install

Zuno is in active development and is not on any app store yet.

1. Download the APK from [Releases](../../releases) when one is published, or [build it yourself](#build-from-source).
2. Install it, open **Zuno** and follow the setup to enable it as your keyboard.
3. Open AI Writing once to download the model (586 MB; Wi-Fi only by default).

**Requirements:** Android 7.0 or newer on a 64-bit ARM phone (arm64-v8a). Around 2 GB of free RAM for AI Writing. 32-bit-only phones can use the keyboard but not AI Writing.

## Build from source

You need JDK 21 and the Android SDK (Android Studio Ladybug or newer has both).

```bash
git clone --recursive https://github.com/gamidirohan/Zuno-AI-Native-Keyboard.git
cd Zuno-AI-Native-Keyboard
./gradlew :assembleStableDebug -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
```

The APK is written to `build/intermediates/apk/stable/debug/`. The first `-P` flag builds only arm64, which is much faster. It also marks the APK test-only, which phones refuse to install, so keep the second flag.

Unit tests:

```bash
./gradlew :testStableDebugUnitTest
```

## How it works

```
Keyboard (LatinIME / FUTO Keyboard)
 ├─ uix/actions/ai/        AI Writing panel, prompts, model download, chat parsing
 │    ├─ RewriteEngine     interface: text + style in, rewritten text out (streamed)
 │    ├─ LiteRtRewriteEngine   one owner thread, GPU with CPU fallback, cancel, timeouts
 │    ├─ ChatTranscript    reads copied WhatsApp chats for Reply
 │    └─ EmojiGuard        keeps emoji away from the model (they made it hang) and puts them back
 ├─ learning/              learned words and personal typos (pure Kotlin rules + storage)
 └─ litert-bridge/         small Java module around LiteRT-LM
```

- **Why a Java bridge:** LiteRT-LM is built with Kotlin 2.x, and this codebase uses Kotlin 1.9, which cannot read it. Keeping every LiteRT-LM call in a module without the Kotlin plugin avoids a large migration.
- **Small model, small steps:** a 0.6B model does best with one narrow instruction at a time. So Fix all runs three passes: spelling, then grammar, then punctuation. Prompts were tuned against the real model; the results are in [docs/AI_WRITING_ASSISTANT.md](docs/AI_WRITING_ASSISTANT.md).

### Using these AI features in another keyboard

The AI parts do not depend on the rest of the keyboard much. To add them to another Android keyboard:

- Copy `litert-bridge/` and the `RewriteEngine`, `LiteRtRewriteEngine`, `RewriteTypes`, `RewriteOutput`, `EmojiGuard`, `ChatTranscript`, `LlmModelStore` and `LlmModelDownloader` files.
- Give the engine the text the user selected (or the whole field) through your `InputConnection`, and show its streamed output.
- `learning/LearnedWordsCore.kt` is plain Kotlin with no Android code. Call it from your autocorrect decision and your backspace handling.

## Documentation

- [AI Writing design notes and test results](docs/AI_WRITING_ASSISTANT.md)
- [Performance on a Nothing Phone (1)](docs/needs-access/performance.md)
- [Device testing checklist](docs/needs-access/device-testing.md)
- [Known runtime risks](docs/needs-access/runtime-risks.md)
- [iOS research notes](docs/IOS_PLAN.md)

## Privacy

- AI Writing runs entirely on the phone. The only network use is the one-time model download from Hugging Face.
- The older **Translate** toolbar action (from the upstream fork) sends text to an online translation service. AI Writing's own Translate is on-device.
- A screenshot-based assistant from the upstream fork is still in the code. It is **off by default** and not reachable from the keyboard. If re-enabled, it would send screenshots to a remote server.

## Contributing

Issues and pull requests are welcome. Please run the unit tests before sending a change, and say which phone you tested on for anything touching AI Writing or performance.

## Credits

Zuno stands on a lot of other people's work:

- [**FUTO Keyboard**](https://keyboard.futo.org/) by FUTO: the keyboard this is built on, including swipe typing, voice input and the transformer next-word model.
- **LatinIME**, the [Android Open Source Project](https://android.googlesource.com/platform/packages/inputmethods/LatinIME) keyboard that FUTO Keyboard is based on.
- [**nth-kbd**](https://github.com/arunk140/nth-kbd) by arunk140: the fork this repository started from (Nothing-style theme, translation panel, extra emoji).
- [**Qwen3**](https://huggingface.co/Qwen/Qwen3-0.6B) by the Qwen team (Apache-2.0), in the [LiteRT-LM conversion](https://huggingface.co/litert-community/Qwen3-0.6B) by litert-community.
- [**LiteRT-LM**](https://github.com/google-ai-edge/LiteRT-LM) by Google (Apache-2.0).

## License

Zuno is licensed under the [FUTO Source First License 1.0](LICENSE.md), the same license as FUTO Keyboard.

This is a **source-available** license, not an OSI-approved open-source license. You may use, study, modify and share the code free of charge for non-commercial purposes, and you must keep FUTO's notices and payment features. Read [LICENSE.md](LICENSE.md) for the exact terms. Third-party components keep their own licenses (see [NOTICE](NOTICE)).
