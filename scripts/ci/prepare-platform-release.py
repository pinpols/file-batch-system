#!/usr/bin/env python3
# 用途：核对双仓版本并计算下一统一版本及 Release 准备参数。
# 用法：python3 scripts/ci/prepare-platform-release.py --frontend-root <前端仓库路径> --bump patch|minor|major；其余参数见 --help。
"""为后端和配对 Console 准备相同的下一 SemVer 版本。"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION_PATTERN = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def read_backend_version(backend_root: Path) -> str:
    pom_root = ET.parse(backend_root / "pom.xml").getroot()
    version = pom_root.findtext("m:properties/m:revision", namespaces=POM_NAMESPACE)
    if not version:
        raise ValueError("backend pom.xml has no root <revision>")
    return version.strip()


def calculate_next_version(current: str, bump: str) -> str:
    match = VERSION_PATTERN.fullmatch(current)
    if not match:
        raise ValueError(f"current release version must be stable SemVer X.Y.Z: {current}")
    major, minor, patch = map(int, match.groups())
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def read_frontend_version(frontend_root: Path) -> str:
    package = json.loads((frontend_root / "package.json").read_text())
    lock = json.loads((frontend_root / "package-lock.json").read_text())
    manifest = json.loads((frontend_root / ".platform-release-manifest.json").read_text())
    versions = {
        "package.json": package.get("version"),
        "package-lock.json": lock.get("version"),
        "package-lock.json packages['']": lock.get("packages", {}).get("", {}).get("version"),
        ".platform-release-manifest.json": manifest.get("."),
    }
    if len(set(versions.values())) != 1 or not next(iter(versions.values())):
        raise ValueError(f"frontend version files disagree: {versions}")
    return str(package["version"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frontend-root", required=True, type=Path)
    parser.add_argument("--bump", required=True, choices=("patch", "minor", "major"))
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()

    backend_root = Path(__file__).resolve().parents[2]
    frontend_root = args.frontend_root.resolve()
    try:
        backend_version = read_backend_version(backend_root)
        frontend_version = read_frontend_version(frontend_root)
        if backend_version != frontend_version:
            raise ValueError(
                "backend and frontend versions must match before release preparation "
                f"(backend={backend_version}, frontend={frontend_version})"
            )
        subprocess.run(
            ["npm", "run", "check:version", "--", backend_version],
            cwd=frontend_root,
            check=True,
        )

        release_version = calculate_next_version(backend_version, args.bump)
        if args.github_output:
            with args.github_output.open("a") as output:
                output.write(f"release_version={release_version}\n")
        subprocess.run(
            ["bash", "scripts/ci/bump-version.sh", release_version],
            cwd=backend_root,
            check=True,
        )
        print(f"Prepared coordinated release {backend_version} -> {release_version}")
        print(f"Backend: {backend_root}")
        print(f"Frontend: {frontend_root}")
    except (
        OSError,
        ValueError,
        subprocess.CalledProcessError,
        ET.ParseError,
        json.JSONDecodeError,
    ) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
