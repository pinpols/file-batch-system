#!/usr/bin/env python3
# 用途：验证 Helm 双 Release 的监控目标隔离与维护范围。
# 用法：python3 -m unittest scripts/ci/tests/test_helm_service_monitor_scope.py
"""渲染真实 Chart, 校验 ServiceMonitor 选择集合, 不访问 Kubernetes。"""

from pathlib import Path
import shutil
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(shutil.which("helm"), "需要本地 Helm 才能渲染 Chart")
class HelmServiceMonitorScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.documents = {}
        for release in ("audit-blue", "audit-green"):
            command = [
                "helm", "template", release, "helm/batch-platform", "--namespace", "audit",
                "-f", "helm/batch-platform/examples/values-local-k8s.yaml",
                "--set", f"fullnameOverride={release}", "--set", "serviceMonitor.enabled=true",
                "--set", "prometheusRule.enabled=true",
            ]
            rendered = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
            cls.documents[release] = [doc for doc in yaml.safe_load_all(rendered.stdout) if doc]

    def test_monitors_match_only_services_from_their_release(self):
        for release, documents in self.documents.items():
            monitors = [doc for doc in documents if doc["kind"] == "ServiceMonitor"]
            self.assertGreaterEqual(len(monitors), 8)
            for monitor in monitors:
                selector = monitor["spec"]["selector"]["matchLabels"]
                matches = []
                for owner, resources in self.documents.items():
                    for service in resources:
                        if service["kind"] == "Service" and all(
                            service["metadata"].get("labels", {}).get(key) == value
                            for key, value in selector.items()
                        ):
                            matches.append(owner)
                self.assertTrue(matches, monitor["metadata"]["name"])
                self.assertEqual(set(matches), {release}, monitor["metadata"]["name"])

    def test_app_targets_and_console_use_the_same_scope(self):
        for release, documents in self.documents.items():
            expected_scope = f"audit.{release}"
            app_monitors = [doc for doc in documents if doc["kind"] == "ServiceMonitor"
                            and doc["metadata"]["labels"].get("app.kubernetes.io/component") != "otel-collector"]
            self.assertEqual(len(app_monitors), 8)
            for monitor in app_monitors:
                labels = {entry["targetLabel"]: entry.get("replacement")
                          for entry in monitor["spec"]["endpoints"][0]["relabelings"]}
                self.assertEqual(labels["maintenance_scope"], expected_scope)
            config = next(doc for doc in documents if doc["kind"] == "ConfigMap"
                          and "BATCH_MAINTENANCE_ALERT_SCOPE" in doc.get("data", {}))
            self.assertEqual(config["data"]["BATCH_MAINTENANCE_ALERT_SCOPE"], expected_scope)


if __name__ == "__main__":
    unittest.main()
