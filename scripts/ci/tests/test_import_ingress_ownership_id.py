#!/usr/bin/env python3
# 用途：验证归属管理脚本输出与现有扫描身份兼容。
# 用法：python3 -m unittest scripts.ci.tests.test_import_ingress_ownership_id

import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/db/import-ingress-ownership-id.py"


class ImportIngressOwnershipIdentityTest(unittest.TestCase):
    def test_scanner_compatible_identity_without_printing_endpoint(self) -> None:
        env = os.environ.copy()
        env["BATCH_S3_ENDPOINT"] = "http://minio:9000/"
        result = subprocess.run(
            ["python3", str(SCRIPT), "--bucket", "batch-test", "--prefix", "ingress/acme/",
             "--namespace", "prod", "--backend", "s3"],
            check=True, text=True, capture_output=True, env=env,
        )
        self.assertIn(
            "storage_id=72bb968aa9645ac8b2eab5f4482750af13add07c70e36f70ea1490ee844b0cad",
            result.stdout,
        )
        self.assertNotIn("minio", result.stdout)
        self.assertIn("scope_id=", result.stdout)

    def test_s3_endpoint_is_required(self) -> None:
        env = os.environ.copy()
        env.pop("BATCH_S3_ENDPOINT", None)
        result = subprocess.run(
            ["python3", str(SCRIPT), "--bucket", "batch-test", "--endpoint", ""],
            text=True, capture_output=True, env=env,
        )
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
