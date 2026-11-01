# Packaging and distribution

App: WeazlCloud, package `io.prendie.weazlcloud`, version 0.1.0 (code 1), Android 14+.

## GitHub preview

Source and contributor documentation are public under the MIT license. Download
preview APKs from [GitHub releases](https://github.com/bprendie/weazlcloud_android/releases).
They contain no account credentials, personal backup data or hosted storage.
Demo is synthetic; real use needs an existing compatible server account.

```sh
./scripts/package-preview.sh
```

This assembles a development-signed debug APK and copies it to ignored
`output/releases/WeazlCloud-0.1.0-preview.apk`. It does not need a Play key or
local signing configuration and does not install onto any device. Debug builds
are debuggable; use previews for evaluation/development with that in mind.

## Optional maintainer Play upload

Contributors do not need Play credentials or private keys. Release assembly
without a local signing configuration produces an unsigned APK:

```sh
./gradlew :app:assembleRelease
```

For an authorized maintainer upload, copy `keystore.properties.example` to the
ignored `keystore.properties`, configure a PKCS12 upload key outside the repository,
then use `scripts/build-release.sh` to produce and verify a signed AAB. Never
commit the filled configuration or keys; keep secure backups separately.

Google Play's app-signing certificate differs from development certificates.
Changing certificates can require uninstalling, removing app-local account data,
staged uploads, durable receipts and offline copies. Do not replace an active
phone installation without preserving that state. This repository does not
establish public production availability or physical-device acceptance.

## Privacy and store review

The public policy at https://weazl.studio/privacy.html covers WeazlCloud and
Subweazl in separate sections; its bundled offline copy is
`app/src/main/assets/privacy-policy.html`. Review app-content declarations against
the implementation and server's actual data practices before any Play submission.
Account/device data and selected uploads go to the configured server. Self-hosting
does not by itself mean no data leaves the device. Optional background media
access and data-sync foreground work need accurate explanations where requested.

Public production eligibility and testing requirements are controlled by Google
Play. A privacy policy does not waive new personal-account closed-testing rules.
Keep reviewer/test data synthetic and scoped to an explicit destination; enter
real credentials only through the native app. Do not publish review credentials.

See [PARITY.md](PARITY.md) for validation evidence and outstanding device gates.
