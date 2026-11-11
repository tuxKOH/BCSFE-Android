# Regional BCData snapshots

Snapshot checked on 2026-11-11, against BCSFE-Python `85fb94c` and BCData commit
`8644713249966c10a880e056c2f3e58fe21cd4a3`. These releases were extracted from
the official regional APKs using the BCData README workflow because upstream
metadata has not listed them yet. Upstream source checkouts and caches stay
outside this repository.

| Region | Version | unitbuy cat records | Known rank gifts |
| --- | --- | --- | --- |
| JP | 16.0.1 | 888 | 352 |
| EN | 15.7.0 | 882 | 349 |
| TW | 15.7.0 | 882 | 349 |
| KR | 15.7.0 | 882 | 349 |

Source: <https://git.battlecatsmodding.org/fieryhenry/BCData>.
Release versions are taken from its `metadata.json` when available; this update
uses the latest APK versions reported by `apkdown` and records extraction
provenance in `tools/game_data_sources.json`. `tools/game_data_manifest.json` records the versions
and SHA-256 of every rule input. These hashes identify public game tables, never
player accounts or save files.

`tools/sync_game_data.py` emits compact Java tables for unitbuy, picture-book
availability/forms, drop slots, unit limits, rank gifts, talents, mission targets,
map crowns, scheme items, and Talent Orb grades/effects/attributes. Identical regional tables are deduplicated. Names
are regenerated independently into both Android locales. Historical 15.5/15.6
rules remain intact and are selected for saves of those versions. The previous
JP 15.7.4 snapshot is also retained: JP saves through 15.7.4 use its 882-cat and
349-reward tables, while 16.0.0/16.0.1 use the new 888-cat/352-reward snapshot.

The shared 15.5 serialization layout is also supported for intermediate patch
versions, bounded by each region's latest snapshot above. As in upstream
`GameDataGetter.get_version`, a missing exact patch uses the next available
regional BCData snapshot. The original 15.5.0/15.6.0 rule tables remain selected
for those exact versions; intermediate patches use the bundled newer snapshot.
Importing or editing does not rewrite the save version. The conversion menu
includes JP 15.7.1 through 15.7.4 and 16.0.0, regional 15.6.1 and 15.5.1, with checksum and layout checks
retained. Future regional versions above the verified ceiling remain unsupported.

## Updating

1. Check upstream format changes before certifying any new revision. Compare
   `/tmp/BCSFE-Python/src/bcsfe/core/io/save.py`, not just the version number.
2. Download the official regional archives named in BCData metadata, extracting
   them safely to `~/.local/share/bcsfe/game_data/REGION/VERSION`.
3. Update `VERSIONS` in `tools/sync_game_data.py`, then run:

   ```sh
   python3 tools/sync_game_data.py
   python3 tools/generate_cat_names.py ~/.local/share/bcsfe/game_data
   ./gradlew test assembleDebug
   python3 tools/verify_latest_game_data.py
   python3 tools/verify_item_records.py
   ```

4. Check the diff, supported-version notices in both locales, and emulator flows.
   The generator does not automatically certify unknown future save formats.

The verifier needs Python dependencies from the upstream project, the external
checkout at `/tmp/BCSFE-Python`, a JDK, and the BCData cache. It compares the Android
factory and representative edits byte for byte against the upstream serializer,
then compares all regional rule rows against the upstream readers. It uses only
bundled scrubbed templates and local game data, without account/transfer calls.
Ticket checks also use upstream-generated variable dialogue lists and MySale
dictionaries, including all four variable-integer widths. Normal/rare ticket
offsets follow the serialized prefix rather than searching for a nearby
cat-count integer. Platinum shards follow the validated account suffix rather
than the first 100600 marker, which can instead be an EN region record. Checks
cover normal, rare, platinum and legend tickets plus platinum shards; only the
requested ticket bytes may change.

## Account preparation choices

Upload, root import and root-write confirmation dialogs each offer a
replacement-account checkbox, enabled by default whenever the dialog opens.
Clearing it uses the existing account for upload, opens the original account's
copy on root import, or writes the original save for root loading.
An unchecked upload never falls back to creating a replacement account on
failure. The checkbox is a native control outside the advertisement WebView.

## New-save creation

The bundled scrubbed 15.5 templates remain immutable regression fixtures.
`SaveDocument.fromNewSaveTemplate` works on a copy, converts to the regional latest
version, and expands all nine per-cat serialized lists using upstream
`Cat.init` defaults. Existing template cat states and unknown bytes are preserved;
new cats start locked at level 1. The existing rank reward capacity is retained
or expanded as necessary. Account creation runs only after this structural
upgrade succeeds and uses the same regional version in its requests.

Normal import does not invoke the factory or resize/relabel an existing save.
The format core stays independent of Android UI classes.

