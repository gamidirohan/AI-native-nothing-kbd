# CI and distribution (needs the GitHub billing lock fixed)

## Blocked

- **GitHub Actions** does not start any job on this account: "The job was not started because your account is locked due to a billing issue." Fix at github.com/settings/billing.
- **GitHub Releases:** the sandbox cannot reach GitHub's file-upload server, so APKs cannot be attached to a release from here.
- **Committing the APK:** GitHub rejects files over 100 MB; the debug APK is about 128 MB (mostly the keyboard's own 64 MB next-word model). Git LFS is not available through the sandbox's GitHub connection.

## Ready once Actions works

`.github/workflows/android-debug.yml` (run from the Actions tab, "Run workflow"):

1. Builds the debug APK for arm64 and x86_64 and uploads it as a downloadable artifact (30 days).
2. Starts an API 34 emulator, installs the APK, enables the keyboard, opens the AI Writing settings and panel, installs the model, runs Fix all and Replace, and saves screenshots and logs (`.github/ci/emulator-smoke.sh`).

The emulator script has never run, so expect to fix it on the first run. To also build on every push, add the `push:` trigger noted at the top of the workflow.

The workflow could also run `./gradlew :testStableDebugUnitTest` (23 unit tests, currently passing) - add a step for it.

## How builds reach the phone today

The sandbox uploads each APK to Gofile (a free file host) and the previous one is deleted. Anyone with the link can download it; there are no secrets in the build (it is signed with the debug key already public in the repo).
