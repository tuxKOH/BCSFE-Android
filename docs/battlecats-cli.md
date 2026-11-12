# Battle Cats emulator CLI

`tools/battlecats.py` drives the **game**, rather than the BCSFE editor. Its JSON
output combines Android controls with local OCR of the native game surface.
This avoids sending screenshots to a model for each operation. It does not
claim to expose private C++ game functions: BCData's public toolchain provides
asset/APK parsing, not a stable game automation API.

## Setup

```sh
python3 -m venv /tmp/battlecats-cli
/tmp/battlecats-cli/bin/pip install -r tools/battlecats-requirements.txt
/tmp/battlecats-cli/bin/python tools/battlecats.py --serial emulator-5556 status
```

Native OCR supports Chinese/Japanese/English text. It is optional for process,
file and crash commands. `screen --android-only` handles Android/WebView dialogs
without OCR. Select the device explicitly when several emulators are running.
Physical devices require `--allow-device`.

## Commands

```sh
# Inspect visible text and bounds, then operate a recognized menu.
python tools/battlecats.py --serial emulator-5556 --region tw --qa launch
python tools/battlecats.py --serial emulator-5556 --region tw --qa screen
python tools/battlecats.py --serial emulator-5556 --region tw --qa action start
python tools/battlecats.py --serial emulator-5556 --region tw --qa tap '升級'
python tools/battlecats.py --serial emulator-5556 --region tw --qa wait '貓咪基地'
python tools/battlecats.py actions
```

Named actions cover start, settings, transfer upload/receive, base, battle,
upgrade, equipment, normal/rare capsules, shop, missions, rank rewards,
Gamatoto, Ototo, guide, treasures, menu, ok/cancel/back. Actions only click
labels present in the current screen. Unknown versions/ambiguous labels fail;
use `tap --index N`, `tapxy`, `swipe` or `key` for controls drawn as icons.
These commands act on the current screen; `action equip` does not navigate
through unrelated screens automatically. Capsule draws, purchases and actual
transfers require explicit commands.

`text --file /private/path` enters ASCII text without echoing credentials in
output. Recognized account/PIN/password input boxes are redacted in Android
and OCR output. Adb's text input does not support arbitrary CJK text.
`run-script steps.json` reuses one OCR engine for sequential recipes:

```json
[
  {"wait": "ゲームスタート"},
  {"action": "start"},
  {"sleep": 2},
  {"action": "upgrade"}
]
```

Recipes stop on a failed step. Each `wait` has a 30-second timeout; standalone
`wait --seconds N` allows longer asset downloads. Sleeps are capped at 30 s.

## Save and crash investigation

Private save access requires a debuggable QA copy. It uses `run-as`, does not
need root and does not change the normal installed game. `--qa` appends
`.bcsfeqa` to the regional package name. For a fresh install with no save use
`load-save --initialize`; it records the absence in the mandatory backup file.
Backups/pulls refuse to overwrite local files and use mode 0600. Load stops the
game, writes the save and verifies every byte before returning success.

```sh
python tools/battlecats.py --serial emulator-5556 --qa pull-save /tmp/native.save
python tools/battlecats.py --serial emulator-5556 --qa load-save /private/test.save \
  --backup /private/before-test.save
python tools/battlecats.py --serial emulator-5556 --qa launch
python tools/battlecats.py --serial emulator-5556 --qa monitor --seconds 30
python tools/battlecats.py --serial emulator-5556 --qa crashes
python tools/battlecats.py --serial emulator-5556 --qa exit-info
```

`monitor` reports **process liveness**, not successful save loading. Inspect the
foreground activity and game text to establish that the base/map was reached.
`exit-info` distinguishes low-memory termination from Java/native crashes.
Crash output is package-filtered and redacts common account-code/token forms;
save contents are never printed. Raw screenshots and local saves can contain
private data and stay outside the repository.

## Separate QA APKs

BCData uses [tbctk](https://codeberg.org/fieryhenry/tbctk) to read/patch APKs.
`tools/prepare_battlecats_qa.py` uses the same public APIs to make a debuggable
copy under a separate package name. Requires a local tbctk checkout, Cargo,
Android build tools and the normal Android debug keystore.

```sh
python3 tools/prepare_battlecats_qa.py /private/game.xapk /tmp/game-qa \
  --region jp --version 16.0.1 --tbctk /tmp/tbctk
adb -s emulator-5556 install-multiple --no-incremental -r /tmp/game-qa/*.apk
```

Keep original splits: merging newer sparse Android resource tables can produce
unrelated `Resources$NotFoundException` failures before the game loads. The
preparer retains all original split files, resource IDs, native libraries and
assets, changes only manifest package/debug attributes and fixed resource-table
package names, and signs with the local debug key. Added manifest attributes
are sorted by resource ID for Android's attribute lookup. Original player
installations, accounts and saves are not modified by preparation/install.
Do not distribute these third-party test APKs with a BCSFE release.
