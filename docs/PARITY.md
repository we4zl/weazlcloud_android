# Parity and validation record

Reference: `../weazlcoud_app/WeazlCloud/Live/` (iOS build 27), the core `MobileContract.swift`, `Theme.swift`, original fixture JSON, original icon and synthetic landscape assets. The native source is authoritative where historical planning documents differ.

## Public source validation — November 1, 2026

A clean export of the staged public source passed `scripts/verify.sh` (debug
assembly, 16 unit tests, lint) and `:app:assembleRelease`, without local SDK
configuration files or private signing configuration. The development preview
was built from that export. No application behavior was changed for publication;
no connected instrumentation, live account sign-in or physical-phone install
was performed in this pass. Historical emulator evidence and remaining physical
acceptance gates below still apply. Build output, private Play notes, local
configuration and signing keys are excluded from Git.

## Screen and service mapping

| iOS surface/service | Android implementation |
| --- | --- |
| LiveWorkspaceView, FilesPane, PhotosPane, DateRail | CloudUI.kt, CloudModel |
| LiveUtilityViews, LiveActions | UtilitiesUI.kt, Repository.kt |
| LivePhotoViewer / LiveMediaViews | Viewer.kt; original image zoom and paging, video/audio controls, lazy PDF pages and text |
| LiveTransport, account session / Keychain | Api.kt, Storage.kt; explicit authentication-boundary errors, credential routing, redirect rejection, AES-GCM key held by Android Keystore |
| LiveTransfers / upload journal | Transfers.kt; atomic private queue, immutable specifications, accepted-part reconciliation, batch finalization polling |
| PhotoKit / BGTask / background URLSession | MediaStore, WorkManager periodic checks, foreground data-sync service for active transfers |
| File-provider bookmarks | Android document-tree persistable URI grants |
| Photo organization | MediaStore bucket identities, additive memberships, nested relative-path collections, durable exact operation replay, source lookup/adoption and verified empty-catalog metadata recovery |
| LiveOfflineStore | Repository.kt; verified HEAD/ETag/Range originals, full membership/sync traversal, pins and reconciliation |
| iOS Share extension | Android SEND/SEND_MULTIPLE receiver and durable ShareInbox |
| Grabs and duplicates | Same scoped server routes; maintenance uses the account cookie |

## Platform differences

- Pixel backup uses photos/videos exposed by Android MediaStore. The default is all accessible local media. Selected-photo permission narrows the accessible set. Google Photos cloud-only items, Google cloud album memberships, and Locked Folder media are not exposed by this interface. They must be exported/downloaded or explicitly shared before this app can import them.
- Android exposes media directory buckets, not PhotoKit's collection graph. Their directory ancestry is preserved as server collection folders. Existing server albums, nested collections and multiple memberships remain browsable/editable. Empty local media albums and cloud/shared album rules cannot be inferred from MediaStore. Recurring document-tree backups do preserve empty folders.
- Motion-photo bytes are uploaded unchanged in the original container. Android does not expose Apple's separate PhotoKit Live Photo resource pair. Existing server motion components can be opened from the viewer's information controls.
- Android system pickers, share sheets, device authentication, status bar and keyboard are platform-native. In-app colors, content structure, icon, toolbar order, grid density and action vocabulary follow the iOS source. Final side-by-side visual acceptance is still pending.
- WorkManager schedules opportunities; an active foreground service handles user-requested transfers with a generic notification. Android may stop work due to quotas, force-stop or power restrictions. Physical Pixel battery/permission/reboot behavior remains unverified. See [Android long-running work guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

## Background photo backup

The scheduled worker restores the encrypted device session and refreshes/validates server capabilities before preparing media. It processes successive groups of uploads and rescans beyond the 32-photo preparation limit without an Activity. Background transfer windows display a generic data-sync notification, yield after eight minutes and retry with durable specifications, receipts and staged originals. Collection reconciliation remains separate from Stored original completion. Automatic folder preparation cannot expand device scopes; missing authorization requires opening the app.

The existing 15-minute WorkManager schedule persists across process death/reboot and respects network and charging preferences. Paused transfers and disabled backup sources are respected. Android controls execution timing; battery restrictions and Android 16 job quotas may defer work, and force-stop requires reopening the app. This is scheduled background backup, not a guarantee of an immediate upload for each new photo.

Synthetic emulator regression coverage uses a loopback mock server and an explicit synthetic destination: a fresh repository/credential restoration, discovery of 35 app-owned MediaStore photos across scan/upload batches, exact original bytes, Stored-only staging cleanup, separate collection reconciliation, interruption followed by receipt/spec-preserving restoration, paused/off behavior, and no automatic scope expansion. The foreground notification is captured by the worker test harness. These tests do not establish physical background scheduling, reboot, Doze, force-stop recovery or live-server acceptance; those remain in the gates below.

## Evidence and remaining gates

Verified on 2026-10-19:

- Android Debug APK assembly.
- Android lint: no errors. Remaining warnings are dependency-update suggestions, conservative storage checks, icon styling/location and Kotlin convenience APIs.
- 16 JVM contract tests: authentication-boundary errors, credential routing, redirect rejection, origin validation, query encoding, immutable source-key construction, cursor progression, Hidden context, collection adoption, content-lookup consistency, upload receipt states, ETag/range recovery and redacted server errors.
- Four emulator instrumented tests: separate browsing context and photo viewer; folder creation/search and utilities; encrypted credential persistence; a multipart upload spanning 16 MiB, exact reassembled SHA-256, transfer-engine restart, durable receipt recovery and cleanup only after Stored.
- Emulator uses the installed API 37 image with Pixel 9 display dimensions (1080 × 2424, 420 dpi). The app targets API 36 and supports API 34+.
- Public HTTPS discovery returned contract 1 and the expected capabilities route. This is not authenticated API acceptance.
- Screenshots are saved in `output/screenshots/`; these are Android emulator captures, not iOS evidence.

Still required before claiming identical live functionality:

1. Native account sign-in and separate-vault-passphrase flow against the actual server; confirm scoped grants and relaunch restoration.
2. Synthetic content in a dedicated destination: empty file, Unicode names, multi-part file, large video; stored original download and SHA-256 comparison; file and photo mutations, grab recovery/revoke, duplicate review, Hidden and paginated albums.
3. MediaStore permission variants, real device photo backup, source changes, source-organization conflict/recovery behavior, folder-provider grants and interruption handling against the deployed server. Collection adoption and empty-catalog metadata rebuild are ported with durable exact requests, strict identity/revision checks, and preservation of original upload records; deployed-server acceptance is pending.
4. Offline airplane-mode access and remote deletion/revision reconciliation on the live account; credential rotation with a lost reply; app-lock and share-target behavior across process death.
5. Physical Pixel 9 screen/gesture/accessibility review, charging and Wi-Fi/cellular transitions, lock, reboot, force-stop, and long-running large-library backup. These cannot be established by an emulator.

Demo is intentionally synthetic. It supports browsing, organization, mutations, previews, and example grab links; it does not simulate successful real backup, actual hosted links, or personal-media transfers.
