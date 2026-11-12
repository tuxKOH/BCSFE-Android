#!/usr/bin/env python3
"""Text/JSON control of the actual Battle Cats Android game, including native UI.

Only adb is mandatory. Native screen recognition additionally needs rapidocr,
onnxruntime and pillow. Private save access uses run-as on a debuggable QA copy;
it never clears app data or silently replaces an installed player's save.
"""
from __future__ import annotations

import argparse
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
from xml.etree import ElementTree as ET

PACKAGES = {r: "jp.co.ponos.battlecats" + (r if r != "jp" else "")
            for r in ("jp", "tw", "en", "kr")}
# Labels are matched against the current screen, never tapped blindly.
ACTIONS = {
    "ok": ["OK", "確定", "確認", "はい", "同意する", "同意"],
    "cancel": ["キャンセル", "取消", "Cancel", "いいえ"],
    "retry": ["請重試", "重試", "リトライ", "Retry", "再試行"],
    "start": ["ゲームスタート", "開始遊戲", "遊戲開始", "开始游戏", "Start Game", "Play"],
    "settings": ["オプション", "設定", "選項", "设置", "Options", "Settings"],
    "transfer": ["機種変更", "アカウント・機種変更", "機種変更手続き", "轉移設定", "資料轉移", "帳號/機種變更", "機種變更手續", "Data Transfer"],
    "receive": ["引き継ぎ", "新しい端末でデータを受け取る", "接收", "繼續資料轉移", "在此裝置接收舊遊戲資料", "Resume Data Transfer"],
    "receive-submit": ["引継ぎ開始", "開始轉移", "Resume Data Transfer"],
    "title": ["回主標題畫面", "タイトルへ戻る", "タイトル画面に戻る", "Return to Title"],
    "upload": ["データをサーバーへ保存", "開始資料轉移", "Begin Data Transfer"],
    "base": ["ネコ基地", "貓咪基地", "Cat Base"],
    "battle": ["戦闘開始", "出陣", "開始戰鬥", "Start Battle", "Battle"],
    "upgrade": ["パワーアップ", "升級", "升级", "Upgrade"],
    "equip": ["キャラクター編成", "角色編成", "角色编成", "Equip"],
    "gacha": ["にゃんこガチャ", "貓咪轉蛋", "Rare Cat Capsule", "Cat Capsule"],
    "rare-gacha": ["レアガチャ", "稀有轉蛋", "Rare Cat Capsule"],
    "shop": ["アイテムショップ", "道具商店", "Item Shop"],
    "missions": ["ミッション", "任務", "任务", "Missions"],
    "rewards": ["ユーザーランク", "使用者等級", "User Rank"],
    "gamatoto": ["ガマトト", "加碼多多", "GAMATOTO"],
    "ototo": ["オトート", "貓咪城開發隊", "Ototo"],
    "guide": ["にゃんこ図鑑", "貓咪圖鑑", "Cat Guide"],
    "treasures": ["お宝", "寶物", "Treasures"],
    "menu": ["メニュー", "選單", "Menu"],
    "back": ["戻る", "返回", "Back"],
}


def public(text):
    """Do not echo native inquiry codes, JWTs or labeled transfer credentials."""
    text = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b", "[redacted-token]", text)
    text = re.sub(r"(?i)\b[0-9a-f]{9}\b", "[redacted-code]", text)
    return re.sub(r"(?i)((?:inquiry|transfer|confirmation|password|token|pin|お問い合わせ|引継ぎ|詢問碼|轉移碼)\s*[:：=]\s*)\S+", r"\1[redacted]", text)


def emit(value):
    print(json.dumps(value, ensure_ascii=False, indent=2))


