# 用途：验证 CodeQL SARIF 相关位置裁剪只去掉超额项并保留告警指纹。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_trim_codeql_sarif_related_locations.py。
import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "trim-codeql-sarif-related-locations.py"
SPEC = importlib.util.spec_from_file_location("trim_codeql_sarif_related_locations", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def location(index: int) -> dict:
    return {"id": index, "physicalLocation": {"artifactLocation": {"uri": "A.java"}}}


class TrimCodeqlSarifRelatedLocationsTest(unittest.TestCase):
    def test_keeps_first_hundred_and_fingerprint(self):
        document = {
            "runs": [
                {
                    "results": [
                        {
                            "ruleId": "java/csrf-unprotected-request-type",
                            "partialFingerprints": {"primaryLocationLineHash": "abc"},
                            "relatedLocations": [location(index) for index in range(128)],
                        }
                    ]
                }
            ]
        }
        removed = MODULE.trim_sarif(document)
        related = document["runs"][0]["results"][0]["relatedLocations"]
        self.assertEqual(28, removed)
        self.assertEqual(100, len(related))
        self.assertEqual(0, related[0]["id"])
        self.assertEqual(99, related[-1]["id"])
        self.assertEqual(
            "abc",
            document["runs"][0]["results"][0]["partialFingerprints"]["primaryLocationLineHash"],
        )

    def test_leaves_short_results_unchanged(self):
        document = {"runs": [{"results": [{"relatedLocations": [location(1)]}, {}]}]}
        self.assertEqual(0, MODULE.trim_sarif(document))
        self.assertEqual(1, len(document["runs"][0]["results"][0]["relatedLocations"]))
        self.assertNotIn("relatedLocations", document["runs"][0]["results"][1])
