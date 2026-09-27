#!/usr/bin/env python3
"""校验脚本目录入口和 CI 守护清单，避免新增脚本成为隐形入口。"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
GATE_CODE = "SCRIPT_GOVERNANCE"
GATE_NAME = "脚本治理"
DIRECT_SCRIPT_CALL = re.compile(r'^\s*"?\$ROOT/(scripts/[A-Za-z0-9_./-]+\.sh)"?(?:\s|$)')


def main() -> int:
    errors: list[str] = []
    root_index = (SCRIPTS / "README.md").read_text(encoding="utf-8")
    for directory in sorted(path for path in SCRIPTS.iterdir() if path.is_dir()):
        if not (directory / "README.md").is_file():
            errors.append(f"missing script directory index: scripts/{directory.name}/README.md")
        if f"{directory.name}/" not in root_index:
            errors.append(f"scripts/README.md does not mention directory: {directory.name}/")

    ci_index = (SCRIPTS / "ci" / "README.md").read_text(encoding="utf-8")
    guards = sorted(
        path.name
        for path in (SCRIPTS / "ci").iterdir()
        if path.is_file()
        and path.suffix in {".py", ".sh"}
        and path.name.startswith(("check-", "validate-"))
    )
    for guard in guards:
        if guard not in ci_index:
            errors.append(f"CI guard is not registered in scripts/ci/README.md: {guard}")

    direct_scripts: set[str] = set()
    for caller in SCRIPTS.rglob("*.sh"):
        for line in caller.read_text(encoding="utf-8").splitlines():
            match = DIRECT_SCRIPT_CALL.match(line)
            if match:
                direct_scripts.add(match.group(1))
    for script in sorted(direct_scripts):
        result = subprocess.run(
            ["git", "ls-files", "--stage", "--", script],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        entries = [line for line in result.stdout.splitlines() if line.endswith(f"\t{script}")]
        if not entries:
            errors.append(f"directly invoked script is not tracked by Git: {script}")
        elif any(line.split(" ", 1)[0] != "100755" for line in entries):
            errors.append(f"directly invoked script must have Git mode 100755: {script}")

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(
        f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} "
        f"| guards={len(guards)} direct-executable={len(direct_scripts)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
