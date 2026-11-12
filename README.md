# BCSFE Android

[简体中文](README.zh-CN.md) | English

Android save-file editor UI for *The Battle Cats*. The application is written
in Java and uses Android XML layouts and resources. English is stored in
`app/src/main/res/values/strings.xml`; Simplified Chinese is stored in
`app/src/main/res/values-zh-rCN/strings.xml`, so additional Android resource
qualifiers can be added later without changing application code.

## Status

The Android port provides the main BCSFE editing surface: basic currencies and
tickets, cats/forms/talents/storage, story and event maps, Enigma and Gauntlet
maps, Gamatoto/Ototo/Cat Shrine, account credentials, gacha seeds, repairs,
lineups, gambling events, guides, missions, medals, Gold Pass, and related
items. Import/export, transfer-code reception, save upload with new transfer
codes, replacement-account creation, and independent managed-item upload are
implemented.

Upload and root-load confirmations offer a replacement-account checkbox, enabled
by default. Clearing it keeps the existing account; an upload failure never
silently switches that choice back on.

The in-app seed finder reads current pools through the HTTPS Seed API and asks
for a one-time license key only when calculating a matching rare seed.

The format core validates and rewrites the region-specific MD5 checksum and
preserves unknown data. Current deep editors support 15.5.0 through JP 16.0.1 or
EN/TW/KR 15.7.0, including intermediate patches such as JP 15.7.1–15.7.4 and 16.0.0.
Intermediate patches use compatible regional BCData and keep their original save
version. New saves use their region's latest supported
version and initialize all current cat records. Region conversion is byte-tested
for 15.5. Version conversion supports 14.0 and 14.3 through the latest regional
version, including the version-dependent Dojo, Enigma, and
tail record layouts.

The offline regional BCData snapshots and repeatable sync/verification steps are
documented in [docs/game-data-sync.md](docs/game-data-sync.md).

The editor uses hierarchical navigation: select a category such as Basic
Information, then select a concrete editor such as Cat Food or XP. English and
Simplified Chinese can be switched explicitly from About. The active working
save is written atomically to private app storage after import, transfer
reception, and every edit; it survives upload, process termination, app exit,
and device reboot until the user confirms that the session should be discarded.
The session drawer can keep multiple independent working saves, switch between
them, rename them, or delete them. Files shared from another Android app can be
opened directly as a new validated session.

Current-version offsets may not match every personal use case, so this
repository does not provide offsets. Populate a private `.env` from the empty
IDs in `.env.example` before building. This avoids publishing rapidly obsolete
values that could mislead users.

## Build

Open the repository in Android Studio and run the `app` configuration, or use a
Gradle 8.x installation:

```sh
gradle assembleDebug
```

The project requires Android SDK 34 or newer and Java 17.

## Local automation API (development only)

The loopback API used by the differential test harness is retained in the
source but disabled in distributed builds. To run local API tests, temporarily
set `LOCAL_API_ENABLED` to `true` in `MainActivity`; it listens only on
`127.0.0.1:8765`. With an emulator, forward a host port:

```sh
adb forward tcp:18765 tcp:8765
curl http://127.0.0.1:18765/status
curl -X POST -H 'Content-Type: application/json' \\
  -d '{"op":"setXp","value":5678}' \\
  http://127.0.0.1:18765/edit
curl http://127.0.0.1:18765/export -o edited.save
```

Use `POST /import` with `--data-binary @save` to load a save, `POST /reset` to
restore the import snapshot, and `POST /edit` with an `args` array for methods
that take multiple arguments (for example `{"op":"setLineupCat","args":[0,0,12]}`).

For a one-command upstream differential run (build, install, start the loopback
API, and compare SHA256), use:

```sh
./tools/run_api_diff.sh issues/issue /path/to/another.save
```

Compact reports are written to `artifacts/api-diff/`; exported save copies are
removed after each comparison.

## Verification status

Advertising has been removed. Upload and root loading use native confirmation
dialogs without banners, pop-ups or an advertising delay.

The About page's sponsor list is maintained in [sponsors.json](sponsors.json).
Opening the list fetches the GitHub Raw file again; names are not compiled into
the APK. Edit that JSON on the default branch to update the list without a release.

Native game regression testing uses the [Battle Cats emulator CLI](docs/battlecats-cli.md),
which combines Android controls and local OCR, save backups and process diagnostics.
See the [new-save loading investigation](docs/new-save-crash-2026-11.md) for the
version-specific native trailer repair.

- JVM tests cover region checksums, persistent sessions, item/cat/account
  editors, variable-length tables, game-version and region conversion, and
  fixed protocol signature vectors.
- Major map families are compared byte-for-byte against saves serialized by
  BCSFE-Python, including Event, Challenge, Gauntlet, Enigma, Legend Quest,
  Tower, Zero Legends, and Catclaw Dojo layouts.
- A TW 15.5.0 transfer save has been received from the production transfer
  endpoint, parsed with a valid checksum, and imported through Android's
  document picker on an Android emulator.
- The debug APK has been installed and exercised on an Android emulator in both
  languages. Session recovery after force-stop/relaunch, exit confirmation,
  category navigation, destructive cat-reset confirmation, and the cleared
  Enigma map editor have been verified on-device.

Transfer credentials and downloaded save data are test-only and are never
stored in this repository or application logs.

## Reference And Attribution

This project references the architecture, save-format research, feature set,
and localization work of
[fieryhenry/BCSFE-Python](https://github.com/fieryhenry/BCSFE-Python). Thanks to
fieryhenry and all contributors to that project. BCSFE-Python is licensed under
the GNU General Public License version 3 or later; this repository is likewise
distributed under GPL-3.0-or-later. The upstream source was used as a reference
while developing this independent Android port.

- Android repository: <https://github.com/tuxKOH/BCSFE-Android>
- Reference repository: <https://github.com/fieryhenry/BCSFE-Python>

The temporary upstream checkout used during development is kept outside this
repository and is not tracked by Git.
