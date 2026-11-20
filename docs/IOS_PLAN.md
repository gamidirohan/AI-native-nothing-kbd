# iOS version: research notes and plan

Starting point for a **separate iOS repo**. Nothing here has been built or run on iOS: the sandbox this was
written in has no Xcode or iPhone. Items marked **UNVERIFIED** need a test on a real device before relying on them.

Written 2026-11-19. Android work lives in this repo; see `AI_WRITING_ASSISTANT.md`.

## Goal

The same feature as the Android keyboard (Copilot-keyboard style):

- **Editor:** Fix all, Grammar, Spelling, Punctuation.
- **Tone:** Professional, Casual, Polite, Funny, LinkedIn (Translate later).
- Each result has **Replace** and **Copy**.
- If text is selected, only that is rewritten. If nothing is selected, the whole field is.
- Runs on the device, no server.

## Why a separate repo

- The Android code (Kotlin, Gradle, LiteRT-LM, Android keyboard service, Compose UI) does not run on iOS.
- Kotlin Multiplatform could share logic, but here that is only the tone list and prompts. Not worth the setup.
- The Android repo is a fork of FUTO Keyboard and carries its license (`LICENSE.md`). A separate Swift app keeps iOS code independent of it. (License terms not reviewed.)
- The iOS keyboard extension must be native Swift/UIKit. Flutter/Dart does not fit under the extension memory limit (see below), so it would not save work.

## What iOS does not allow

| Idea | Result |
|---|---|
| Floating, always-on-top icon over other apps (Wispr Flow style) | Not possible. Apple's answer on the developer forum: [no](https://developer.apple.com/forums/thread/78360). |
| App running in the background all the time | Not possible. iOS suspends background apps. A keyboard extension only runs while it is showing. |
| Adding a hidden feature to Apple's own keyboard | Not possible. A custom keyboard replaces Apple's, it does not extend it. |
| Share sheet as the main flow | Possible, but the user rejected it as poor UX, and it cannot Replace text in the other app. |

The iOS counterpart of the floating icon is the keyboard itself.

## AI engine: Apple Foundation Models

Use Apple's built-in on-device model through the Foundation Models framework (iOS 26+). This replaces Qwen on iOS.

