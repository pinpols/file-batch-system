"""Bound the approved skip-node reason exception with real oasdiff output."""

import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CONSOLE_SPEC = "docs/api/console-api.openapi.yaml"
ORCHESTRATOR_SPEC = "docs/api/orchestrator-internal.openapi.yaml"
APPROVED_PATH = "/api/console/workflow-runs/{id}/skip-node"

GIT_BASELINE_STUB = r'''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['GIT_CALL_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\n')
if args == ['rev-parse', '--verify', 'origin/main']:
    print('1111111111111111111111111111111111111111')
elif args == ['rev-list', '--parents', '-n1', 'HEAD']:
    print('2222222222222222222222222222222222222222')
elif len(args) == 3 and args[:2] == ['cat-file', '-e']:
    relative = args[2].removeprefix('origin/main:')
    raise SystemExit(0 if (Path(os.environ['BASELINE_DIR']) / relative).is_file() else 1)
elif len(args) == 2 and args[0] == 'show' and args[1].startswith('origin/main:'):
    relative = args[1].removeprefix('origin/main:')
    print((Path(os.environ['BASELINE_DIR']) / relative).read_text(), end='')
else:
    raise SystemExit('unexpected Git operation: ' + repr(args))
'''


class OpenApiSkipReasonCutoverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.oasdiff = shutil.which("oasdiff")
        if cls.oasdiff is None:
            raise RuntimeError("oasdiff is required; this test must not silently skip its real diff")
        override = os.environ.get("OPENAPI_SKIP_REASON_GATE_SOURCE")
        cls.gate_source = Path(override) if override else ROOT / "scripts/ci/check-openapi-breaking.sh"
        cls.gate_bytes = cls.gate_source.read_bytes()
        cls.gate_sha = hashlib.sha256(cls.gate_bytes).hexdigest()

    @staticmethod
    def minimal_spec(method, path):
        operation = {
            "operationId": "skipWorkflowNode",
            "parameters": [{"name": "id", "in": "path", "required": True,
                            "schema": {"type": "integer", "format": "int64"}}],
            "responses": {"200": {"description": "Accepted"}},
        }
        return {"openapi": "3.0.3", "info": {"title": "Cutover boundary fixture", "version": "1"},
                "paths": {path: {method: operation}}}

    def run_gate(self, *, method="post", path=APPROVED_PATH, parameter="reason",
                 location="query", target_spec=CONSOLE_SPEC, changed=True):
        base_spec = self.minimal_spec(method, path)
        changed_spec = copy.deepcopy(base_spec)
        if changed:
            new_parameter = {"name": parameter, "in": location, "required": True,
                             "schema": {"type": "string"}}
            changed_spec["paths"][path][method]["parameters"].append(new_parameter)
        with tempfile.TemporaryDirectory(prefix="openapi-skip-reason-") as directory:
            temporary = Path(directory)
            baseline_dir = temporary / "baseline"
            isolated_root = temporary / "worktree"
            gate_path = isolated_root / "scripts/ci/check-openapi-breaking.sh"
            gate_path.parent.mkdir(parents=True)
            gate_path.write_bytes(self.gate_bytes)
            raw_results = []
            for relative in (CONSOLE_SPEC, ORCHESTRATOR_SPEC):
                baseline_path = baseline_dir / relative
                current_path = isolated_root / relative
                baseline_path.parent.mkdir(parents=True, exist_ok=True)
                current_path.parent.mkdir(parents=True, exist_ok=True)
                current_spec = changed_spec if relative == target_spec else base_spec
                baseline_path.write_text(json.dumps(base_spec, indent=2) + "\n")
                current_path.write_text(json.dumps(current_spec, indent=2) + "\n")
                raw_command = [self.oasdiff, "breaking", str(baseline_path), str(current_path),
                               "--fail-on", "ERR", "--format", "json"]
                raw_result = subprocess.run(raw_command, capture_output=True, text=True, timeout=15)
                expected_raw_exit = 1 if changed and relative == target_spec else 0
                self.assertEqual(expected_raw_exit, raw_result.returncode, raw_result.stdout + raw_result.stderr)
                if expected_raw_exit:
                    self.assertIn("new-required-request-parameter", raw_result.stdout)
                raw_results.append({"spec": relative, "command": raw_command,
                                    "exit_code": raw_result.returncode,
                                    "stdout": raw_result.stdout, "stderr": raw_result.stderr})
            fake_bin = temporary / "bin"
            fake_bin.mkdir()
            git_path = fake_bin / "git"
            git_path.write_text(f"#!{sys.executable}\n" + GIT_BASELINE_STUB)
            git_path.chmod(0o755)
            git_log = temporary / "git.jsonl"
            env = os.environ.copy()
            env.update({"PATH": f"{fake_bin}{os.pathsep}{env['PATH']}",
                        "BASELINE_DIR": str(baseline_dir), "GIT_CALL_LOG": str(git_log)})
            command = ["bash", str(gate_path), "origin/main"]
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=20)
            git_calls = [json.loads(line) for line in git_log.read_text().splitlines()]
            self.assertTrue(git_calls)
            self.assertTrue(all(args[0] in ("rev-parse", "rev-list", "cat-file", "show")
                                for args in git_calls))
            current_gate_sha = hashlib.sha256(self.gate_source.read_bytes()).hexdigest()
            self.assertEqual(self.gate_sha, current_gate_sha, "source gate changed during test")
            evidence_dir = os.environ.get("OPENAPI_SKIP_REASON_EVIDENCE_DIR")
            if evidence_dir:
                output_dir = Path(evidence_dir)
                output_dir.mkdir(parents=True, exist_ok=True)
                evidence = {"scope": "real oasdiff; isolated specs; Git baseline reads stubbed",
                            "gate_source": str(self.gate_source), "gate_sha256": self.gate_sha,
                            "method": method, "path": path, "parameter": parameter,
                            "location": location, "target_spec": target_spec, "changed": changed,
                            "baseline_spec": base_spec, "current_spec": changed_spec,
                            "raw_diff": raw_results, "gate_exit_code": result.returncode,
                            "gate_stdout": result.stdout, "gate_stderr": result.stderr,
                            "git_calls": git_calls}
                output_path = output_dir / f"{self.id().split('.')[-1]}.json"
                output_path.write_text(json.dumps(evidence, indent=2) + "\n")
            return result

    def assert_blocked(self, result):
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("new-required-request-parameter", result.stdout)
        self.assertIn("不通过", result.stdout)

    def test_approved_console_post_skip_node_query_reason_passes(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("无 ERR 级破坏性变更", result.stdout)

    def test_same_parameter_on_wrong_method_is_blocked(self):
        result = self.run_gate(method="get")
        self.assert_blocked(result)

    def test_same_parameter_on_wrong_path_is_blocked(self):
        result = self.run_gate(path=APPROVED_PATH + "-extra")
        self.assert_blocked(result)

    def test_other_query_parameter_is_blocked(self):
        result = self.run_gate(parameter="reasonExtra")
        self.assert_blocked(result)

    def test_reason_in_wrong_location_is_blocked(self):
        result = self.run_gate(location="header")
        self.assert_blocked(result)

    def test_same_change_in_orchestrator_spec_is_blocked(self):
        result = self.run_gate(target_spec=ORCHESTRATOR_SPEC)
        self.assert_blocked(result)

    def test_unchanged_specs_pass(self):
        result = self.run_gate(changed=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
