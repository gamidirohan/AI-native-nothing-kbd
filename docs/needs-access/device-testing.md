# Device testing (needs the phone and `adb`)

Nothing in this branch has been run on Android. The debug APK builds, installs, and the logic was unit-tested on a desktop JVM, but every item below is unverified on a phone.

## What adb access would let us do

- Install builds directly instead of through a file host.
- Read crash stack traces (`adb logcat`) instead of guessing from a description.
- Measure speed and memory (see [performance.md](performance.md)).
- Drive the keyboard with scripted taps and take screenshots, so the panel layout can be checked.

## Setup once access is available

On the phone: Settings -> About phone -> tap Build number 7 times; then Developer options -> USB debugging (or Wireless debugging). Then from the sandbox, or any computer:

```
adb devices                       # should list the phone
adb install -r app.apk            # install over the existing build
adb logcat -c                     # clear the log before a test
```

Useful log filters for this project:

```
adb logcat -s LiteRtRewriteEngine LlmModelDownloader LearnedWords AndroidRuntime
adb logcat | grep -E "FATAL|ANR in|libc .*Fatal signal"
```

## Test checklist

Install and setup
- [ ] APK installs over the previous build
- [ ] Keyboard can be enabled and selected

AI Writing panel
- [ ] Sparkle button opens it; long-press on the emoji button opens it
- [ ] Fix all starts by itself on open, on the selection or whole field
- [ ] Replace changes only the selection when there is one, else the whole field
- [ ] Copy puts the result on the clipboard
- [ ] Refresh icon (title bar) re-reads the text and re-runs
- [ ] Chips and result text fit; nothing cut off at the default keyboard height
- [ ] Translate shows the language picker in the card's top line
- [ ] Text with emoji (😊 🎉 👍) does not freeze the panel; emoji come back in the result
- [ ] Turning the screen off / switching apps mid-rewrite does not crash

Model download
- [ ] Download card appears without a model; download runs with a notification
- [ ] Cancel, then Retry resumes (does not restart from 0)
- [ ] Wi-Fi only blocks mobile data and offers "Download anyway"
- [ ] Import a `.litertlm` file from settings; delete frees the space

Reply key (~)
- [ ] Hidden by default; spacebar is full width
- [ ] Turning on "AI reply key (~)" in AI Writing settings shows it without restarting the keyboard
- [ ] Copy a message, tap ~: a reply is drafted and Insert types it at the cursor
- [ ] With nothing copied, it asks to copy a message first

Learned words
- [ ] Type a Telugu/Hindi/Tamil word in English letters that gets autocorrected; backspace once to undo; type it again: it stays
- [ ] Second undo of the same word: it appears in suggestions and in the personal dictionary
- [ ] Learned Words page lists it; remove works; "Forget all" works
- [ ] Nothing is learned in a password field

Spacebar and vibration
- [ ] Long-press the spacebar: short vibration, then sliding left/right moves the cursor
- [ ] Sliding up/down moves between lines in a multi-line field (chat, notes); does nothing in a single-line field
- [ ] Vibration strength slider changes the feel (needs a motor with amplitude control)

Memory
- [ ] AI Writing does not get the keyboard killed when other apps are open; note free RAM when it refuses ("Not enough free memory")
