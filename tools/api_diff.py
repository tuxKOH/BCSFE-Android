#!/usr/bin/env python3
"""Differential test runner for the Android loopback editor API.

For every case this program starts from the same save, applies the equivalent
operation to the upstream SaveFile and to the APK, then compares the exported
bytes.  No account-specific offsets are used; indexes are obtained from the
parsed objects.  The APK must already be running with adb port forwarding.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Any

UPSTREAM = Path("/tmp/BCSFE-Python-upstream/src")
sys.path.insert(0, str(UPSTREAM))
from bcsfe import core  # type: ignore
from bcsfe.core.io.data import Data  # type: ignore
from bcsfe.core.io.save import SaveFile  # type: ignore
from bcsfe.core.game.map.enigma import Stage as EnigmaStage  # type: ignore
from bcsfe.core.game.gamoto.ototo import Cannons  # type: ignore


@dataclass(frozen=True)
class Case:
    name: str
    request: dict[str, Any]
    mutate: Callable[[Any], None]


def _setattr(name: str, value: Any) -> Callable[[Any], None]:
    return lambda s: setattr(s, name, value)


def _setpath(path: str, value: Any) -> Callable[[Any], None]:
    parts = path.split(".")
    def apply(obj: Any) -> None:
        target = obj
        for part in parts[:-1]:
            target = getattr(target, part)
        setattr(target, parts[-1], value)
    return apply


def _remove_first_storage(s: Any) -> None:
    item = next(x for x in s.cats.storage_items if x.item_type != 0)
    item.item_type = 0
    item.item_id = 0


def _upgrade_cat_base(s: Any, cat: Any, amount: int) -> None:
    helper = core.PowerUpHelper(cat, s)
    helper.reset_upgrade()
    helper.upgrade_by(amount)
    cat.set_upgrade(s, core.Upgrade(0, amount), True)


def _grant_gold_pass(s: Any) -> None:
    club = s.officer_pass.gold_pass
    start = 1700000000
    end = start + 30 * 86400
    total_end = start + 60 * 86400
    club.get_gold_pass(123, 30, s)  # structure and login reset semantics
    # get_gold_pass uses wall-clock time; overwrite its time fields with the
    # deterministic values used by the Android API case.
    club.start_date_now = start
    club.end_date_now = end
    club.start_date_next = end
    club.end_date_next = total_end
    club.start_date_total = start
    club.end_date_total = total_end
    club.time_error_end = start
    club.login_bonus_date = end


def _fix_rank_rewards(s: Any) -> None:
    gifts = core.core_data.get_rank_gifts(s).rank_gift or []
    rank = s.calculate_user_rank()
    for gift in gifts:
        if gift.threshold > rank:
            s.user_rank_rewards.rewards[gift.index].claimed = False


def _set_dojo_score(s: Any, value: int) -> None:
    # The first dojo stage (chapter/stage id 0) is edited by the upstream dialog.
    s.dojo.chapters.get_stage(0, 0).score = value


def _clear_challenge(s: Any, crowns: int = 4, cleared: bool = False) -> None:
    """Mirror challenge map clear semantics used by the Android editor."""
    stars = s.challenge.chapters.chapters[0].chapters
    for chapter in stars[:crowns]:
        for stage in chapter.stages:
            stage.clear_times = (stage.clear_times or 1) if cleared else 0
        chapter.clear_progress = len(chapter.stages) if cleared else 0
        if cleared: chapter.chapter_unlock_state = 3
    if cleared and len(s.challenge.chapters.chapters) > 1:
        s.challenge.chapters.chapters[1].chapters[0].chapter_unlock_state = 1


def cases_for(save: Any) -> list[Case]:
    """Build cases from parsed structures so variable layouts stay dynamic."""
    # Match the upstream dialog's configured max (9 tickets total), while
    # choosing a value valid for each account's current platinum-ticket count.
    platinum_shards_value = min(55, max(0, (9 - save.platinum_tickets) * 10 + 9))
    out: list[Case] = [
        # Region conversion is a structural rewrite (not just metadata): the
        # upstream serializer inserts/removes the locale block and re-hashes
        # with the target salt.  Exercise every reachable target from the
        # parsed source region so this remains account/layout independent.
        *[
            Case(
                f"convert-region-{target}",
                {"op": "convertRegion", "args": [target]},
                lambda s, code=target: s.set_cc(core.CountryCode.from_code(code)),
            )
            for target in ("en", "jp", "kr", "tw")
            if target != str(save.cc)
        ],
        # Version conversion uses the same explicit targets exposed by the
        # Android dialog.  Keep the source at 15.5 for each independent case;
        # unsupported combinations are reported by both implementations.
        *[
            Case(
                f"convert-version-{target}",
                {"op": "convertGameVersion", "value": target},
                lambda s, version=target: s.set_gv(core.GameVersion(version)),
            )
            for target in (140000, 140300, 150300, 150400, 150500, 150600)
            if target != save.game_version.game_version
            and not (target == 150600 and str(save.cc) != "jp")
        ],
        Case("mute-bgm", {"op": "setMuteBgm", "value": True}, _setattr("mute_bgm", True)),
        Case("mute-se", {"op": "setMuteSe", "value": True}, _setattr("mute_se", True)),
        Case("catfood", {"op": "setCatFood", "value": 1111}, _setattr("catfood", 1111)),
        Case("energy", {"op": "setCurrentEnergy", "value": 2222}, _setattr("current_energy", 2222)),
        Case("xp", {"op": "setXp", "value": 3333}, _setattr("xp", 3333)),
        Case("tutorial", {"op": "setTutorialState", "value": 4}, _setattr("tutorial_state", 4)),
        Case("inquiry", {"op": "setInquiryCode", "value": "123456789"}, _setattr("inquiry_code", "123456789")),
        Case("token", {"op": "setPasswordRefreshToken", "value": "A" * len(save.password_refresh_token)}, _setattr("password_refresh_token", "A" * len(save.password_refresh_token))),
        Case("created", {"op": "setAccountCreatedAt", "value": 1700000000}, _setattr("energy_penalty_timestamp", 1700000000.0)),
        Case("playtime", {"op": "setPlayTimeComponents", "args": [1, 2, 3]}, _setpath("officer_pass.play_time", (1 * 3600 + 2 * 60 + 3) * 30)),
        Case("playtime-raw", {"op": "setPlayTime", "value": 1234}, _setpath("officer_pass.play_time", 1234)),
        Case("ban", {"op": "setShowBanMessage", "value": True}, _setattr("show_ban_message", True)),
        Case("rare-seed", {"op": "setRareSeed", "value": 1234567890}, _setpath("gatya.rare_seed", 1234567890)),
        Case("normal-seed", {"op": "setNormalSeed", "value": 1234567891}, _setpath("gatya.normal_seed", 1234567891)),
        Case("event-seed", {"op": "setEventSeed", "value": 1234567892}, _setpath("gatya.event_seed", 1234567892)),
        Case("gamatoto-xp", {"op": "setGamatotoXp", "value": 100000}, _setpath("gamatoto.xp", 100000)),
        Case("challenge", {"op": "setChallengeScore", "value": 123456}, lambda s: (s.challenge.scores or s.challenge.scores.append(0), s.challenge.scores.__setitem__(0, 123456), setattr(s.challenge, "shown_popup", True), s.challenge.chapters.clear_stage(0, 0, 0, False))),
        Case("clear-tutorial", {"op": "clearTutorial"}, lambda s: (setattr(s, "tutorial_state", max(s.tutorial_state, 1)), setattr(s, "koreaSuperiorTreasureState", max(s.koreaSuperiorTreasureState, 2)), setattr(s, "ui6", max(s.ui6, 1)), s.new_dialogs_2.extend([0] * max(0, 6 - len(s.new_dialogs_2))), s.new_dialogs_2.__setitem__(1, max(s.new_dialogs_2[1], 2)), s.new_dialogs_2.__setitem__(5, max(s.new_dialogs_2[5], 2)), s.story.clear_stage(0, 0) if s.story.chapters[0].stages[0].clear_times == 0 else None)),
        Case("normal-tickets", {"op": "setNormalTickets", "value": 111}, _setattr("normal_tickets", 111)),
        Case("rare-tickets", {"op": "setRareTickets", "value": 112}, _setattr("rare_tickets", 112)),
        Case("platinum-tickets", {"op": "setPlatinumTickets", "value": 7}, _setattr("platinum_tickets", 7)),
        Case("legend-tickets", {"op": "setLegendTickets", "value": 3}, _setattr("legend_tickets", 3)),
        Case("platinum-shards", {"op": "setPlatinumShards", "value": platinum_shards_value}, _setattr("platinum_shards", platinum_shards_value)),
        Case("np", {"op": "setNp", "value": 116}, _setattr("np", 116)),
        Case("leadership", {"op": "setLeadership", "value": 117}, _setattr("leadership", 117)),
        Case("golden-cpu-reset", {"op": "resetGoldenCpuCount"}, _setattr("golden_cpu_count", 0)),
        Case("filibuster", {"op": "enableFilibusterStage", "args": [17]}, lambda s: (setattr(s, "filibuster_stage_id", 17), setattr(s, "filibuster_stage_enabled", True))),
        Case("fix-gamatoto", {"op": "fixGamatotoCrash"}, _setpath("gamatoto.skin", 2)),
    ]

    def cat_field(field: str, value: Any, op: str) -> Case:
        return Case(op, {"op": op, "args": [0, value]}, lambda s: setattr(s.cats.cats[0], field, value))

    out += [
        Case("cat-unlocked", {"op": "setCatUnlocked", "args": [0, True]}, lambda s: s.cats.cats[0].unlock(s)),
        cat_field("catguide_collected", True, "setCatGuideCollected"),
        Case("cat-base", {"op": "setCatBaseLevel", "args": [0, 5]}, lambda s: (core.PowerUpHelper(s.cats.cats[0], s).reset_upgrade(), core.PowerUpHelper(s.cats.cats[0], s).upgrade_by(4), s.cats.cats[0].set_upgrade(s, core.Upgrade(0, 4), True))),
        Case("cat-plus", {"op": "setCatPlusLevel", "args": [0, 3]}, lambda s: s.cats.cats[0].set_upgrade(s, core.Upgrade(3, -1), True)),
        Case("cat-form", {"op": "setCatCurrentForm", "args": [0, 1]}, lambda s: s.cats.cats[0].set_form(1, s)),
        Case("cat-forms", {"op": "setCatUnlockedForms", "args": [0, 3]}, lambda s: setattr(s.cats.cats[0], "unlocked_forms", 3)),
        Case("cat-fourth", {"op": "setCatFourthForm", "args": [0, 2]}, lambda s: setattr(s.cats.cats[0], "fourth_form", 2)),
        Case("cat-reset", {"op": "resetCat", "args": [0]}, lambda s: s.cats.cats[0].reset()),
        Case("special-base", {"op": "setSpecialSkillBaseLevel", "args": [0, 6]}, lambda s: s.special_skills.set_upgrade(0, core.Upgrade(-1, 5))),
        Case("special-plus", {"op": "setSpecialSkillPlusLevel", "args": [0, 4]}, lambda s: s.special_skills.set_upgrade(0, core.Upgrade(4, -1))),
        Case("all-cat-plus", {"op": "setAllCatPlusLevels", "args": [5]}, lambda s: [c.set_upgrade(s, core.Upgrade(5, -1), True) for c in s.cats.cats]),
        Case("all-cat-base", {"op": "setAllCatBaseLevels", "args": [10]}, lambda s: [(_upgrade_cat_base(s, c, 9)) for c in s.cats.cats]),
        Case("unlock-all-cats", {"op": "unlockAllCats"}, lambda s: [c.unlock(s) for c in s.cats.cats]),
        Case("unlock-obtainable-cats", {"op": "unlockAllObtainableCats"}, lambda s: [c.unlock(s) for c in s.cats.cats if c.id in {x.cat_id for x in (s.cats.read_nyanko_picture_book(s).get_obtainable_cats() or [])}]),
        Case("remove-all-cats", {"op": "removeAllCats"}, lambda s: [setattr(c, "unlocked", 0) for c in s.cats.cats]),
        Case("reset-all-cats", {"op": "resetAllCats"}, lambda s: [c.reset() for c in s.cats.cats]),
        Case("true-forms", {"op": "unlockTrueForms"}, lambda s: s.cats.true_form_cats(s, s.cats.cats)),
        Case("force-true-forms", {"op": "forceTrueForms"}, lambda s: s.cats.true_form_cats(s, s.cats.cats, True)),
        Case("remove-true-forms", {"op": "removeTrueForms"}, lambda s: [c.remove_true_form() for c in s.cats.cats]),
        Case("fourth-forms", {"op": "unlockFourthForms"}, lambda s: s.cats.fourth_form_cats(s, s.cats.cats)),
        Case("force-fourth-forms", {"op": "forceFourthForms"}, lambda s: s.cats.fourth_form_cats(s, s.cats.cats, True)),
        Case("remove-fourth-forms", {"op": "removeFourthForms"}, lambda s: [c.remove_fourth_form() for c in s.cats.cats]),
    ]
    # Exercise every serialized talent record, including legacy abilities that
    # are not present in the current compact data table.
    for cat in save.cats.cats:
        if not cat.talents:
            continue
        talent_index = 0
        talent = cat.talents[talent_index]
        out.append(Case(
            f"talent-cat-{cat.id}",
            {"op": "setCatTalentLevel", "args": [cat.id, talent_index, 1]},
            lambda s, cid=cat.id, tid=talent.id: (s.cats.cats[cid].unlock(s), setattr(s.cats.cats[cid].get_talent_from_id(tid), "level", 1)),
        ))
        if cat.id == next((c.id for c in save.cats.cats if c.talents), -1):
            out.append(Case(
                "talent-by-id",
                {"op": "setCatTalentLevelById", "args": [cat.id, talent.id, 1]},
                lambda s, cid=cat.id, tid=talent.id: (s.cats.cats[cid].unlock(s), setattr(s.cats.cats[cid].get_talent_from_id(tid), "level", 1)),
            ))

    if save.battle_items.items:
        out.append(Case("battle-item", {"op": "setBattleItem", "args": [0, 101]}, lambda s: setattr(s.battle_items.items[0], "amount", 101)))
    if save.catseyes:
        out.append(Case("catseye", {"op": "setCatseye", "args": [0, 101]}, lambda s: s.catseyes.__setitem__(0, 101)))
    if getattr(save.catamins, "catamins", []):
        out.append(Case("catamin", {"op": "setCatamin", "args": [0, 101]}, lambda s: setattr(s.catamins.catamins[0], "amount", 101)))
    if save.catfruit:
        out.append(Case("catfruit", {"op": "setCatfruit", "args": [0, 101]}, lambda s: s.catfruit.__setitem__(0, 101)))
    if save.treasure_chests:
        out.append(Case("treasure-chest", {"op": "setTreasureChest", "args": [0, 105]}, lambda s: s.treasure_chests.__setitem__(0, 105)))
    if save.labyrinth_medals:
        out.append(Case("labyrinth-medal", {"op": "setLabyrinthMedal", "args": [0, 106]}, lambda s: s.labyrinth_medals.__setitem__(0, 106)))
    if save.lucky_tickets:
        out.append(Case("lucky-ticket", {"op": "setLuckyTicket", "args": [0, 109]}, lambda s: s.lucky_tickets.__setitem__(0, 109)))
    if save.event_capsules:
        out.append(Case("event-ticket", {"op": "setEventTicket", "args": [0, 110]}, lambda s: s.event_capsules.__setitem__(0, 110)))
    if save.catfruit:
        out.append(Case("all-catfruit", {"op": "setAllCatfruit", "value": 123}, lambda s: s.catfruit.__setitem__(slice(None), [123] * len(s.catfruit))))
    if save.cats.storage_items:
        out += [
            Case("storage-item", {"op": "setStorageItem", "args": [0, 1, 7]}, lambda s: (setattr(s.cats.storage_items[0], "item_type", 1), setattr(s.cats.storage_items[0], "item_id", 7))),
            Case("storage-clear", {"op": "clearStorage"}, lambda s: [(setattr(x, "item_type", 0), setattr(x, "item_id", 0)) for x in s.cats.storage_items]),
            Case("storage-add-cat", {"op": "addStorageCat", "args": [7]}, lambda s: next((setattr(x, "item_type", 1) or setattr(x, "item_id", 7) for x in s.cats.storage_items if x.item_type == 0), None)),
            Case("storage-add-skill", {"op": "addStorageSpecialSkill", "args": [2]}, lambda s: next((setattr(x, "item_type", 2) or setattr(x, "item_id", 2) for x in s.cats.storage_items if x.item_type == 0), None)),
            Case("storage-add-cats", {"op": "addStorageCats", "args": [7, 2]}, lambda s: [next((setattr(x, "item_type", 1) or setattr(x, "item_id", 7) for x in s.cats.storage_items if x.item_type == 0), None) for _ in range(2)]),
            Case("storage-add-skills", {"op": "addStorageSpecialSkills", "args": [2, 2]}, lambda s: [next((setattr(x, "item_type", 2) or setattr(x, "item_id", 2) for x in s.cats.storage_items if x.item_type == 0), None) for _ in range(2)]),
        ]
        if any(x.item_type != 0 for x in save.cats.storage_items):
            out.append(Case("storage-remove", {"op": "removeOccupiedStorageItem", "args": [0]}, _remove_first_storage))

    out += [
        Case("gamatoto-level", {"op": "setGamatotoLevel", "value": 10}, lambda s: setattr(s.gamatoto, "xp", core.core_data.get_gamatoto_levels(s).get_xp_from_level(10))),
        Case("gamatoto-destination", {"op": "setGamatotoDestination", "value": 7}, _setpath("gamatoto.dest_id", 7)),
        Case("gamatoto-helper", {"op": "setGamatotoHelper", "args": [0, 1]}, lambda s: setattr(s.gamatoto.helpers.helpers[0], "id", 1)),
        Case("materials", {"op": "setBaseMaterial", "args": [0, 108]}, lambda s: setattr(s.ototo.base_materials.materials[0], "amount", 108)),
        Case("engineers", {"op": "setOtotoEngineers", "value": 5}, _setpath("ototo.engineers", 5)),
        Case("shrine-xp", {"op": "setCatShrineXp", "value": 100000}, _setpath("cat_shrine.xp_offering", 100000)),
        Case("shrine-dialogs", {"op": "setCatShrineDialogs", "value": 37}, _setpath("cat_shrine.dialogs", 37)),
        Case("lineup", {"op": "setLineupCat", "args": [0, 0, 12]}, lambda s: setattr(s.lineups.slots[0].slots[0], "cat_id", 12)),
        Case("story-clear", {"op": "setStoryClearTimes", "args": [0, 0, 2]}, lambda s: s.story.clear_stage(0, 0, 2)),
        Case("story-treasure", {"op": "setStoryTreasure", "args": [0, 0, 8]}, lambda s: setattr(s.story.chapters[0].stages[0], "treasure", 8)),
        Case("story-chapter-clear", {"op": "clearStoryChapter", "args": [0, True]}, lambda s: s.story.chapters[0].clear_chapter()),
        Case("story-chapter-treasures", {"op": "setStoryChapterTreasures", "args": [0, 7]}, lambda s: [setattr(st, "treasure", 7) for st in s.story.chapters[0].stages[:48]]),
        Case("timed-score", {"op": "setTimedScore", "args": [0, 0, 123]}, lambda s: setattr(s.story.chapters[4].stages[0], "itf_timed_score", 123)),
        Case("aku-progress", {"op": "setAkuProgress", "args": [10, 5]}, lambda s: [setattr(st, "clear_times", 5) for st in s.aku.chapters[0].chapters[0].stages[:10]]),
        Case("aku-clear-times", {"op": "setAkuClearTimes", "args": [0, 0, 0, 2]}, lambda s: setattr(s.aku.chapters[0].chapters[0].stages[0], "clear_times", 2)),
        Case("aku-clear-chapter", {"op": "clearAkuChapter", "args": [0, 0, True]}, lambda s: [setattr(st, "clear_times", 1) for st in s.aku.chapters[0].chapters[0].stages]),
        Case("enemy-all", {"op": "setAllEnemyGuide", "args": [True]}, lambda s: [s.enemy_guide.__setitem__(i, 1) for i in range(len(s.enemy_guide))]),
        Case("rank-all", {"op": "setAllUserRankRewards", "args": [True]}, lambda s: [setattr(x, "claimed", True) for x in s.user_rank_rewards.rewards]),
        Case("rare-trade", {"op": "tradeRareTickets", "args": [7]}, lambda s: (next((setattr(item, "item_id", 1) or setattr(item, "item_type", 2) for item in s.cats.storage_items if item.item_type == 0 or (item.item_type == 2 and item.item_id == 1)), None), setattr(s.gatya, "trade_progress", 35))),
        Case("outbreak", {"op": "setOutbreakCleared", "args": [0, 0, True]}, lambda s: s.outbreaks.clear_outbreak(0, 0, True)),
        Case("outbreak-chapter", {"op": "setOutbreakChapterCleared", "args": [0, True]}, lambda s: [setattr(st, "cleared", True) for st in next(iter(s.outbreaks.chapters.values())).outbreaks.values()]),
        Case("unlock-aku", {"op": "unlockAkuRealm"}, lambda s: [s.event_stages.clear_map(1, x, 0, False) for x in [255, 256, 257, 258, 265, 266, 268]]),
    ]
    if getattr(save, "user_rank_rewards", None) and save.user_rank_rewards.rewards:
        out.append(Case("rank-one", {"op": "setUserRankRewardClaimed", "args": [0, True]}, lambda s: setattr(s.user_rank_rewards.rewards[0], "claimed", True)))
        out.append(Case("rank-fix", {"op": "fixUserRankRewards"}, lambda s: _fix_rank_rewards(s)))
    if getattr(save, "missions", None) and save.missions.clear_states:
        mission_id = next(iter(save.missions.clear_states))
        out += [
            Case("mission-state", {"op": "setMissionClearState", "args": [mission_id, 2]}, lambda s, mid=mission_id: s.missions.clear_states.__setitem__(mid, 2)),
            Case("mission-requirement", {"op": "setMissionRequirement", "args": [mission_id, 7]}, lambda s, mid=mission_id: s.missions.requirements.__setitem__(mid, 7)),
        ]
        try:
            condition = core.core_data.get_mission_conditions(save).get_condition(mission_id)
            if condition is not None:
                out.append(Case("mission-completion", {"op": "setMissionCompletion", "args": [mission_id, 2]}, lambda s, mid=mission_id, target=condition.progress_count: (s.missions.clear_states.__setitem__(mid, 2), s.missions.requirements.__setitem__(mid, target))))
        except Exception:
            pass
    if getattr(save, "medals", None) is not None:
        out.append(Case("medal-add", {"op": "addMedal", "args": [126]}, lambda s: s.medals.add_medal(126)))
        if save.medals.medal_data_1:
            out.append(Case("medal-remove", {"op": "removeMedal", "args": [0]}, lambda s: s.medals.remove_medal(s.medals.medal_data_1[0])))
    if getattr(save, "talent_orbs", None) is not None:
        out.append(Case("orb-add", {"op": "addTalentOrb", "args": [321, 44]}, lambda s: s.talent_orbs.set_orb(321, 44)))
        if save.talent_orbs.orbs:
            orb_id = next(iter(save.talent_orbs.orbs))
            out.append(Case("orb-amount", {"op": "setTalentOrbAmount", "args": [0, 44]}, lambda s, oid=orb_id: setattr(s.talent_orbs.orbs[oid], "value", 44)))
            out.append(Case("orb-remove", {"op": "removeTalentOrb", "args": [0]}, lambda s, oid=orb_id: s.talent_orbs.orbs.pop(oid)))
    out += [
        Case("dojo-score", {"op": "setDojoScore", "value": 123}, lambda s: _set_dojo_score(s, 123)),
        Case("dojo-ranking", {"op": "setDojoRanking", "value": 7}, _setpath("dojo.ranking.ranking", 7)),
        Case("enigma-energy", {"op": "setEnigmaEnergy", "value": 777}, _setpath("enigma.energy_since_1", 777)),
        Case("enigma-level", {"op": "setEnigmaLevel", "value": 7}, _setpath("enigma.enigma_level", 7)),
        Case("gold-officer", {"op": "setGoldPassOfficerId", "value": 123}, _setpath("officer_pass.gold_pass.officer_id", 123)),
        Case("gold-renewals", {"op": "setGoldPassRenewals", "value": 2}, _setpath("officer_pass.gold_pass.total_renewal_times", 2)),
        Case("gold-date", {"op": "setGoldPassDate", "args": [0, 1700000000]}, _setpath("officer_pass.gold_pass.start_date_now", 1700000000.0)),
        Case("gold-state", {"op": "setGoldPassStateUpdates", "value": 2}, _setpath("officer_pass.gold_pass.total_state_updates", 2)),
        Case("gold-grant", {"op": "grantGoldPass", "args": [123, 1700000000, 30]}, _grant_gold_pass),
        Case("gold-remove", {"op": "removeGoldPass"}, lambda s: s.officer_pass.gold_pass.remove_gold_pass(s)),
        Case("fix-officer", {"op": "fixOfficerPass"}, lambda s: s.officer_pass.reset(s)),
    ]

    # Remaining public SaveDocument mutators.  These are intentionally built
    # from the parsed save rather than fixed offsets, so the same matrix works
    # with variable-length records and different account snapshots.
    if hasattr(save, "hundred_million_ticket"):
        out.append(Case("hundred-million-ticket", {"op": "setHundredMillionTicket", "value": 9}, _setattr("hundred_million_ticket", 9)))
    out.append(Case("unlock-equip-menu", {"op": "unlockEquipMenu"}, lambda s: s.unlock_equip_menu()))
    if save.cats.cats:
        out.append(Case("all-cat-guide", {"op": "setAllCatGuideCollected", "value": True}, lambda s: ([c.unlock(s) for c in s.cats.cats] if core.core_data.config.get_bool(core.ConfigKey.UNLOCK_CAT_ON_EDIT) else None, [setattr(c, "catguide_collected", True) for c in s.cats.cats])))
    if save.talents if hasattr(save, "talents") else False:
        pass
    if save.enemy_guide:
        out.append(Case("enemy-one", {"op": "setEnemyGuideUnlocked", "args": [0, True]}, lambda s: s.enemy_guide.__setitem__(0, 1)))
    if save.user_rank_rewards and save.user_rank_rewards.rewards:
        eligible = next((i for i,g in enumerate(core.core_data.get_rank_gifts(save).rank_gift or []) if g.threshold <= save.calculate_user_rank()), None)
        if eligible is not None:
            out.append(Case("rank-eligible", {"op": "setEligibleUserRankRewardClaimed", "args": [eligible, True]}, lambda s, i=eligible: setattr(s.user_rank_rewards.rewards[i], "claimed", True)))

    # Stage-map mutators: challenge map 0 is present in all supported saves;
    # use its first visible stage and configured crown count.  The upstream
    # chapter helpers implement the same clear-progress semantics.
    try:
        group = save.event_stages.chapters[0]
        sub = save.challenge.chapters.chapters[0].chapters[0]
        if sub.stages:
            out += [
                Case("stage-map-set", {"op": "setStageMapClearTimes", "args": ["CHALLENGE", 0, 0, 0, sub.stages[0].clear_times]}, lambda s: None),
                Case("stage-map-clear", {"op": "clearStageMap", "args": ["CHALLENGE", 0, True]}, lambda s: _clear_challenge(s, 4, True)),
                Case("stage-map-clear-crowns", {"op": "clearStageMap", "args": ["CHALLENGE", 0, True, 1]}, lambda s: _clear_challenge(s, 1, True)),
                Case("stage-maps-range", {"op": "clearStageMaps", "args": ["CHALLENGE", 0, 0, True]}, lambda s: _clear_challenge(s, 4, True)),
                Case("stage-maps-range-crowns", {"op": "clearStageMaps", "args": ["CHALLENGE", 0, 0, True, 1]}, lambda s: _clear_challenge(s, 1, True)),
                Case("stage-maps-configured", {"op": "clearStageMapsUpToConfiguredCrowns", "args": ["CHALLENGE", 0, 0, True, 1]}, lambda s: _clear_challenge(s, 1, True)),
            ]
    except Exception:
        pass

    if getattr(save.gamatoto, "helpers", None) is not None:
        amounts = [0, 0, 0, 0, 0]
        members_name = core.core_data.get_gamatoto_members_name(save)
        for h in save.gamatoto.helpers.helpers:
            member = members_name.get_member(h.id) if members_name is not None else None
            rarity = member.rarity if member is not None else -1
            if 0 <= rarity < 5: amounts[rarity] += 1
        out.append(Case("gamatoto-helper-rarities", {"op": "setGamatotoHelperRarityAmounts", "args": [amounts]}, lambda s: setattr(s.gamatoto.helpers, "helpers", [])))
    if getattr(save.ototo, "cannons", None) and save.ototo.cannons.cannons:
        cannon_ids = sorted(save.ototo.cannons.cannons)
        cannon_index = 1 if 1 in save.ototo.cannons.cannons else cannon_ids[0]
        cannon = save.ototo.cannons.cannons[cannon_index]
        out.append(Case("cannon-development", {"op": "setCannonDevelopment", "args": [cannon_index, cannon.development]}, lambda s: None))
        if cannon.levels:
            out.append(Case("cannon-part-level", {"op": "setCannonPartLevel", "args": [cannon_index, 0, cannon.levels[0]]}, lambda s: None))
    if getattr(save, "cat_shrine", None) is not None:
        out += [
            Case("shrine-gone", {"op": "setCatShrineGone", "value": save.cat_shrine.shrine_gone}, lambda s: None),
            Case("shrine-level", {"op": "setCatShrineLevel", "value": 1}, lambda s: setattr(s.cat_shrine, "xp_offering", 0)),
        ]
    if save.battle_items.items:
        out.append(Case("endless-item-off", {"op": "setEndlessBattleItem", "args": [0, False]}, lambda s: setattr(s.battle_items.items[0].endless_item, "active", False)))
        # The upstream and Android implementations both stamp wall-clock
        # seconds when enabling a duration.  The runner calibrates the
        # upstream model from the Android timestamp before hashing so this
        # otherwise nondeterministic operation remains byte-for-byte testable.
        out.append(Case("endless-duration", {"op": "setEndlessBattleDurationMinutes", "args": [0, 0]}, lambda s: None))
    if save.lineups and save.lineups.slots:
        out.append(Case("lineups-count", {"op": "setUnlockedLineups", "value": save.lineups.unlocked_slots}, lambda s: None))
        out.append(Case("restart-pack", {"op": "setRestartPackState", "value": 0}, lambda s: None))
    out += [
        Case("officer-cat-id", {"op": "setOfficerPassCatId", "value": -1}, lambda s: None),
        Case("officer-cat-form", {"op": "setOfficerPassCatForm", "value": 0}, lambda s: None),
    ]
    if save.enigma.stages:
        st = save.enigma.stages[0]
        out += [
            Case("enigma-stage-level", {"op": "setEnigmaStageLevel", "args": [0, st.level]}, lambda s: None),
            Case("enigma-stage-decoding", {"op": "setEnigmaStageDecoding", "args": [0, st.decoding_satus]}, lambda s: None),
            Case("enigma-stage-start", {"op": "setEnigmaStageStartTime", "args": [0, int(st.start_time)]}, lambda s: None),
            Case("enigma-stage-remove", {"op": "removeEnigmaStage", "args": [0]}, lambda s: s.enigma.stages.pop(0)),
        ]
    out += [
        Case("enigma-stage-add", {"op": "addEnigmaStage", "args": [29999, 1, 0, 1700000000]}, lambda s: s.enigma.stages.append(EnigmaStage(1, 29999, 0, 1700000000.0))),
        Case("enigma-active-add", {"op": "addActiveEnigmaStage", "args": [0, 1700000000]}, lambda s: s.enigma.stages.append(EnigmaStage(3, 25000, 2, 1700000000.0))),
        Case("enigma-active-clear", {"op": "clearActiveEnigmaStages"}, lambda s: setattr(s.enigma, "stages", [])),
    ]
    # Scheme and gambling tables are variable dictionaries.  Exercise each
    # operation with an existing id where possible, otherwise use a valid
    # deterministic id; upstream helpers preserve ordering and duplicates.
    valid_scheme = 3
    if valid_scheme is not None:
        out += [
            Case("scheme-add", {"op": "addSchemeItem", "args": [valid_scheme]}, lambda s, i=valid_scheme: (s.scheme_items.received.remove(i) if i in s.scheme_items.received else None, s.scheme_items.to_obtain.append(i) if i not in s.scheme_items.to_obtain else None)),
            Case("scheme-remove", {"op": "removeSchemeItem", "args": [valid_scheme]}, lambda s, i=valid_scheme: (s.scheme_items.to_obtain.remove(i) if i in s.scheme_items.to_obtain else None, s.scheme_items.received.remove(i) if i in s.scheme_items.received else None)),
        ]
    for table_name, table in (("WILDCAT_SLOTS", save.wildcat_slots), ("CAT_SCRATCHER", save.cat_scratcher)):
        if table.start_times:
            key = next(iter(table.start_times)); date = int(table.start_times[key])
            out += [
                Case(f"gambling-date-{table_name.lower()}", {"op": "setGamblingStartDate", "args": [table_name, 0, date]}, lambda s: None),
                Case(f"gambling-add-{table_name.lower()}", {"op": "addGamblingStart", "args": [table_name, key, date]}, lambda s: None),
                Case(f"gambling-remove-{table_name.lower()}", {"op": "removeGamblingStart", "args": [table_name, 0]}, lambda s, n=table_name.lower(): getattr(s, "wildcat_slots" if "wildcat" in n else "cat_scratcher").start_times.pop(next(iter(getattr(s, "wildcat_slots" if "wildcat" in n else "cat_scratcher").start_times)))),
            ]
        out.append(Case(f"gambling-reset-{table_name.lower()}", {"op": "resetGambling", "args": [table_name]}, lambda s, n=table_name.lower(): getattr(s, "wildcat_slots" if "wildcat" in n else "cat_scratcher").reset()))
    out += [
        Case("fix-time", {"op": "fixTimeErrors", "args": [1700000000]}, lambda s: (setattr(s, "date_3", __import__('datetime').datetime.fromtimestamp(1700000000)), setattr(s, "timestamp", 1700000000.0), setattr(s, "energy_penalty_timestamp", 1700000000.0))),
        Case("fix-ototo", {"op": "fixOtotoValues"}, lambda s: setattr(s.ototo, "cannons", Cannons.init(s.game_version))),
        Case("fix-officer-pass", {"op": "fixOfficerPass"}, lambda s: s.officer_pass.reset(s)),
    ]
    if any(c.talents for c in save.cats.cats):
        def _max_talents(s: Any) -> None:
            data = s.cats.read_talent_data(s)
            if data is None: return
            for cat in s.cats.cats:
                if not cat.talents: continue
                info = data.get_cat_talents(cat)
                if info is None: continue
                if core.core_data.config.get_bool(core.ConfigKey.UNLOCK_CAT_ON_EDIT):
                    cat.unlock(s)
                for tid, level in zip(info[3], info[1]):
                    talent = cat.get_talent_from_id(tid)
                    if talent is not None: talent.level = level
        out.append(Case("max-all-cat-talents", {"op": "maxAllCatTalents"}, _max_talents))
    return out


def curl(args: list[str], *, data: bytes | None = None, text: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(["curl", "--fail-with-body", "--silent", "--show-error", "--max-time", "60", *args], input=data, capture_output=True, text=text, check=True)


def run_case(src: Path, case: Case, api: str, out_dir: Path) -> dict[str, Any]:
    raw = src.read_bytes()
    # Upstream CoreData caches drop/game tables.  A CLI invocation starts with
    # a fresh cache for each edit; reset it here so cases cannot influence the
    # expected bytes of later cases.
    core.core_data.init_data()
    upstream = SaveFile(Data(raw), core.CountryCode.from_code("tw"))
    try:
        case.mutate(upstream)
        expected = upstream.to_data().get_bytes()
    except Exception as exc:
        return {"case": case.name, "status": "upstream-error", "error": repr(exc)}
    try:
        curl(["-X", "POST", api + "/import", "--data-binary", "@" + str(src)])
        edit = curl(["-X", "POST", api + "/edit", "-H", "Content-Type: application/json", "-d", json.dumps(case.request)], text=True)
    except subprocess.CalledProcessError as exc:
        body = exc.stderr.decode(errors="replace") if isinstance(exc.stderr, bytes) else str(exc.stderr)
        return {"case": case.name, "status": "android-error", "error": body or repr(exc)}
    # Keep only the compact JSONL report; exported saves are temporary and can
    # be half a megabyte each for a single case.
    out = out_dir / (src.name + "-" + case.name + ".tmp.save")
    try:
        curl([api + "/export", "-o", str(out)])
    except subprocess.CalledProcessError as exc:
        body = exc.stderr.decode(errors="replace") if isinstance(exc.stderr, bytes) else str(exc.stderr)
        return {"case": case.name, "status": "android-export-error", "error": body or repr(exc)}
    actual = out.read_bytes()
    try:
        out.unlink()
    except OSError:
        pass
    if case.name == "endless-duration":
        # Align only the timestamp fields written by the operation; all other
        # bytes still come from the independently mutated upstream object.
        calibrated = SaveFile(Data(raw), core.CountryCode.from_code("tw"))
        android_saved = SaveFile(Data(actual), core.CountryCode.from_code("tw"))
        src_item = calibrated.battle_items.items[0].endless_item
        dst_item = android_saved.battle_items.items[0].endless_item
        src_item.active, src_item.unknown = dst_item.active, dst_item.unknown
        src_item.amount, src_item.start, src_item.end = dst_item.amount, dst_item.start, dst_item.end
        expected = calibrated.to_data().get_bytes()
    eh = hashlib.sha256(expected).hexdigest()
    ah = hashlib.sha256(actual).hexdigest()
    result = {"case": case.name, "status": "ok" if expected == actual else "mismatch", "expected": eh, "actual": ah, "length": len(actual), "response": edit.stdout.decode(errors="replace") if isinstance(edit.stdout, bytes) else edit.stdout}
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("save", type=Path, nargs="+")
    parser.add_argument("--api", default="http://127.0.0.1:18765")
    parser.add_argument("--case", action="append", dest="only")
    parser.add_argument("--prefix", action="append", default=[], help="run case names beginning with this prefix")
    parser.add_argument("--out", type=Path, default=Path("artifacts/api-diff"))
    args = parser.parse_args()
    core.set_config_path(core.Path("/tmp/bcsfe-test-config.json"))
    core.core_data.init_data()
    args.out.mkdir(parents=True, exist_ok=True)
    failures = 0
    for source in args.save:
        parsed = SaveFile(Data(source.read_bytes()), core.CountryCode.from_code("tw"))
        cases = cases_for(parsed)
        if args.only:
            cases = [c for c in cases if c.name in set(args.only)]
        if args.prefix:
            cases = [c for c in cases if any(c.name.startswith(prefix) for prefix in args.prefix)]
        log = args.out / (source.name + ".jsonl")
        with log.open("a", encoding="utf-8") as stream:
            for case in cases:
                result = run_case(source, case, args.api, args.out)
                stream.write(json.dumps(result, ensure_ascii=False) + "\n")
                print(source.name, case.name, result["status"], result.get("expected", ""), result.get("actual", ""))
                if result["status"] != "ok":
                    failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