class Game:
    def __init__(self, args):
        self.args = args
        self.package = args.package or PACKAGES[args.region] + (".bcsfeqa" if args.qa else "")
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", self.package):
            raise ValueError("Invalid Android package name")
        self.adb = args.adb or shutil.which("adb") or str(Path.home() / "Android/Sdk/platform-tools/adb")
        devices = self.call("devices").decode()
        connected = re.findall(r"^([^\s]+)\s+device$", devices, re.M)
        self.serial = args.serial
        if not self.serial:
            if len(connected) != 1:
                raise ValueError("Specify --serial; connected devices: " + ", ".join(connected))
            self.serial = connected[0]
        if not self.serial.startswith("emulator-") and not args.allow_device:
            raise ValueError("This QA tool defaults to emulators; use --allow-device for a physical device")
        self.engine = None

    def call(self, *args, data=None, timeout=30):
        cmd = [self.adb] + (["-s", self.serial] if getattr(self, "serial", None) else []) + list(args)
        r = subprocess.run(cmd, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if r.returncode:
            raise RuntimeError(public(r.stderr.decode(errors="replace").strip() or "adb command failed"))
        return r.stdout

    def shell(self, *args, **kwargs):
        return self.call("shell", *args, **kwargs).decode(errors="replace").strip()

    def status(self):
        info = self.shell("dumpsys", "package", self.package)
        activity = self.shell("dumpsys", "activity", "activities")
        top = re.search(r"(?:topResumedActivity|mResumedActivity).*? ([\w.]+/[\w.$]+)", activity)
        version = re.search(r"versionName=([^\s]+)", info)
        pid = self.pid() if "versionName=" in info else ""
        return {"serial": self.serial, "package": self.package, "installed": bool(version),
                "version": version[1] if version else None, "pid": pid or None,
                "foreground": top[1] if top else None}

    def pid(self):
        return self.shell("sh", "-c", shlex.quote("pidof " + self.package + " || true"))

    def screen(self, ocr=True):
        nodes = []
        sensitive_bounds = []
        remote = "/sdcard/bcsfe-game-cli-window.xml"
        try:
            self.shell("rm", "-f", remote)
            self.shell("uiautomator", "dump", remote, timeout=12)
            root = ET.fromstring(self.call("shell", "-T", "cat", remote))
            for n in root.iter("node"):
                sensitive = n.get("password") == "true" or (
                    "EditText" in n.get("class", "") and re.search(
                        r"(?i)(account|pin|password|transfer|token)", n.get("resource-id", "")))
                text = n.get("text") or n.get("content-desc")
                b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
                if len(b) != 4 or b[2] <= b[0] or b[3] <= b[1]:
                    continue
                if sensitive:
                    sensitive_bounds.append(b)
                if not text:
                    continue
                nodes.append({"text": "[redacted]" if sensitive else public(text), "bounds": b, "source": "android",
                              "clickable": n.get("clickable") == "true"})
        except (RuntimeError, subprocess.TimeoutExpired, ET.ParseError):
            pass
        if ocr:
            try:
                from rapidocr import RapidOCR
                from PIL import Image
                import numpy as np
            except ImportError as e:
                raise RuntimeError("Install tools/battlecats-requirements.txt for native game text recognition") from e
            if self.engine is None:
                self.engine = RapidOCR(params={"EngineConfig.onnxruntime.intra_op_num_threads": 2,
                                               "EngineConfig.onnxruntime.inter_op_num_threads": 1})
            raw = self.call("exec-out", "screencap", "-p")
            result = self.engine(np.array(Image.open(io.BytesIO(raw)).convert("RGB")))
            if result.boxes is not None:
                for box, text, score in zip(result.boxes, result.txts, result.scores):
                    cx, cy = box[:, 0].mean(), box[:, 1].mean()
                    if any(x <= cx <= X and y <= cy <= Y for x, y, X, Y in sensitive_bounds):
                        text = "[redacted]"
                    nodes.append({"text": public(text), "bounds": [int(box[:, 0].min()), int(box[:, 1].min()),
                                  int(box[:, 0].max()), int(box[:, 1].max())], "source": "game-ocr",
                                  "confidence": round(float(score), 3)})
        return nodes

    def tap(self, query, index=None, exact=False, android_only=False):
        nodes = self.screen(not android_only)
        normalize = lambda s: re.sub(r"\s+", "", s).casefold()
        q = normalize(query)
        matches = [n for n in nodes if normalize(n['text']) == q]
        if not matches and not exact:
            matches = [n for n in nodes if q in normalize(n['text'])]
        # Android + OCR may describe the same button; prefer Android bounds.
        unique = []
        for n in matches:
            x, y, X, Y = n['bounds']
            if not any(abs((x+X)-(m['bounds'][0]+m['bounds'][2])) < 60 and
                       abs((y+Y)-(m['bounds'][1]+m['bounds'][3])) < 60 for m in unique):
                unique.append(n)
        if not unique:
            raise ValueError("Text not visible: " + public(query))
        if index is None and len(unique) > 1:
            raise ValueError("Ambiguous text; specify --index (zero based): " + public(query))
        n = unique[index or 0]
        x, y, X, Y = n['bounds']
        self.shell("input", "tap", str((x+X)//2), str((y+Y)//2))
        return n

    def action(self, name):
        if name == "android-back":
            self.shell("input", "keyevent", "4")
            return {"action": name}
        nodes = self.screen()
        for label in ACTIONS[name]:
            for n in nodes:
                if re.sub(r"\s+", "", label).casefold() == re.sub(r"\s+", "", n['text']).casefold():
                    x, y, X, Y = n['bounds']
                    self.shell("input", "tap", str((x+X)//2), str((y+Y)//2))
                    return {"action": name, "matched": n}
        raise ValueError("Action is not visible; use screen/tap for this version: " + name)

    def private(self, command, data=None):
        return self.call("shell", "-T", "run-as", self.package, "sh", "-c", shlex.quote(command), data=data)

    def pull_save(self, path):
        raw = self.private("cat files/SAVE_DATA")
        if len(raw) < 48:
            raise ValueError("Game save is missing or too short")
        path = Path(path)
        with path.open("xb") as f:
            os.chmod(path, 0o600)
            f.write(raw)
        return {"saved_to": str(path), "bytes": len(raw)}

    def load_save(self, source, backup, initialize=False):
        raw = Path(source).read_bytes()
        if len(raw) < 48:
            raise ValueError("Input is not a save")
        # Stop before backup: the game must not rewrite the file mid-copy.
        self.shell("am", "force-stop", self.package)
        if self.private("if [ -f files/SAVE_DATA ]; then echo present; fi").strip():
            self.pull_save(backup)
        elif initialize:
            with Path(backup).open("x") as f:
                os.chmod(backup, 0o600)
                f.write('{"SAVE_DATA": "absent before initialization"}\n')
        else:
            raise ValueError("No existing SAVE_DATA; --initialize is required for a new QA install")
        self.private("mkdir -p files && cat > files/SAVE_DATA.cli-new && chmod 600 files/SAVE_DATA.cli-new && "
                     "mv files/SAVE_DATA.cli-new files/SAVE_DATA && rm -f files/SAVE_DATA.OLD", data=raw)
        if self.private("cat files/SAVE_DATA") != raw:
            raise RuntimeError("Save write verification failed; restore the backup before launching")
        return {"loaded": str(source), "backup": str(backup), "bytes": len(raw)}

    def launch(self):
        component = self.shell("cmd", "package", "resolve-activity", "--brief", self.package).splitlines()[-1]
        if "/" not in component:
            raise ValueError("No launcher activity for " + self.package)
        self.shell("am", "start", "-n", component)

    def crashes(self):
        # Only the crash buffer, scoped to this package; no account/network log dump.
        text = self.call("logcat", "-d", "-b", "crash", "-v", "threadtime", "-t", "1500").decode(errors="replace")
        chunks = re.split(r"(?=^.*(?:\*\*\* \*\*\*|FATAL EXCEPTION))", text, flags=re.M)
        return public("\n".join(c for c in chunks if self.package in c))

    def exit_info(self):
        return public(self.shell("dumpsys", "activity", "exit-info", self.package))

    def monitor(self, duration):
        deadline = time.monotonic() + duration
        started = False
        while time.monotonic() < deadline:
            pid = self.pid()
            if pid:
                started = True
            elif started:
                return {"process_alive": False, "crash": self.crashes(), "exit_info": self.exit_info()}
            time.sleep(.5)
        return {"process_alive": bool(self.pid()), "duration": duration, "status": self.status(),
                "exit_info": self.exit_info() if not self.pid() else None}

    def run_script(self, path):
        steps = json.loads(Path(path).read_text())
        if not isinstance(steps, list):
            raise ValueError("A script must be an array of steps")
        for i, step in enumerate(steps):
            if len(step) != 1:
                raise ValueError("Each step must contain one command")
            command, value = next(iter(step.items()))
            if command == "action": self.action(value)
            elif command == "tap": self.tap(value)
            elif command == "key": self.shell("input", "keyevent", str(value))
            elif command == "sleep": time.sleep(min(max(float(value), 0), 30))
            elif command == "wait":
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    if any(value.casefold() in n['text'].casefold() for n in self.screen()): break
                    time.sleep(.5)
                else: raise ValueError("Timed out at step " + str(i))
            else: raise ValueError("Unknown script operation: " + command)
            # Do not echo values (scripts can include private field labels).
            emit({"step": i, "command": command, "completed": True})


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial"); p.add_argument("--adb"); p.add_argument("--region", choices=PACKAGES, default="jp")
    p.add_argument("--package"); p.add_argument("--qa", action="store_true")
    p.add_argument("--allow-device", action="store_true")
    sub = p.add_subparsers(dest="command", required=True)
    for name in ("status", "launch", "stop", "actions", "crashes", "exit-info"):
        sub.add_parser(name)
    s = sub.add_parser("screen"); s.add_argument("--android-only", action="store_true")
    s = sub.add_parser("tap"); s.add_argument("query"); s.add_argument("--index", type=int); s.add_argument("--exact", action="store_true"); s.add_argument("--android-only", action="store_true")
    s = sub.add_parser("action"); s.add_argument("name", choices=[*ACTIONS, "android-back"])
    s = sub.add_parser("key"); s.add_argument("code")
    s = sub.add_parser("text"); g=s.add_mutually_exclusive_group(required=True); g.add_argument("--file", type=Path); g.add_argument("--value")
    s = sub.add_parser("tapxy"); s.add_argument("x", type=int); s.add_argument("y", type=int)
    s = sub.add_parser("swipe"); s.add_argument("coords", type=int, nargs=4); s.add_argument("--ms", type=int, default=300)
    s = sub.add_parser("screenshot"); s.add_argument("path", type=Path)
    s = sub.add_parser("pull-save"); s.add_argument("path", type=Path)
    s = sub.add_parser("load-save"); s.add_argument("path", type=Path); s.add_argument("--backup", type=Path, required=True); s.add_argument("--initialize", action="store_true")
    s = sub.add_parser("monitor"); s.add_argument("--seconds", type=float, default=20)
    s = sub.add_parser("wait"); s.add_argument("query"); s.add_argument("--seconds", type=float, default=30)
    s = sub.add_parser("run-script"); s.add_argument("path", type=Path)
    a = p.parse_args()
    if a.command == "actions":
        emit(ACTIONS); return
    game = Game(a)
    if a.command == "status": emit(game.status())
    elif a.command == "screen": emit(game.screen(not a.android_only))
    elif a.command == "tap": emit(game.tap(a.query, a.index, a.exact, a.android_only))
    elif a.command == "action": emit(game.action(a.name))
    elif a.command == "launch": game.launch(); emit(game.status())
    elif a.command == "stop": game.shell("am", "force-stop", game.package)
    elif a.command == "key": game.shell("input", "keyevent", a.code)
    elif a.command == "text":
        value = a.file.read_text().strip() if a.file else a.value
        game.shell("input", "text", shlex.quote(value.replace(" ", "%s")))
    elif a.command == "tapxy": game.shell("input", "tap", str(a.x), str(a.y))
    elif a.command == "swipe": game.shell("input", "swipe", *map(str, a.coords), str(a.ms))
    elif a.command == "screenshot":
        with a.path.open("xb") as f: os.chmod(a.path, 0o600); f.write(game.call("exec-out", "screencap", "-p"))
    elif a.command == "pull-save": emit(game.pull_save(a.path))
    elif a.command == "load-save": emit(game.load_save(a.path, a.backup, a.initialize))
    elif a.command == "crashes": emit({"crash": game.crashes()})
    elif a.command == "exit-info": emit({"exit_info": game.exit_info()})
    elif a.command == "monitor": emit(game.monitor(a.seconds))
    elif a.command == "run-script": game.run_script(a.path)
    elif a.command == "wait":
        deadline = time.monotonic() + a.seconds
        while time.monotonic() < deadline:
            if any(a.query.casefold() in n['text'].casefold() for n in game.screen()):
                emit({"found": public(a.query)}); break
            time.sleep(.5)
        else: raise ValueError("Timed out waiting for visible text")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.TimeoutExpired, OSError) as e:
        print(public(str(e)), file=sys.stderr)
        sys.exit(1)
