# Regional BCData snapshots

Snapshot checked on 2026-11-08, against BCSFE-Python `a54abde` and BCData commit
`8644713249966c10a880e056c2f3e58fe21cd4a3`. These releases were extracted from
the official regional APKs using the BCData README workflow because upstream
metadata has not listed them yet. Upstream source checkouts and caches stay
outside this repository.

| Region | Version | unitbuy cat records | Known rank gifts |
| --- | --- | --- | --- |
| JP | 15.7.4 | 882 | 349 |
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
map crowns, and scheme items. Identical regional tables are deduplicated. Names
are regenerated independently into both Android locales. Historical 15.5/15.6
rules remain intact and are selected for saves of those versions.

The shared 15.5 serialization layout is also supported for intermediate patch
versions, bounded by each region's latest snapshot above. As in upstream
`GameDataGetter.get_version`, a missing exact patch uses the next available
regional BCData snapshot. The original 15.5.0/15.6.0 rule tables remain selected
for those exact versions; intermediate patches use the bundled newer snapshot.
Importing or editing does not rewrite the save version. The conversion menu
includes JP 15.7.1 through 15.7.3, regional 15.6.1 and 15.5.1, with checksum and layout checks
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
