#!/usr/bin/env python3
# 用途：验证 Worker 平台库独立凭据的 Helm 渲染：默认沿用共享账号，配置后各 Worker 只覆盖自己的平台库账号。
# 用法：python3 -m unittest scripts/ci/tests/test_helm_worker_platform_db_credentials.py
"""渲染真实 Chart，校验 ARCH-20261103-01 的分账号与回退两种配置，不访问 Kubernetes。"""

from pathlib import Path
import shutil
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKERS = {
    "worker-import": "workerImport",
    "worker-export": "workerExport",
    "worker-process": "workerProcess",
    "worker-dispatch": "workerDispatch",
}
CREDENTIAL_KEYS = ("BATCH_PLATFORM_DB_USERNAME", "BATCH_PLATFORM_DB_PASSWORD")


def render(*overrides: str) -> list[dict]:
    command = ["helm", "template", "creds", "helm/batch-platform", "--namespace", "creds"]
    for override in overrides:
        command += ["--set", override]
    rendered = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
    return [document for document in yaml.safe_load_all(rendered.stdout) if document]


def container_env(documents: list[dict]) -> dict[str, dict[str, dict]]:
    environments = {}
    for document in documents:
        if document.get("kind") != "Deployment":
            continue
        component = document["metadata"]["labels"].get("app.kubernetes.io/component")
        container = document["spec"]["template"]["spec"]["containers"][0]
        environments[component] = {entry["name"]: entry for entry in container.get("env", [])}
    return environments


@unittest.skipUnless(shutil.which("helm"), "需要本地 Helm 才能渲染 Chart")
class HelmWorkerPlatformDbCredentialsTest(unittest.TestCase):
    def test_default_render_keeps_shared_platform_account(self):
        environments = container_env(render())
        for component in WORKERS:
            for key in CREDENTIAL_KEYS:
                self.assertNotIn(key, environments[component], component)

    def test_each_worker_reads_only_its_own_credentials_secret(self):
        overrides = [f"{values}.platformDb.credentialsSecretName={component}-db"
                     for component, values in WORKERS.items()]
        environments = container_env(render(*overrides))
        for component in WORKERS:
            for key in CREDENTIAL_KEYS:
                reference = environments[component][key]["valueFrom"]["secretKeyRef"]
                self.assertEqual(reference, {"name": f"{component}-db", "key": key}, component)
        for component, environment in environments.items():
            if component not in WORKERS:
                for key in CREDENTIAL_KEYS:
                    self.assertNotIn(key, environment, component)

    def test_single_worker_override_leaves_other_workers_shared(self):
        environments = container_env(render("workerProcess.platformDb.credentialsSecretName=proc-db"))
        self.assertIn("BATCH_PLATFORM_DB_USERNAME", environments["worker-process"])
        for component in set(WORKERS) - {"worker-process"}:
            self.assertNotIn("BATCH_PLATFORM_DB_USERNAME", environments[component], component)


if __name__ == "__main__":
    unittest.main()
