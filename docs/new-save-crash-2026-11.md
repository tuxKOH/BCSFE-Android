# New-save load failure investigation (2026-11-12)

The JP 16.0.1 and TW 15.7.0 diagnostic bundles both contain saves generated from
the bundled 15.5 templates. Their opaque trailers end at marker `150300`, even
though the save headers claim a newer revision. The old factory expanded cats
and rank gifts and changed the version, but never completed native records at
the end. The Python upstream reader preserves these unknown bytes; agreement
with that reader did not establish that the game could load them.

In the emulator the incomplete TW save grew to roughly 4.9 GB RSS before Android
terminated it for `LOW_MEMORY`; the incomplete JP save was also killed after
growing to roughly 1.8 GB on the smaller emulator configuration. A legacy
15.5-header control reached the resource download normally, letting the game
migrate and serialize the later records itself. The complete record shapes
differ between 15.7 and 16.0, including compact chapter counts and later flags.
See the non-identifying fixtures in `app/src/test/resources/native_tails`.

`ModernSaveTail` generates empty native records by game-version gates. New-save
creation includes them. A targeted detector recognizes the exact old trailer
shape using its final marker chain; opening such a save remains byte-preserving.
An explicit repair appends the missing defaults and updates the checksum while
retaining all existing edits. The UI offers repair on open and before upload,
root loading or export, recording it in session history. Other unknown trailers
are left untouched.

`tools/battlecats.py` drives the actual native game through adb, Android control
trees and local OCR, and exposes process exit reasons as well as crash logs.
`tools/prepare_battlecats_qa.py` builds separate debug packages using BCData's
tbctk dependency, retaining the original APK splits and native game code.
Diagnostic bundles, player saves, credentials and third-party APKs remain
outside the repository.

Verification of 1.4.3 includes:

- `./gradlew test assembleDebug`: 203 tests per variant, no failures/errors;
  106 pass and 97 historical cases skip for unavailable external fixtures.
- 20,398 upstream/data comparisons and 88 item-record comparisons pass. The
  new-save comparison now includes independently captured native trailer
  fixtures instead of expecting upstream to recreate the original defect.
- Explicit repair of both diagnostic saves preserves every pre-existing byte
  before the checksum. Python upstream round trips the repaired files exactly.
- English TW import: closing the warning leaves the original unchanged; export
  asks again, repair opens the system save picker, and the exported bytes match
  the independent repair. Simplified Chinese JP import/repair/export matches
  too, and reimport no longer warns. The final APK's repair history label was
  checked on-device.
- Live replacement-account creation, upload and API receipt pass for both
  regions, retaining complete trailers. For game validation, direct injection
  of the API-received file reaches title but can trigger a transferred-account
  check. A separate upload is therefore received through the game's own
  transfer form, rather than treating injection or process liveness as a
  successful end-to-end transfer.
- TW 15.7.0's native form reports transfer complete; the received save reaches
  the base and displays the native daily login/reward flows without the former
  allocation failure. The game writes the loaded save back normally. Further
  menu checks in the separate QA copy were blocked by a Google Play billing
  availability dialog, so upgrade/equipment navigation is not claimed as
  verified.
- JP 16.0.1's native form also reports transfer complete. Starting the received
  save reaches the base with the native battle/upgrade buttons and welcome
  message, and the game writes the loaded save normally. The same QA billing
  dialog limits further navigation. The CLI uses separately signed packages;
  these checks validate save reception/loading, not billing or purchases.

The final release is 1.4.4 (version code 46); 1.4.3 was an internal build and
the sponsor-data publishing commit. The release build passes 207 tests per
variant: 110 pass and the same 97 historical cases skip. Added cases verify
selected-cat edits against individual edits, atomic failures, fresh sponsor
requests and malformed/oversized HTTP responses. Both locales fetch and refresh
all 11 remote sponsor names. Emulator batch checks cover selections retained
across ID searches, restored checked rows, expandable titles and form edits.
Exported saves match the intended individual edits byte for byte.

Advertising was removed before publication: upload/root loading use native
confirmation dialogs, without advertising scripts, banners or a minimum
advertising display delay. The sponsor notice and remote directory remain.
The ad-free APK was checked for former advertising references. Emulator checks
verified native upload/root confirmations and the default replacement-account
checkbox; an actual upload completed and displayed transfer codes successfully.
