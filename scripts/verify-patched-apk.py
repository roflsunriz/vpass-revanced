#!/usr/bin/env python3
"""Verify that the Vpass ReVanced patches were applied to an APK.

Requires Android SDK build-tools (dexdump) and Python 3.10+.

Checks:
  * every TamperAction callback body is a single return-void
  * BaseActivity.onCreate forces the Settings.Global.getInt result to 0
  * the VpassApplication tamper getter returns false
  * the VpassApplication constructor was not replaced
  * exactly one of the two kh startup initializers is disabled
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

TAMPER_CLASS = "Lcom/smbc_card/vpass/TamperAction;"
APPLICATION_CLASS = "Lcom/smbc_card/vpass/VpassApplication;"
BASE_ACTIVITY_CLASS = "Lcom/smbc_card/vpass/ui/BaseActivity;"
SPLASH_ACTIVITY_CLASS = "Lcom/smbc_card/vpass/ui/splash/SplashActivity;"

CLASS_RE = re.compile(r"Class descriptor\s+:\s+'(.*)'")
NAME_RE = re.compile(r"name\s+:\s+'(.*)'")
TYPE_RE = re.compile(r"type\s+:\s+'(.*)'")
INSN_RE = re.compile(r"^\s*[0-9a-f]+:\s+[0-9a-f ]+\s+\|([0-9a-f]+):\s+(.*?)(?:\s+//.*)?$")


@dataclass
class DexModel:
    methods: dict[tuple[str, str, str], list[str]] = field(default_factory=dict)


def parse_dexdump(text: str) -> DexModel:
    model = DexModel()
    current_class: str | None = None
    current_name: str | None = None
    current_type: str | None = None
    current_instructions: list[str] = []

    def flush() -> None:
        nonlocal current_instructions
        if current_class and current_name and current_type and current_instructions:
            model.methods[(current_class, current_name, current_type)] = current_instructions
        current_instructions = []

    for line in text.splitlines():
        class_match = CLASS_RE.search(line)
        if class_match:
            flush()
            current_class = class_match.group(1)
            current_name = None
            current_type = None
            continue
        name_match = NAME_RE.search(line)
        if name_match and line.lstrip().startswith("name"):
            flush()
            current_name = name_match.group(1)
            continue
        type_match = TYPE_RE.search(line)
        if type_match and line.lstrip().startswith("type"):
            current_type = type_match.group(1)
            continue
        insn_match = INSN_RE.match(line)
        if insn_match:
            current_instructions.append(insn_match.group(2).strip())
    flush()
    return model


def find_dexdump() -> Path:
    candidates: list[Path] = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if root:
            candidates.extend(sorted(Path(root).glob("build-tools/*/dexdump*"), reverse=True))
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidates.extend(
            sorted(Path(local_app_data).glob("Android/Sdk/build-tools/*/dexdump*"), reverse=True)
        )
    executable = shutil.which("dexdump")
    if executable:
        candidates.append(Path(executable))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise SystemExit(
        "dexdump が見つかりません。ANDROID_HOME を設定するか、Android SDK build-tools を PATH に追加してください。"
    )


def extract_dex(apk: Path, directory: Path) -> list[Path]:
    with zipfile.ZipFile(apk) as archive:
        names = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        paths = []
        for name in names:
            target = directory / name
            target.write_bytes(archive.read(name))
            paths.append(target)
    return sorted(paths)


def load_model(apk: Path, dexdump: Path) -> DexModel:
    model = DexModel()
    with tempfile.TemporaryDirectory() as temp:
        for dex in extract_dex(apk, Path(temp)):
            result = subprocess.run(
                [str(dexdump), "-d", str(dex)],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                check=False,
            )
            if result.returncode != 0:
                raise SystemExit(f"dexdump が失敗しました: {dex.name}: {result.stderr.strip()}")
            model.methods.update(parse_dexdump(result.stdout).methods)
    return model


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()

    if not args.apk.is_file():
        raise SystemExit(f"APK が見つかりません: {args.apk}")

    model = load_model(args.apk, find_dexdump())
    errors: list[str] = []

    tamper_methods = {
        key: value
        for key, value in model.methods.items()
        if key[0] == TAMPER_CLASS and not key[1].startswith("<")
    }
    if not tamper_methods:
        errors.append("TamperAction のコールバックが見つかりません")
    for (_, name, _), instructions in sorted(tamper_methods.items()):
        if instructions != ["return-void"]:
            errors.append(f"TamperAction.{name} が return-void ではありません: {instructions[:4]}")

    base_on_create = model.methods.get((BASE_ACTIVITY_CLASS, "onCreate", "(Landroid/os/Bundle;)V"))
    if base_on_create is None:
        errors.append("BaseActivity.onCreate が見つかりません")
    else:
        get_int = next(
            (i for i, text in enumerate(base_on_create) if "Settings$Global;.getInt" in text),
            None,
        )
        if get_int is None:
            errors.append("BaseActivity.onCreate に Settings.Global.getInt がありません")
        else:
            window = base_on_create[get_int : get_int + 6]
            move = next((text for text in window if text.startswith("move-result ")), None)
            if move is None:
                errors.append("getInt の move-result が見つかりません")
            else:
                register = move.split()[1]
                forced = False
                for text in window:
                    const = re.match(r"const/(?:4|16) (v\d+), #int (-?\d+)", text)
                    if const and const.group(1) == register and int(const.group(2)) == 0:
                        forced = True
                branched = any(
                    text.startswith("if-eqz ") and text.split(",")[0].endswith(register)
                    for text in window
                )
                if not forced:
                    errors.append(f"getInt の戻り値 {register} が 0 に固定されていません")
                if not branched:
                    errors.append(f"{register} に対する if-eqz が見つかりません")

    getter_name = None
    if base_on_create:
        for text in base_on_create:
            match = re.search(r"Lcom/smbc_card/vpass/VpassApplication;\.(.*):\(\)Z", text)
            if match:
                getter_name = match.group(1)
                break
    if getter_name is None:
        errors.append("BaseActivity.onCreate に VpassApplication の改ざんフラグ getter 呼び出しがありません")
    else:
        getter = model.methods.get((APPLICATION_CLASS, getter_name, "()Z"))
        if getter is None:
            errors.append(f"VpassApplication.{getter_name} が見つかりません")
        elif not (
            len(getter) == 2
            and re.match(r"const/(?:4|16) v\d+, #int 0$", getter[0])
            and re.match(r"return v\d+$", getter[1])
        ):
            errors.append(f"VpassApplication.{getter_name} が false を返しません: {getter[:4]}")

    constructor = model.methods.get((APPLICATION_CLASS, "<init>", "()V"))
    if constructor is not None and constructor == ["return-void"]:
        errors.append("VpassApplication.<init> が return-void に置換されています")

    splash = model.methods.get((SPLASH_ACTIVITY_CLASS, "onCreate", "(Landroid/os/Bundle;)V"))
    if splash is None:
        errors.append("SplashActivity.onCreate が見つかりません")
    else:
        initializers = []
        for text in splash:
            match = re.search(
                r"invoke-static(?:/range)? \{[^}]*\}, (Lkh/[^.]*)\.([^:(]+):?\(\)V$",
                text,
            )
            if match:
                initializers.append((match.group(1), match.group(2)))
        bodies = []
        for class_name, method_name in initializers:
            body = model.methods.get((class_name, method_name, "()V"))
            if body is not None:
                bodies.append((class_name, method_name, body))
        disabled = [item for item in bodies if item[2] == ["return-void"]]
        active = [item for item in bodies if item[2] != ["return-void"]]
        if not disabled:
            errors.append("DexGuard の署名検証初期化が無効化されていません")
        if not active:
            errors.append("kh の初期化をすべて無効化しています。文字列復号が壊れる可能性があります")

    if errors:
        print(f"FAIL {args.apk}")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(
        f"PASS {args.apk}: {len(tamper_methods)} tamper callbacks disabled, "
        f"adb_enabled forced to 0, {getter_name} returns false, signature initializer disabled"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
