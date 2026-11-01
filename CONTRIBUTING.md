# Contributing

Fork the repository, create a branch, and open a pull request against `main`.
Open an issue first for larger behavior or protocol changes. Describe the
problem, resulting behavior and validation in each pull request.

## Setup

Install Android Studio, Android SDK Platform 36, Build Tools 36.0.0 and JDK 25.
The Gradle daemon is pinned to JDK 25; app bytecode targets Java 17. Android 14
(API 34) or newer is required to run the app.

```sh
git clone https://github.com/bprendie/weazlcloud_android.git
cd weazlcloud_android
./scripts/verify.sh
./gradlew :app:assembleRelease
```

Open the entire repository in Android Studio and run the `app` configuration.
Configure the SDK location there or set `ANDROID_HOME`. Use the included Gradle
wrapper. No iOS checkout, Play account, signing key or server is required to
build, run unit tests or try Demo. Real use requires your own compatible
[WeazlCloud server](https://github.com/bprendie/weazlcloud) and an existing account.
Enter credentials only in the native app, never in scripts or test sources.

For instrumented tests, start an emulator and run:

```sh
WEAZLCLOUD_TEST_EMULATOR=emulator-5554 ./scripts/verify.sh --emulator
```

The script explicitly targets the emulator and refuses physical-device serials.
Connected-test installation can remove app data: never run instrumentation on a
phone with a real account or active uploads. Test writes use only synthetic
content and explicit fixture destinations; tests do not need a production server.

## Architecture and invariants

- Keep Files and Photos search, selection and scroll context independent.
- Preserve immutable upload specifications, durable receipts, source identities,
  original bytes until Stored, and the separation between storage completion and
  collection organization.
- Preserve Hidden context and scoped authentication. Do not forward credentials
  to unverified origins or redirects, or reset queues/namespaces to hide failures.
- Personal backup remains opt-in; preserve device originals.
- Preserve the existing colors, icon, design and original artwork.
- Keep account data, tokens, private files/photos, device dumps, signing keys,
  local signing configuration and output artifacts out of commits and reports.
- Follow `AGENTS.md` and the platform differences in `docs/PARITY.md`. The sibling
  iOS source was the port reference; it is not needed to build or contribute here.
- Report emulator fixture tests separately from actual live-server, audible/media,
  background scheduling or physical-device observations.

GitHub Actions runs the verification script and release assembly on pushes and
pull requests. Relevant behavior changes also need emulator regression checks.
See [PARITY.md](docs/PARITY.md) for current coverage and remaining acceptance gates.

## License

Contributions are provided under the project's [MIT license](LICENSE).
