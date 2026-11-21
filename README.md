# WeazlCloud for Android

Native Kotlin / Jetpack Compose client for [WeazlCloud](https://github.com/bprendie/weazlcloud), ported from the implemented native iOS app. Open **this entire directory** in Android Studio, let Gradle sync, select the `app` configuration, choose an emulator, and Run.

The build uses Android SDK 36, Android Gradle Plugin 9.1.0, Gradle 9.3.1, and JDK 25 for the Gradle daemon (Java 17 bytecode). Android 14 or newer is required. There is no Apple signing or developer account requirement.

## Run and verify

```sh
./scripts/verify.sh
```

With an emulator already running:

```sh
./scripts/run-emulator.sh
```

The script installs a debug APK and opens **Demo**, using the original app's synthetic fixtures and artwork. Normal Android Studio Run opens connection setup. Enter server credentials only in the app. The default server is `https://weazlcloud.prendie.io`. Failed authentication never enters Demo.

`./scripts/verify.sh --emulator` additionally runs instrumented UI, secure-storage, and multipart-transfer recovery tests. Instrumented tests use local fixtures and a local mock HTTP server; they do not upload personal content. Run these tests before signing into a real account, because Android's connected-test installation lifecycle can uninstall the app.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

[Download preview APKs](https://github.com/bprendie/weazlcloud_android/releases) or
build locally. GitHub previews use a development signing certificate and require
your own server/account for real use; Demo uses synthetic data. Google Play uses
a different app-signing certificate. Switching distributions can require
uninstalling and removes local credentials, staged transfers and offline data,
so preserve an active queue before changing installations. No public Play
production availability is claimed here.

For contribution setup and pull requests, see [CONTRIBUTING.md](CONTRIBUTING.md).
For maintainer packaging and optional Play uploads, see [PLAY_RELEASE.md](docs/PLAY_RELEASE.md).

## Implemented surfaces

- Separate Files and Photos modes, preserving each mode's search, selection and scrolling; the original charcoal/neon colors, icon and compact angular panels.
- Files list/grid, wildcard search, sort, folder creation, upload, rename, move/copy, Trash/restore and original previews.
- Photo timeline, Favorites, Recent, Archive, Hidden, Trash, search, month/date navigation, 2–6-column pinch density, nested albums and folders, memberships, full-screen swipe/zoom viewer, photo/video/audio/PDF/text previews and original sharing.
- Server connection, vault unlock, encrypted device credentials and account-session restoration, scope upgrades, credential rotation, verified home/remote switching and device screen lock.
- Durable verified parts uploads, eight bounded workers, batch status polling where advertised, pause/retry/cancel, expired-staging recovery, source content lookup and distinct sent/verified/Stored states.
- Opt-in MediaStore photo/video backup and recurring document-tree folder backup; Wi-Fi/cellular and charging policy; one-way preservation of originals; device album and directory organization.
- Offline originals for files/folders/albums, cached listings, synchronization/reconciliation and explicit local-copy removal.
- Expiring grabs with download limits, optional passphrase, Hidden confirmation, recovery keys, QR, copy/share and revocation; duplicate review with preferred-copy metadata.
- Android Share-target inbox that copies attachments before temporary source grants disappear and imports into the destination selected in Files.

## Acceptance status

Photo backup can run without the app open once you sign in, choose a destination, grant media access and enable **Photos backup**. WorkManager checks approximately every 15 minutes subject to Android scheduling, network and charging preferences, and restores scheduled work after process death or reboot. Active background uploads show a generic notification and use saved upload receipts to resume successive batches. Android battery restrictions, Doze and job/foreground-service quotas can delay uploads; a force-stop prevents background work until the app is opened again. **Back Up Now** starts a user-requested transfer and bypasses the charging preference. Backup stays opt-in; device originals are preserved.

Read [docs/PARITY.md](docs/PARITY.md) for the implementation mapping, platform differences, and outstanding acceptance gates. A compiling app and emulator tests do **not** establish identical behavior against the live account or physical Pixel. Live authenticated acceptance and physical-device checks remain explicit gates.

The sibling iOS app and backend are unchanged. Source artwork was copied from the iOS repository; no artwork was regenerated.

## License

[MIT](LICENSE), copyright 2026 Bob Prendergast.

## WEAZL and WE4ZL branding

Forking or reusing code does not grant permission to brand or market your own product with either the WEAZL™ or WE4ZL™ wordmark, the weazl head logo, or the tagline *Never Tax the Gig*™. See the [trademark and artwork notice](TRADEMARKS.md).
