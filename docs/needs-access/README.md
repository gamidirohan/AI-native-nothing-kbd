# Blocked work: needs access or resources we don't have yet

Things that cannot be finished from the cloud sandbox this project is built in. Each file says what is blocked, what is already done, and exactly what to run once access is available.

The sandbox has no phone, no Android emulator (no `/dev/kvm`), no Mac and no Xcode. Code can be built and unit-tested here; nothing can be run on Android.

| File | Blocked on | Unblocks |
|---|---|---|
| [device-testing.md](device-testing.md) | A Nothing Phone (1) with `adb` access | Checking everything actually works; reading logs and crashes |
| [performance.md](performance.md) | The phone, plus `adb` | Measuring and improving tokens/sec, load time and memory |
| [runtime-risks.md](runtime-risks.md) | The phone | Confirming two native-library risks found on desktop |
| [ci-and-distribution.md](ci-and-distribution.md) | GitHub billing lock on the account | Automatic builds, emulator tests, normal download links |

iOS is a separate project: see [../IOS_PLAN.md](../IOS_PLAN.md) (needs a Mac with Xcode and an iPhone 15 Pro or newer).

Last updated 2026-11-20, branch `feature/ai-writing-assistant`.
