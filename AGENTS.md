# WeazlCloud Android

Port reference is `../weazlcoud_app` (spelling is intentional). Keep sibling app/backend source unchanged. Read README.md and docs/PARITY.md before changes. The goal is visual and functional parity with the live native iOS app, not just its browser prototype.

Keep Files and Photos context independent. Keep the original colors and icon. Do not claim live/physical acceptance from demo fixtures. Credentials are entered only in native UI. Keep all test writes synthetic and in an explicit destination. Personal backup is opt-in.

Preserve immutable upload specifications, durable receipts, source identities, originals until Stored, Hidden context, and scoped authentication. Do not reset an active queue or source namespace to conceal an error. Original storage and collection organization have separate completion states.

Run scripts/verify.sh. Run scripts/verify.sh --emulator for relevant behavioral changes before live sign-in; the connected-test installation lifecycle can remove app data. A physical Pixel 9 is now connected as well as the emulator. Always target adb commands explicitly. Never run connected instrumentation against the physical phone: its installation lifecycle can remove app data. Use the emulator for automated tests and retain outstanding physical acceptance gates.
