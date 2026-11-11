#!/usr/bin/env python3
"""Byte-compare item, Aku Realm and received-token edits with upstream.

Only scrubbed bundled templates are used. No network or player account calls.
Includes valid 100 KB dictionaries and decoy version integers in dialogue data.
"""
from pathlib import Path
import argparse
import json
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, default=Path("/tmp/BCSFE-Python"))
    args = parser.parse_args()
    sys.path.insert(0, str(args.upstream / "src"))
    from bcsfe import core

    classes = ROOT / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
    probe = ROOT / "app/build/upstream-probe"
    subprocess.run(["javac", "-cp", str(classes), "-d", str(probe), str(ROOT / "tools/GameDataProbe.java")], check=True)
    versions = json.loads((ROOT / "tools/game_data_manifest.json").read_text())["regions"]
    comparisons = 0
    with tempfile.TemporaryDirectory(prefix="bcsfe-item-records-") as temp:
        core.set_config_path(core.Path(str(Path(temp) / "config.json")))
        for region, snapshot in versions.items():
            for shifted in (False, True):
                def load(raw):
                    core.core_data.init_data()
                    return core.SaveFile(core.Data(raw))

                save = load((ROOT / f"app/src/main/assets/new_saves/{region}.save").read_bytes())
                save.set_gv(core.GameVersion.from_string(snapshot["version"]))
                save.cats.cats.extend(core.Cat.init(i) for i in range(len(save.cats.cats), snapshot["cat_count"]))
                save.new_dialogs_2 = [80200, 100300, 110000, 90500, 90700, 0, 0]
                save.talent_orbs.set_orb(30000, 17)  # Preserve unknown/native future IDs.
                save.ushdshd2 = {1: [1, 2, 300], 4: []}
                save.ushdd = {3: 1700000000.0}
                save.ushdd2 = {5: 1700000001.0}
                if shifted:
                    save.uidiid2 = {i: {j: j for j in range(127)} for i in range(300)}
                for m, chapter in enumerate(save.behemoth_culling.chapters):
                    for star, entry in enumerate(chapter.chapters):
                        entry.selected_stage = 2
                        entry.clear_progress = len(entry.stages)
                        entry.chapter_unlock_state = 3
                        for stage, record in enumerate(entry.stages):
                            record.clear_times = 1 + m + star + stage
                source = save.to_data().get_bytes()
                directory = Path(temp) / f"{region}-{shifted}"
                directory.mkdir()
                fixture = directory / "source.save"
                fixture.write_bytes(source)
                subprocess.run(["java", "-cp", f"{probe}:{classes}", "io.github.tuxkoh.bcsfe.core.GameDataProbe",
                                "--items", str(fixture), str(directory)], check=True)

                def equal(name, expected):
                    nonlocal comparisons
                    actual = (directory / (name + ".save")).read_bytes()
                    comparisons += 1
                    if expected != actual:
                        first = next((i for i, pair in enumerate(zip(expected, actual)) if pair[0] != pair[1]), min(len(expected), len(actual)))
                        raise AssertionError(f"{region} shifted={shifted} {name}: first difference {first}, lengths {len(expected)}/{len(actual)}")
                    # Decode all culling stages with the independent upstream parser.
                    parsed = load(actual)
                    if name != "behemoth":
                        assert parsed.behemoth_culling.serialize() == load(source).behemoth_culling.serialize()

                equal("roundtrip", source)
                save = load(source);save.leadership = 117
                equal("leadership", save.to_data().get_bytes())
                equipment = json.loads((Path.home() / ".local/share/bcsfe/game_data" / region /
                                        snapshot["version"] / "DataLocal/equipmentlist.json").read_text())["ID"]
                for grade in (3, 4, -1):
                    save = load(source)
                    for orb, entry in enumerate(equipment):
                        if grade == -1 or entry["gradeID"] == grade:
                            save.talent_orbs.set_orb(orb, 1)
                    equal("orbs-" + str(grade), save.to_data().get_bytes())
                save = load(source)
                actual = load((directory / "endless.save").read_bytes())
                for i, (expected, edited) in enumerate(zip(save.battle_items.items, actual.battle_items.items)):
                    duration = float("inf") if i == 5 else 90 + i
                    assert edited.endless_item.get_endless_duration() is None if i == 5 else abs(edited.endless_item.get_endless_duration().total_seconds() / 60 - duration) < 0.001
                    expected.endless_item.active = True
                    expected.endless_item.unknown = True
                    expected.endless_item.amount = 0
                    expected.endless_item.start = edited.endless_item.start
                    expected.endless_item.end = edited.endless_item.start + duration * 60
                equal("endless", save.to_data().get_bytes())
                save = load(source);save.password_refresh_token = "0123456789012345678901234567890123456789"
                equal("received", save.to_data().get_bytes())
                save = load(source);save.behemoth_culling.clear_stage(2, 0, 0, 7)
                equal("behemoth", save.to_data().get_bytes())
                save = load(source)
                for i, stage in enumerate(save.aku.chapters[0].chapters[0].stages):
                    stage.clear_times = 5 if i < 10 else 0
                equal("aku-progress", save.to_data().get_bytes())
                save = load(source)
                for stage in save.aku.chapters[0].chapters[0].stages:
                    stage.clear_times = 1
                equal("aku-clear", save.to_data().get_bytes())
                save = load(source);save.aku.chapters[0].chapters[0].stages[48].clear_times = 7
                equal("aku-stage", save.to_data().get_bytes())
                print(f"{region} {snapshot['version']}, shifted={shifted}: all orbs, item fields and culling progress pass", flush=True)
    print(f"PASS: {comparisons} upstream item-record comparisons")


if __name__ == "__main__":
    main()
