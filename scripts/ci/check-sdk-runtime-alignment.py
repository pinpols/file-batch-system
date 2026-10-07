#!/usr/bin/env python3
"""Keep SDK and paired frontend runtime declarations aligned with CI coverage."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT.parent / "batch-console"
errors: list[str] = []


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"cannot read {path.relative_to(ROOT)}: {exc}")
        return ""


typescript_package = json.loads(read(ROOT / "sdk/typescript/package.json"))
require(typescript_package.get("engines", {}).get("node") == "^22 || ^24", "TypeScript SDK engines.node must support Node 22 and 24 only")
require((ROOT / "sdk/typescript/package-lock.json").is_file(), "TypeScript SDK development dependencies must be lockfile-backed")

node_baseline = read(ROOT / ".node-version").strip()
require(bool(re.fullmatch(r"24\.\d+\.\d+", node_baseline)), "repository .node-version must pin an exact Node 24 release")
require(read(ROOT / ".nvmrc").strip() == node_baseline, "repository .nvmrc must match .node-version exactly")

# CI 中的前端仓库单独检出。仅在本地同时存在两个仓库时执行此处校验；
# 前端自身 CI 仍是最终验证依据。
if FRONTEND.is_dir():
    frontend_package = json.loads(read(FRONTEND / "package.json"))
    require(frontend_package.get("engines", {}).get("node") == "^24", "frontend engines.node must require Node 24")
    require("engine-strict=true" in read(FRONTEND / ".npmrc"), "frontend npm must enforce the declared Node engine")
    frontend_node_baseline = read(FRONTEND / ".node-version").strip()
    require(
        bool(re.fullmatch(r"24\.\d+\.\d+", frontend_node_baseline)),
        "frontend .node-version must pin an exact Node 24 release",
    )
    require(
        read(FRONTEND / ".nvmrc").strip() == frontend_node_baseline,
        "frontend .nvmrc must match .node-version exactly",
    )
    require(frontend_node_baseline == node_baseline, "frontend and backend Node execution baselines must match")
    frontend_docker_node = re.search(
        r"^FROM node:(24\.\d+\.\d+)-alpine\d+\.\d+@sha256:[0-9a-f]{64} AS build$",
        read(FRONTEND / "Dockerfile"),
        re.M,
    )
    require(frontend_docker_node is not None, "frontend Docker build image must pin Node 24 tag and digest")
    if frontend_docker_node is not None:
        require(
            frontend_docker_node.group(1) == frontend_node_baseline,
            "frontend Docker Node release must match .node-version",
        )

go_files = (ROOT / "sdk/go/go.mod", ROOT / "sdk/go/kafka/go.mod")
for path in go_files:
    require(bool(re.search(r"^go 1\.26\.0$", read(path), re.M)), f"{path.relative_to(ROOT)} must declare Go 1.26.0")

python_manifest = read(ROOT / "sdk/python/pyproject.toml")
require('requires-python = ">=3.12"' in python_manifest, "Python SDK minimum must remain Python 3.12")
require('"Programming Language :: Python :: 3.14"' in python_manifest, "Python SDK must advertise Python 3.14")
require((ROOT / "sdk/python/uv.lock").is_file(), "Python SDK development dependencies must be lockfile-backed")

contract_workflow = read(ROOT / ".github/workflows/sdk-contract-parity.yml")
python_workflow = read(ROOT / ".github/workflows/sdk-python.yml")
e2e_workflow = read(ROOT / ".github/workflows/sdk-orchestrator-e2e.yml")
require("python-version: ['3.12', '3.14']" in contract_workflow, "SDK contract CI must test Python 3.12 and 3.14")
require("uv sync --locked --extra dev" in contract_workflow, "SDK contract CI must install the Python SDK from uv.lock")
require("node-version: ['22', '24']" in contract_workflow, "SDK contract CI must test Node 22 and 24")
require("npm ci --ignore-scripts" in contract_workflow, "TypeScript SDK CI must install from package-lock.json")
require("go-version: ['1.26.x', '1.27.x']" in contract_workflow, "SDK contract CI must test Go 1.26 and 1.27")
require("python-version: ['3.12', '3.14']" in python_workflow, "Python SDK CI must test Python 3.12 and 3.14")
require("uv sync --locked --extra dev" in python_workflow, "Python SDK CI must install from uv.lock")
require("go-version: '1.27.x'" in e2e_workflow, "SDK orchestrator E2E must use current Go 1.27")
require("python-version: '3.14'" in e2e_workflow, "SDK orchestrator E2E must use current Python 3.14")
require("node-version: '24'" in e2e_workflow, "SDK orchestrator E2E must use current Node 24")
rust_manifest = read(ROOT / "sdk/rust/Cargo.toml")
rust_policy = read(ROOT / "docs/architecture/runtime-compatibility-contract-2026-09-01.md")
rust_workflow = read(ROOT / ".github/workflows/sdk-contract-parity.yml")
rust_toolchain = read(ROOT / "rust-toolchain.toml")
require('rust-version = "1.75"' in rust_manifest, "Rust core MSRV must remain explicit in Cargo.toml")
require("Rust 1.88+" in rust_policy, "Rust adapter toolchain floor must be documented from the locked dependency tree")
require("cargo +1.75.0 test --locked" in rust_workflow, "Rust core MSRV must be exercised in SDK contract CI")
require(bool(re.search(r'^channel = "\d+\.\d+\.\d+"$', rust_toolchain, re.M)), "repository Rust baseline must pin an exact release")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(f"FAIL: {len(errors)} SDK runtime alignment issue(s)", file=sys.stderr)
    raise SystemExit(1)

print("PASS: SDK and frontend runtime declarations match the documented support matrix")
