# Native runtime risks to confirm on the phone

Both were found on a desktop JVM with the real model and the same LiteRT-LM library. Whether Android behaves the same needs the phone.

## 1. Emoji in the input froze the model (fixed by working around it)

- **Found:** any input containing an emoji outside Unicode's Basic Multilingual Plane (😊 🎉 👍 and most emoji) made generation hang. Every test input with one was killed after 300 s, and one run crashed the process (exit 134, abort). Emoji inside the BMP (❤ ☺ ✅), accented letters and Telugu script were fine.
- **Likely cause:** Java hands strings to native code as "modified UTF-8", which writes these characters as two invalid halves; the tokenizer then loops.
- **Workaround in the app:** `EmojiGuard.kt` removes them before the text reaches the model and puts them back in the result (start/end stay put; middle ones go after the same word for Fix all, at the end for tones; not copied into a Reply). 11 unit tests. Placeholders were tried first, but the model dropped them most of the time.
- **Also added:** `LiteRtBridge` now stops a generation past an output limit or after 60 s, and if the model does not stop it is abandoned and a fresh one loaded. Tested on desktop: both limits stop cleanly and the model works afterwards.
- **To confirm on the phone:** a message with emoji does not freeze AI Writing, and the emoji come back.

## 2. Emoji in the model's *output* on Android

- On desktop the model sometimes writes emoji itself (Reply and Funny: "😊", "🎉") and they arrive fine.
- On Android, debug builds run with CheckJNI, which aborts the app if native code returns a string that is not valid modified UTF-8. If LiteRT-LM builds its Java strings that way, an emoji in the output could crash the keyboard in debug builds. Not seen yet; there is no way to check it here.
- **To confirm on the phone:** run Funny and Reply several times; if the keyboard crashes, `adb logcat | grep -i "JNI DETECTED ERROR"` shows it. A fix would be to ask the model not to use emoji, or a release (non-debuggable) build.

## 3. Memory

- Desktop process size: 1.37 GB after loading the model, 2.04 GB after a few rewrites. The app refuses to load with under 1.6 GB free.
- **To confirm on the phone:** the keyboard is not killed while AI Writing runs, and the 1.6 GB limit is not too strict (adjust `MIN_FREE_RAM_BYTES` in `LiteRtRewriteEngine.kt`).
