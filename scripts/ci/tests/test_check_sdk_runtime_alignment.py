# 用途：验证前端 Docker 构建镜像与 SDK 运行时版本对齐守卫。
# 用法：python3 -m unittest scripts/ci/tests/test_check_sdk_runtime_alignment.py。
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BACKEND_FILES = (
    "scripts/ci/check-sdk-runtime-alignment.py",
    "sdk/typescript/package.json",
    "sdk/typescript/package-lock.json",
    "examples/self-hosted-sdk/sample-tenant-worker-typescript/package.json",
    ".node-version",
    ".nvmrc",
    "sdk/go/go.mod",
    "sdk/go/kafka/go.mod",
    "sdk/python/pyproject.toml",
    "sdk/python/uv.lock",
    ".github/workflows/sdk-contract-parity.yml",
    ".github/workflows/sdk-python.yml",
    ".github/workflows/sdk-orchestrator-e2e.yml",
    "sdk/rust/Cargo.toml",
    "docs/architecture/runtime-compatibility-contract.md",
    "rust-toolchain.toml",
)


class SdkRuntimeAlignmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.backend = root / "file-batch-system"
        self.frontend = root / "batch-console"
        self.frontend.mkdir()
        for name in BACKEND_FILES:
            destination = self.backend / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)
        self.node_version = (self.backend / ".node-version").read_text(encoding="utf-8").strip()
        (self.frontend / "package.json").write_text(
            json.dumps({"engines": {"node": "^24"}}), encoding="utf-8"
        )
        (self.frontend / ".npmrc").write_text("engine-strict=true\n", encoding="utf-8")
        for name in (".node-version", ".nvmrc"):
            (self.frontend / name).write_text(self.node_version + "\n", encoding="utf-8")

    def run_checker(self, from_line):
        (self.frontend / "Dockerfile").write_text(from_line + "\n", encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(self.backend / "scripts/ci/check-sdk-runtime-alignment.py")],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_accepts_pinned_node_image_with_and_without_platform(self):
        for flag in ("", "--platform=$BUILDPLATFORM "):
            with self.subTest(flag=flag):
                result = self.run_checker(
                    f"FROM {flag}node:{self.node_version}-alpine3.24@sha256:{'a' * 64} AS build"
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_node_release_mismatch_even_with_platform(self):
        mismatch = "24.0.1" if self.node_version == "24.0.0" else "24.0.0"
        result = self.run_checker(
            f"FROM --platform=$BUILDPLATFORM node:{mismatch}-alpine3.24@sha256:{'a' * 64} AS build"
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("frontend Docker Node release must match .node-version", result.stderr)

    def test_rejects_wrong_image_or_missing_digest(self):
        for image in (
            f"custom-node:{self.node_version}-alpine3.24@sha256:{'a' * 64}",
            f"node:{self.node_version}-alpine3.24",
            f"node:{self.node_version}-alpine3.24@sha256:{'a' * 63}",
            f"node:22.0.0-alpine3.24@sha256:{'a' * 64}",
        ):
            with self.subTest(image=image):
                result = self.run_checker(f"FROM --platform=$BUILDPLATFORM {image} AS build")
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("frontend Docker build image must pin Node 24 tag and digest", result.stderr)


if __name__ == "__main__":
    unittest.main()
