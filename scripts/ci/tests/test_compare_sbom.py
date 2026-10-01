from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "compare-sbom.py"
SPEC = importlib.util.spec_from_file_location("compare_sbom", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def sbom(version: str = "1.0", reverse: bool = False) -> dict:
    components = [
        {
            "type": "library",
            "group": "example",
            "name": "alpha",
            "version": version,
            "purl": f"pkg:maven/example/alpha@{version}",
            "licenses": [{"license": {"id": "Apache-2.0"}}],
            "hashes": [{"alg": "SHA-256", "content": "abc"}],
            "description": "不参与依赖语义比较",
        },
        {
            "type": "library",
            "group": "example",
            "name": "beta",
            "version": "2.0",
            "purl": "pkg:maven/example/beta@2.0",
        },
    ]
    if reverse:
        components.reverse()
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "timestamp": "2026-10-01T00:00:00Z",
            "component": {
                "type": "application",
                "group": "example",
                "name": "root",
                "version": "1.0",
                "purl": "pkg:maven/example/root@1.0",
            },
        },
        "components": components,
        "dependencies": [
            {"ref": "root", "dependsOn": ["beta", "alpha"]},
            {"ref": "alpha", "dependsOn": []},
        ],
    }


class CompareSbomTest(unittest.TestCase):
    def test_ignores_order_and_display_metadata(self) -> None:
        left = sbom()
        right = sbom(reverse=True)
        right["metadata"]["timestamp"] = "2026-10-02T00:00:00Z"
        right["components"][1]["description"] = "另一环境生成的描述"
        right["dependencies"].reverse()
        right["dependencies"][1]["dependsOn"].reverse()

        self.assertEqual(MODULE.canonicalize(left), MODULE.canonicalize(right))

    def test_detects_dependency_version_change(self) -> None:
        self.assertNotEqual(
            MODULE.canonicalize(sbom()),
            MODULE.canonicalize(sbom(version="1.1")),
        )


if __name__ == "__main__":
    unittest.main()
