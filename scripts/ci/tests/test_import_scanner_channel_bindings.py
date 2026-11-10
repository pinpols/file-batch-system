#!/usr/bin/env python3
# 用途：验证 Import 扫描归属的 Helm 迁移提示和 Compose 配置透传。
# 用法：python3 -m unittest scripts/ci/tests/test_import_scanner_channel_bindings.py
"""渲染 Helm 与 Compose 配置，不连接或修改运行环境。"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]
PREFIX = "BATCH_WORKER_IMPORT_SCANNER_CHANNELBINDINGS_"
MANIFEST_PREFIX = "BATCH_WORKER_IMPORT_SCANNER_MANIFESTBINDINGS_"


def render_helm(*settings):
    command = ["helm", "template", "channel-test", "helm/batch-platform"]
    for setting in settings:
        command.extend(["--set-string", setting])
    result = subprocess.run(command, cwd=ROOT, check=True, text=True, capture_output=True)
    return [doc for doc in yaml.safe_load_all(result.stdout) if doc and doc.get("kind") == "Deployment"]


@unittest.skipUnless(shutil.which("helm"), "需要 Helm 才能验证模板渲染")
class HelmImportScannerChannelBindingsTest(unittest.TestCase):
    def test_default_has_no_binding_environment_variables(self):
        for deployment in render_helm():
            env = deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])
            self.assertFalse(any(item["name"].startswith((PREFIX, MANIFEST_PREFIX)) for item in env))

    def test_legacy_binding_values_fail_before_deploy(self):
        bindings = {
            "scannerChannelBindings": (
                "tenantId=tenant-a", "objectPrefix=ingress/tenant-a/bank/", "channelCode=BANK-SFTP"),
            "scannerManifestBindings": (
                "tenantId=tenant-a", "objectPrefix=ingress/tenant-a/bank/", "fileGroupCode=settlement",
                r"manifestKeyTemplate=manifests/settlement-\{bizDate\}.batch.json"),
        }
        for field, settings in bindings.items():
            with self.subTest(field=field):
                command = ["helm", "template", "channel-test", "helm/batch-platform",
                           *[option for setting in settings
                             for option in ("--set-string", f"workerImport.{field}[0].{setting}")]]
                result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(field, result.stderr)
                self.assertIn("import-ingress-ownership", result.stderr)

    def test_legacy_bindings_fail_even_if_import_workers_scaled_to_zero(self):
        command = ["helm", "template", "channel-test", "helm/batch-platform",
                   "--set", "workerImport.replicaCount=0",
                   "--set-string", "workerImport.scannerChannelBindings[0].tenantId=tenant-a"]
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("import-ingress-ownership", result.stderr)

    def test_empty_bindings_allow_shared_tenant_and_pool_import_workers(self):
        deployments = render_helm(
            "tenantWorkerPools[0].tenantId=tenant-a",
            "tenantWorkerPools[0].workerType=import",
            "workerResourcePools[0].name=special",
            "workerResourcePools[0].workerType=import",
        )
        import_components = set()
        for deployment in deployments:
            component = deployment["metadata"]["labels"].get("app.kubernetes.io/component", "")
            env = deployment["spec"]["template"]["spec"]["containers"][0].get("env", [])
            self.assertFalse(any(item["name"].startswith((PREFIX, MANIFEST_PREFIX)) for item in env))
            if component.startswith("worker-import"):
                import_components.add(component)
        self.assertEqual(len(import_components), 3)


@unittest.skipUnless(shutil.which("docker"), "需要 Docker Compose 才能验证渲染")
class ComposeImportScannerChannelBindingsTest(unittest.TestCase):
    def test_optional_spring_json_is_limited_to_import(self):
        sample = {"batch": {"worker": {"import": {"scanner": {
            "prefix": "ingress/tenant-a/bank/",
            "poll-interval-millis": 2000,
            "arrival": {"enabled": True},
        }}}}}
        services = self.render_compose(json.dumps(sample))
        self.assertIn("worker-import", services)
        for name, service in services.items():
            env = service.get("environment", {})
            if name == "worker-import":
                self.assertEqual(json.loads(env["SPRING_APPLICATION_JSON"]), sample)
            else:
                self.assertNotIn("SPRING_APPLICATION_JSON", env, name)
        defaults = self.render_compose(None)
        self.assertEqual(defaults["worker-import"]["environment"]["SPRING_APPLICATION_JSON"], "")

    @staticmethod
    def render_compose(value):
        files = ["docker-compose.yml", "deploy/docker/compose/app.yml"]
        contents = "\n".join((ROOT / path).read_text() for path in files)
        env = {key: os.environ[key] for key in ("PATH", "HOME") if key in os.environ}
        for key in re.findall(r"\$\{([A-Z0-9_]+):\?", contents):
            env[key] = "fixture"
        if value is not None:
            env["WORKER_IMPORT_SPRING_APPLICATION_JSON"] = value
        command = ["docker", "compose", "--profile", "apps", "--profile", "replica",
                   "--env-file", ".env.example", "-f", files[0], "-f", files[1],
                   "config", "--format", "json"]
        result = subprocess.run(command, cwd=ROOT, env=env, check=True, text=True, capture_output=True)
        return json.loads(result.stdout)["services"]


if __name__ == "__main__":
    unittest.main()