- It is Apple's own model, about 3 billion parameters. You cannot load your own model file into it, so the 586 MB Android model is not used on iOS.
- Context window: **4,096 tokens** on iOS 26. One GitHub note says OS 27 raises it to 8,192 (single source, not checked). Short messages fit easily.
- Devices: **iPhone 15 Pro and newer** (all iPhone 16 and later). A plain iPhone 15 and older get no access even on iOS 26 ([source](https://dev.to/arshtechpro/how-to-fall-back-gracefully-when-apple-intelligence-isnt-available-48j)).
- Users can turn Apple Intelligence off, and it is not in every language or region. The app must handle "unavailable" and show a clear message.
- Apple's WWDC26 pages mention plugging other models into the framework through a `LanguageModel` protocol. Not read in detail. A model loaded by the app itself would likely count against the keyboard memory limit.
- No download step: the system manages the model.

### Keyboard extension memory

- Keyboard extensions have a tight memory limit. Sources disagree: roughly 30 to 77 MB.
- An Apple forum answer says the system language model is **not loaded into the extension's memory**, so it does not count against that limit ([thread 833575](https://developer.apple.com/forums/thread/833575)). Only the search snippet was seen; Apple's site blocked the full page. **UNVERIFIED.** This is the first thing to test.

### Fallback: Apple's Writing Tools

Apple Intelligence devices already have [Writing Tools](https://support.apple.com/en-sg/guide/iphone/iph6f08da1d2/ios) in third-party apps: Proofread, Rewrite, Friendly, Professional, Concise, Summarize, Key Points, List, Table.
They have no Polite, Funny, LinkedIn or Translate, and no keyboard button. Whether they appear while a custom keyboard is active was not checked.

## Recommended design

A custom keyboard extension with a button that opens the Editor/Tone panel and Replace/Copy buttons, calling Foundation Models.

Two scopes:

- **A. Full keyboard with the AI button.** Needs all typing keys, shift, emoji, globe key and so on. Large effort, competes with Apple's keyboard.
- **B. Rewrite-only keyboard (start here).** No typing keys. The user switches to it with the globe key, picks a style, taps Replace, and switches back. Small effort, proves the feature. Typing keys can be added later.

Reading and replacing text (from memory, **UNVERIFIED**):

- `UITextDocumentProxy.selectedText` gives the selection. It may not be filled in every app.
- `documentContextBeforeInput` / `documentContextAfterInput` give only the text near the cursor, so "replace all text" may cover just the current paragraph.
- Replacing means deleting with `deleteBackward()` and writing with `insertText(_:)`.
- Copy writes to `UIPasteboard`. Reading the clipboard may need "Allow Full Access".

## Carry over from Android

Tone and editor list, and the test inputs. The prompts below were tuned against Qwen3 0.6B. Apple's model is larger and behaves differently, so **re-test them**; the three-step "Fix all" may be unnecessary.

System prompt:

```
You are a writing assistant built into a phone keyboard. You receive a short message and one instruction. Reply with only the resulting text. Do not add explanations, quotes, notes or a preamble. Keep the language of the original text.
```

User prompt format: `<instruction>\n\nText:\n<text>`

| Style | Instruction(s) |
|---|---|
| Spelling | Fix the spelling mistakes in the text. Keep everything else the same. |
| Grammar | Fix the grammar mistakes in the text. Keep the meaning. |
| Punctuation | Add the missing punctuation, apostrophes and capital letters to the text. Keep the same words. (plus one example: `dont be late its important` -> `Don't be late. It's important.`) |
| Fix all | Spelling, then Grammar, then Punctuation, each on the previous result |
| Professional | Rewrite the text in a professional, businesslike tone. Keep the meaning. |
| Casual | Rewrite the text in a relaxed, casual, conversational tone. Keep the meaning. |
| Polite | Rewrite the text so it is polite, courteous and considerate. Keep the meaning. |
| Funny | Rewrite the text so it is light-hearted and funny, with a little humor. Keep the meaning. |
| LinkedIn | Rewrite the text as a LinkedIn-style message: professional, positive and engaging. Keep the meaning. |

Other lessons from the Android work:

- Sampling: low temperature (about 0.2) for Editor styles, higher (about 0.7) for Tone styles.
- Small models drop words or return text unchanged. The Android engine ignores an Editor step whose output is much shorter or longer than its input (ratio outside 0.6 to 1.6).
- Strip wrapping quotes and "Here is..." lines from the output, unless the user's own text starts that way.
- Test inputs used on Android:
  - `me and him goes to the store yesterday and buyed two apple`
  - `hey can u send me teh report by tomorow morning pls`
  - `im on my way ill be there in ten minutes dont start without me`
  - `Thanks for the update. I will review it today.` (should stay unchanged)

## What is needed to build it

- A Mac with Xcode (iOS 26 SDK or newer) and an Apple developer account (free is enough for testing on your own device).
- An iPhone 15 Pro or newer with Apple Intelligence turned on. The simulator may not run the model.
- Whoever writes the Swift code cannot compile it without that setup, so expect a compile-and-report-back loop.

## Milestones

Verify first, then build.

- [ ] Create the iOS repo (Xcode project: host app + keyboard extension)
- [ ] Test: can the keyboard extension call `SystemLanguageModel` at all? Does it need Full Access?
- [ ] Test: memory use of the extension while the model runs
- [ ] Test: how reliable `selectedText` and the context properties are in Messages, Notes, WhatsApp, Safari
- [ ] Panel UI: Editor and Tone tabs, style chips, result card, Replace, Copy
- [ ] Replace selection / replace whole field (as far as iOS allows)
- [ ] "Apple Intelligence unavailable" state with a clear message
- [ ] Re-tune the prompts for Apple's model
- [ ] Host app: setup instructions (add keyboard, allow Full Access if needed) and settings
- [ ] Decide on scope A (typing keys) after B works
- [ ] Later: Translate, formatting strength level, clipboard features (same ideas as Android)

## Sources

- Floating overlay: <https://developer.apple.com/forums/thread/78360>
- Writing Tools: <https://support.apple.com/en-sg/guide/iphone/iph6f08da1d2/ios>
- Foundation Models in a keyboard extension (snippet only): <https://developer.apple.com/forums/thread/833575>
- Foundation Models availability and device fallback: <https://dev.to/arshtechpro/how-to-fall-back-gracefully-when-apple-intelligence-isnt-available-48j>
- Foundation Models overview: <https://developer.apple.com/foundation-models-framework/>
- Keyboard extension memory limits: <https://dev.to/tbds_2dadf2b626f315902eae/the-three-hard-constraints-of-an-ios-keyboard-extension>
- Flutter under the keyboard limit: <https://github.com/zacksleo/flutter-ios-custom-keyboard-extension>
- Kotlin Multiplatform for iOS (stable May 2025): <https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/>
