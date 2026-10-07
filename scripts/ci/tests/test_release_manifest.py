from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts" / "deploy" / "release_manifest.py"
SPEC = importlib.util.spec_from_file_location("release_manifest", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
release_manifest = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_manifest)


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = json.loads(
            (ROOT / "deploy" / "release" / "release-manifest.example.json").read_text(
                encoding="utf-8"
            )
        )

    def test_example_manifest_is_valid_and_renders_all_backend_services(self) -> None:
        release_manifest.validate_manifest(self.manifest)

        rendered = release_manifest.render_compose(self.manifest)

        for service in release_manifest.BACKEND_SERVICES:
            self.assertIn(f"  {service}:\n", rendered)
            self.assertIn(self.manifest["images"][service], rendered)
        self.assertNotIn("  frontend:\n", rendered)

    def test_mutable_image_tag_is_rejected(self) -> None:
        self.manifest["images"]["console-api"] = "ghcr.io/pinpols/console-api:latest"

        with self.assertRaisesRegex(release_manifest.ManifestError, "images.console-api"):
            release_manifest.validate_manifest(self.manifest)

    def test_missing_service_is_rejected(self) -> None:
        del self.manifest["images"]["worker-atomic"]

        with self.assertRaisesRegex(release_manifest.ManifestError, "worker-atomic"):
            release_manifest.validate_manifest(self.manifest)

    def test_schema_closed_world_and_rfc3339_time_are_enforced(self) -> None:
        self.manifest["unexpected"] = True
        self.manifest["createdAt"] = "2026-10-07"

        with self.assertRaises(release_manifest.ManifestError) as error:
            release_manifest.validate_manifest(self.manifest)

        self.assertIn("未知顶层字段", str(error.exception))
        self.assertIn("createdAt", str(error.exception))

    def test_backend_fragment_uses_bake_target_digests(self) -> None:
        digest = "sha256:" + "a" * 64
        metadata = {
            service: {"containerimage.digest": digest}
            for service in release_manifest.BACKEND_SERVICES
        }
        bake_plan = {
            "target": {
                service: {"tags": [f"ghcr.io/example/{service}:sha-test"]}
                for service in release_manifest.BACKEND_SERVICES
            }
        }

        fragment = release_manifest.backend_fragment(metadata, bake_plan, "b" * 40)

        self.assertEqual("BackendImageSet", fragment["kind"])
        self.assertEqual("b" * 40, fragment["gitSha"])
        self.assertEqual(
            f"ghcr.io/example/worker-import@{digest}",
            fragment["images"]["worker-import"],
        )

    def test_cli_writes_backend_fragment(self) -> None:
        digest = "sha256:" + "c" * 64
        metadata = {
            service: {"containerimage.digest": digest}
            for service in release_manifest.BACKEND_SERVICES
        }
        bake_plan = {
            "target": {
                service: {"tags": [f"ghcr.io/pinpols/{service}:sha-test"]}
                for service in release_manifest.BACKEND_SERVICES
            }
        }
        with tempfile.TemporaryDirectory() as directory:
            metadata_path = Path(directory) / "metadata.json"
            output_path = Path(directory) / "backend.json"
            metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
            result = release_manifest.backend_fragment(metadata, bake_plan, "d" * 40)
            release_manifest.write_json(output_path, result)

            written = json.loads(output_path.read_text(encoding="utf-8"))

        self.assertEqual(result, written)


if __name__ == "__main__":
    unittest.main()
