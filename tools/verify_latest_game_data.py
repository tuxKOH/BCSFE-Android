#!/usr/bin/env python3
"""Compare latest regional saves and every bundled rule with BCSFE-Python.

Run ./gradlew assembleDebug first. Uses scrubbed templates, local BCData only,
and build-directory artifacts; never contacts account/transfer services.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, default=Path("/tmp/BCSFE-Python"))
    parser.add_argument("--cache", type=Path, default=Path.home() / ".local/share/bcsfe/game_data")
    args = parser.parse_args()
    sys.path.insert(0, str(args.upstream / "src"))
    from bcsfe import core
    from bcsfe.core.game.catbase.cat import CatSkill, CatSkills

    out = ROOT / "app/build/upstream-validation"
    classes = ROOT / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
    probe = ROOT / "app/build/upstream-probe"
    subprocess.run(["javac", "-cp", str(classes), "-d", str(probe), str(ROOT / "tools/GameDataProbe.java")], check=True)
    subprocess.run(["java", "-cp", str(probe) + ":" + str(classes), "io.github.tuxkoh.bcsfe.core.GameDataProbe",
                    str(ROOT / "app/src/main/assets/new_saves"), str(out)], check=True)
    versions = json.loads((ROOT / "tools/game_data_manifest.json").read_text())["regions"]
    comparisons = 0
    failures = []

    def equal(name, expected, actual):
        nonlocal comparisons
        comparisons += 1
        if expected != actual:
            if isinstance(expected, bytes) and isinstance(actual, bytes):
                first = next((i for i, pair in enumerate(zip(expected, actual)) if pair[0] != pair[1]), min(len(expected), len(actual)))
                failures.append(f"{name}: length {len(expected)}/{len(actual)}, first difference {first}")
            else:
                failures.append(f"{name}: expected {expected!r}, actual {actual!r}")

    with tempfile.TemporaryDirectory(prefix="bcsfe-upstream-check-") as temp:
        core.set_config_path(core.Path(str(Path(temp) / "config.json")))
        for region, snapshot in versions.items():
            directory = args.cache / region / snapshot["version"]

            class CachedData:
                def download(self, pack, name):
                    path = directory / pack / name
                    return core.Data(path.read_bytes()) if path.is_file() else None

            def reset():
                core.core_data.init_data()
                core.core_data.game_data_getter = CachedData()

            def load(raw):
                reset()
                return core.SaveFile(core.Data(raw))

            def data_csv(name):
                return core.CSV(core.Data((directory / "DataLocal" / name).read_bytes()))

            template = (ROOT / "app/src/main/assets/new_saves" / (region + ".save")).read_bytes()
            expected = load(template)
            expected.set_gv(core.GameVersion.from_string(snapshot["version"]))
            expected.cats.cats.extend(core.Cat.init(i) for i in range(len(expected.cats.cats), snapshot["cat_count"]))
            raw = (out / (region + "-new.save")).read_bytes()
            equal(region + " new save vs upstream serializer", expected.to_data().get_bytes(), raw)
            equal(region + " parsed round trip", load(raw).to_data().get_bytes(), raw)
            for path in sorted(out.glob(region + "-version-*.save")):
                save = load(raw)
                target = int(path.stem.rsplit("-", 1)[1])
                save.set_gv(core.GameVersion(target))
                equal(region + " convert " + str(target), save.to_data().get_bytes(), path.read_bytes())
            for version in (150501, 150601, 150700, 150701, 150702, 150703):
                major, minor, patch = map(int, snapshot["version"].split("."))
                ceiling = major * 10000 + minor * 100 + patch
                if version >= ceiling:
                    continue
                fixture = out / f"{region}-intermediate-{version}.save"
                edited = out / f"{region}-intermediate-{version}-edited.save"
                save = load(raw)
                save.set_gv(core.GameVersion(version))
                intermediate = fixture.read_bytes()
                equal(region + f" intermediate {version} serializer", save.to_data().get_bytes(), intermediate)
                equal(region + f" intermediate {version} round trip", load(intermediate).to_data().get_bytes(), intermediate)
                save.normal_tickets, save.rare_tickets = 123, 234
                cat = save.cats.cats[-1]
                helper = core.PowerUpHelper(cat, save)
                helper.reset_upgrade()
                helper.upgrade_by(9)
                cat.set_upgrade(save, core.Upgrade(0, 9), True)
                cat.set_upgrade(save, core.Upgrade(3, -1), True)
                cat.set_form(1, save)
                equal(region + f" intermediate {version} edits", save.to_data().get_bytes(), edited.read_bytes())
            save = load(raw)
            save.normal_tickets, save.rare_tickets = 123, 234
            equal(region + " tickets", save.to_data().get_bytes(), (out / (region + "-tickets.save")).read_bytes())
            # Have upstream serialize ambiguous dialogue records plus both
            # MySale dictionaries. Cover all four variable-integer widths,
            # a multi-byte dictionary count, and unknown gatya flags.
            for hash_length in (0, 32):
                save = load(raw)
                save.new_dialogs_2 = [-1, -1, len(save.cats.cats)] + [0] * 1197
                save.save_data_4_hash = "0" * hash_length
                save.mysale.dict_1 = {i: i for i in range(130)}
                save.mysale.dict_1.update({16384: 2097152, 2097152: 268435455})
                save.mysale.dict_2 = {1: True, 128: False, 16384: True, 2097152: False}
                save.cats.cats[0].gatya_seen = -1
                save.normal_tickets, save.rare_tickets = 111, 22
                save.platinum_tickets, save.legend_tickets, save.platinum_shards = 3, 4, 5
                fixture = out / f"{region}-variable-prefix-{hash_length}.save"
                edited = out / f"{region}-variable-prefix-{hash_length}-edited.save"
                fixture.write_bytes(save.to_data().get_bytes())
                subprocess.run(["java", "-cp", str(probe) + ":" + str(classes),
                                "io.github.tuxkoh.bcsfe.core.GameDataProbe", "--tickets",
                                str(fixture), str(edited)], check=True)
                save.normal_tickets, save.rare_tickets = 123, 234
                save.platinum_tickets, save.legend_tickets, save.platinum_shards = 6, 2, 8
                equal(region + f" variable-prefix tickets ({hash_length})",
                      save.to_data().get_bytes(), edited.read_bytes())
            save = load(raw)
            cat = save.cats.cats[-1]
            helper = core.PowerUpHelper(cat, save)
            helper.reset_upgrade()
            helper.upgrade_by(9)  # Displayed level 10 stores base 9.
            cat.set_upgrade(save, core.Upgrade(0, 9), True)
            cat.set_upgrade(save, core.Upgrade(3, -1), True)
            cat.set_form(1, save)
            equal(region + " latest cat upgrade/form", save.to_data().get_bytes(), (out / (region + "-cat.save")).read_bytes())
            save = load(raw)
            skills = CatSkills.from_game_data(save)
            assert skills is not None
            for cat in save.cats.cats:
                skill = skills.get_cat_skill(cat.id)
                maxima = {ability.ability_id: ability.max_lv or 1 for ability in skill.skills if ability.ability_id} if skill else {}
                available = [talent for talent in cat.talents or [] if talent.id in maxima]
                if available:
                    cat.unlock(save)
                for talent in available:
                    talent.level = maxima[talent.id]
            equal(region + " max talents", save.to_data().get_bytes(), (out / (region + "-talents.save")).read_bytes())

            save = load(raw)
            units = save.cats.read_unitbuy(save)
            book = save.cats.read_nyanko_picture_book(save)
            skills = CatSkills.from_game_data(save)
            missions = core.MissionConditions(save)
            maps = core.MapOption.from_csv(data_csv("Map_option.csv"))
            drops = core.CharaDrop(save).drops
            gifts = core.RankGifts(save).rank_gift
            actual = list(csv.reader((out / (region + "-rules.csv")).read_text().splitlines()))
            expected_rows = []
            for cat in save.cats.cats:
                unit = units.get_unit_buy(cat.id)
                entry = book.get_cat(cat.id)
                core.PowerUpHelper(cat, save)
                expected_rows.append(["cat", cat.id, unit.rarity, unit.original_max_levels[0], unit.max_upgrade_level_no_catseye,
                                      unit.max_upgrade_level_catseye, unit.max_plus_upgrade_level, unit.unknown_22,
                                      entry.total_forms if entry else -1, int(entry.is_displayed_in_catguide) if entry else 0,
                                      cat.max_upgrade_level.base, cat.max_upgrade_level.plus])
            for row in data_csv("SkillAcquisition.csv").lines[1:]:
                skill = CatSkill.from_row(row)
                expected_rows.extend(["talent", skill.cat_id, ability.ability_id, ability.max_lv or 1]
                                     for ability in skill.skills if ability.ability_id)
            expected_rows.extend(["mission", key, value.progress_count] for key, value in missions.conditions.items())
            # Upstream also parses the header as mission ID 0; its first numeric
            # row replaces it. Dictionaries retain the original numeric order.
            expected_rows.extend(["map", key, value.crown_count] for key, value in maps.maps.items())
            expected_rows.extend(["drop", drop.chara_id, drop.save_id] for drop in drops or [])
            expected_rows.extend(["gift", gift.index, gift.threshold] for gift in gifts or [])
            expected_rows = [[str(cell) for cell in row] for row in expected_rows]
            equal(region + " rule row count", len(expected_rows), len(actual))
            for i, (expected_row, actual_row) in enumerate(zip(expected_rows, actual)):
                equal(region + " rule " + str(i), expected_row, actual_row)
            print(f"{region} {snapshot['version']}: compared new save, conversions, edits and {len(actual)} rule rows")
    if failures:
        print("\n".join(failures[:30]), file=sys.stderr)
        print(f"FAILED: {len(failures)} of {comparisons} comparisons", file=sys.stderr)
        return 1
    print(f"PASS: {comparisons} upstream comparisons")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
