#!/usr/bin/env python3
"""校验脚本目录入口和 CI 守护清单，避免新增脚本成为隐形入口。"""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"


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

    if errors:
        print("Script governance guard failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"Script governance guard passed: {len(guards)} CI guards registered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