## Verification on 2026-11-11 (1.4.2)

JP 16.0.1 was downloaded from the APKPure version API used by `apkdown`, merged
with BCData's APKEditor, and decrypted with `tbctk-asset` at `c21e94c`. A small
standalone Rust wrapper calls the same `BCApk::new`, `GamePacks::from_pkg`, and
`extract_some` APIs as `tbctk-cli extract pkg`, selecting `DataLocal` and
`resLocal`; the CLI itself references unavailable developer-local Lua paths.
The archive hash and tool revisions are recorded in `tools/game_data_sources.json`.
Upstream `save.py` is unchanged from the previously verified revision and adds
no 16.0 serialization gate. New-save creation, conversions, intermediate-patch
editing and all current regional rules pass 20,398 upstream comparisons.

`./gradlew test assembleDebug` passes: 102 tests pass per build variant, with
97 historical tests skipped because their external fixtures are absent.
The item-record verifier adds 88 independent byte comparisons across four
regions, including 100 KB dictionaries, decoy version integers, all 62 S-grade
orbs, all 310 orbs, Leadership, all six endless items, reception token updates,
and Behemoth Culling edits. Every untouched culling stage is checked against
the upstream parser. Mock-server tests additionally cover replacement-account
creation followed by upload and reception without losing those edits/progress.
The additional Aku checks include nonempty adjacent dictionaries, progress,
single-stage and chapter edits. Aku layout lookup validates the complete
100700/100900/101000 record chain and caches the parsed dimensions; any save
splice invalidates that cache. Batch writes resolve it once, eliminating the
per-stage recursive offset scans that froze the editor. On the same desktop
JP fixture, a 49-stage update dropped from about 5.8 seconds to 2.3 milliseconds.

The orb parser now walks the native 90500/90700/90800 record boundaries rather
than searching for a plausible header in preceding data. Leadership requires
the complete 80000/80200/80300 record. Endless items follow the validated
refresh-token/date record; Behemoth Culling validates its dictionary, table and
110500/110600 continuation. Missing or ambiguous records reject edits before
writing. Batch orb actions use one resize and retain unknown orb IDs and the
following dictionaries.

English UI QA on emulator-5554 and Simplified Chinese QA on emulator-5556
imported JP 16.0.1, set all S orbs to one, opened the individual-orb editor and
exported through the system document picker. Both exports contain all 62 S
orbs and match each other and the upstream serializer byte for byte. The
Chinese export was reimported through the system document picker. The final
Chinese batch dialog displays “S级本能玉” rather than the internal grade number.
The Chinese UI also set Leadership to 117 and all six endless items to 90
minutes; the exported fields and complete bytes matched the upstream serializer
and retained the S orbs and culling records.
Aku Realm UI QA on emulator-5556 opened the editor and applied 49 clears at
count seven in Chinese, then ten clears at count one in English. Both exports
matched the upstream serializer byte for byte, with no ANR during either edit.
These are offline/template and mock-server checks; the reported reset after
reception has not been reproduced with a real affected account on game servers.

The process-start coffee notice was checked in English and Simplified Chinese:
the link opens the browser, dismissal survives navigation and locale recreation,
and a fresh process displays it again. System document export/import and a second
export preserve the working save byte for byte with the notice present.

## Verification on 2026-11-08

`./gradlew test assembleDebug` succeeds. Each JVM build variant runs 91 tests
successfully and skips 97 historical tests whose external save fixtures are
absent. The offline verifier passes 20,336 comparisons against the upstream
readers/serializer, including ticket reads and edits in variable-prefix saves,
intermediate-patch imports and edits, and the expanded conversion targets.

On emulator-5554, TW and JP new saves were created and exported, the JP export
was reimported, and the latest JP cat (ID 881) was visible in the editor. The
replacement controls were checked in English and Simplified Chinese. Root
confirmation dialogs were opened through temporary test instrumentation because
this emulator's su rejects the app UID; actual root file I/O is not claimed as
an emulator test. The unchecked preparation/write branches are covered by JVM
tests, with no account requests and unchanged source bytes.
The final APK also imported the upstream-generated EN variable-prefix fixture:
its UI shows normal/rare/platinum/legend ticket values 111/22/3/4 and platinum
shards 5, matching the upstream values exactly. The temporary instrumentation
APK was uninstalled after the confirmation checks.

Intermediate-patch QA on emulator-5554 converted JP 15.7.2 to 15.7.1 in
English and exported through the system document picker. That export matched
the offline expected save byte for byte. In Simplified Chinese, the export
was imported through the document picker, converted to 15.7.0, and its ticket
editor opened with the expected values before converting back to 15.7.2.
JVM tests also cover intermediate-patch root loading with both replacement
account choices and both explicit and auto-detected JP regions.
