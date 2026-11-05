#!/usr/bin/env python3
# 用途：验证控制面平台库独立凭据的 Helm 渲染：默认沿用共享账号，配置后运行时账号改用独立 Secret，迁移仍用 owner 账号。
# 用法：python3 -m unittest scripts/ci/tests/test_helm_control_plane_platform_db_credentials.py
"""渲染真实 Chart，校验 ARCH-20261103-01 控制面的分账号与回退两种配置，不访问 Kubernetes。"""

from pathlib import Path
import shutil
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]
RELEASE = "creds"
CONTROL_PLANE = {
    "console-api": "consoleApi",
    "trigger": "trigger",
    "orchestrator": "orchestrator",
}
MIGRATING = ("trigger", "orchestrator")
RUNTIME_KEYS = ("BATCH_PLATFORM_DB_USERNAME", "BATCH_PLATFORM_DB_PASSWORD")
CONSOLE_KEYS = {
    "BATCH_CONSOLE_PRIMARY_USER": "BATCH_PLATFORM_DB_USERNAME",
    "BATCH_CONSOLE_PRIMARY_PASSWORD": "BATCH_PLATFORM_DB_PASSWORD",
    "BATCH_CONSOLE_REPLICA_USER": "BATCH_PLATFORM_DB_USERNAME",
    "BATCH_CONSOLE_REPLICA_PASSWORD": "BATCH_PLATFORM_DB_PASSWORD",
}
FLYWAY_KEYS = ("BATCH_PLATFORM_FLYWAY_USERNAME", "BATCH_PLATFORM_FLYWAY_PASSWORD")


def render(*overrides: str) -> dict[str, dict[str, dict]]:
    command = ["helm", "template", RELEASE, "helm/batch-platform", "--namespace", RELEASE]
    for override in overrides:
        command += ["--set", override]
    rendered = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
    environments = {}
    for document in yaml.safe_load_all(rendered.stdout):
        if not document or document.get("kind") not in ("Deployment", "StatefulSet"):
            continue
        component = document["metadata"]["labels"].get("app.kubernetes.io/component")
        container = document["spec"]["template"]["spec"]["containers"][0]
        environments[component] = {entry["name"]: entry for entry in container.get("env", [])}
    return environments


def secret_ref(entry: dict) -> dict:
    return entry["valueFrom"]["secretKeyRef"]


@unittest.skipUnless(shutil.which("helm"), "需要本地 Helm 才能渲染 Chart")
class HelmControlPlanePlatformDbCredentialsTest(unittest.TestCase):
    def test_default_render_keeps_shared_platform_account(self):
        environments = render()
        for component in CONTROL_PLANE:
            for key in (*RUNTIME_KEYS, *CONSOLE_KEYS, *FLYWAY_KEYS):
                self.assertNotIn(key, environments[component], component)

    def test_each_module_uses_its_own_runtime_secret_and_owner_for_migrations(self):
        overrides = [f"{values}.platformDb.credentialsSecretName={component}-db"
                     for component, values in CONTROL_PLANE.items()]
        environments = render(*overrides)
        for component in CONTROL_PLANE:
            for key in RUNTIME_KEYS:
                self.assertEqual(secret_ref(environments[component][key]),
                                 {"name": f"{component}-db", "key": key}, component)
        for component in MIGRATING:
            environment = environments[component]
            self.assertEqual(environment["BATCH_PLATFORM_FLYWAY_USERNAME"]["valueFrom"]["configMapKeyRef"],
                             {"name": f"{RELEASE}-batch-platform-config", "key": "BATCH_PLATFORM_DB_USERNAME"})
            self.assertEqual(secret_ref(environment["BATCH_PLATFORM_FLYWAY_PASSWORD"]),
                             {"name": f"{RELEASE}-batch-platform-secret", "key": "BATCH_PLATFORM_DB_PASSWORD"})
        console = environments["console-api"]
        for key, source in CONSOLE_KEYS.items():
            self.assertEqual(secret_ref(console[key]), {"name": "console-api-db", "key": source}, key)
        for key in FLYWAY_KEYS:
            self.assertNotIn(key, console, "Console 不执行 Flyway")

    def test_single_module_override_leaves_others_shared(self):
        environments = render("trigger.platformDb.credentialsSecretName=trigger-db")
        self.assertIn("BATCH_PLATFORM_FLYWAY_USERNAME", environments["trigger"])
        for component in set(CONTROL_PLANE) - {"trigger"}:
            for key in (*RUNTIME_KEYS, *FLYWAY_KEYS):
                self.assertNotIn(key, environments[component], component)


if __name__ == "__main__":
    unittest.main()
