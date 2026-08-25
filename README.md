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

The format core validates and rewrites the region-specific MD5 checksum and
preserves unknown data. Current deep editors target JP 15.6/15.5 saves and 15.5
saves in EN, TW, and KR. Region conversion is byte-tested for 15.5. Version conversion supports
14.0 and 14.3 through 15.5, including the version-dependent Dojo, Enigma, and
tail record layouts.

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

## Verification status

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
