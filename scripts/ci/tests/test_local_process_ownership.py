# 用途：验证本地生命周期脚本只停止当前工作树进程。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_local_process_ownership.py。
"""本地生命周期脚本只能停止当前工作树的 Java 进程。"""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def function_source(path, name):
    source = path.read_text()
    match = re.search(r"(?ms)^" + re.escape(name) + r"\(\) \{.*?^\}", source)
    if match is None:
        raise AssertionError(f"missing shell function {name} in {path}")
    return match.group()


class LocalProcessOwnershipTest(unittest.TestCase):
    def run_relative_jar_ownership_check(self, expected_jar, process_cwd):
        script = r'''
set -euo pipefail
source "$ROOT/scripts/lib/process.sh"
ps() { printf '%s\n' "$FAKE_COMMAND_LINE"; }
process_pid_cwd() { printf '%s\n' "$PROCESS_CWD"; }
process_pid_uses_jar 54320 "$EXPECTED_JAR"
'''
        return subprocess.run(
            ["bash", "-c", script],
            env={
                **os.environ,
                "ROOT": str(ROOT),
                "EXPECTED_JAR": str(expected_jar),
                "PROCESS_CWD": str(process_cwd),
                "FAKE_COMMAND_LINE": "java -jar build/runtime-jars/worker-import.jar --spring.profiles.active=local",
            },
            text=True,
            capture_output=True,
        )

    def run_stop_all_discovery(self, function_name, command_line):
        source = function_source(ROOT / "scripts/local/stop-all.sh", function_name)
        with tempfile.TemporaryDirectory() as temporary:
            kill_log = Path(temporary) / "kill.log"
            script = r'''
set -euo pipefail
source "$ROOT/scripts/lib/process.sh"
ROOT="$ROOT"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
RUNTIME_JAVA_NAMES=(trigger)
_legacy_module_for_runtime() { [[ "$1" == trigger ]] && printf 'batch-trigger\n'; }
_pids_cmd_contains() { printf '54324\n'; }
ps() { printf '%s\n' "$FAKE_COMMAND_LINE"; }
kill_pid() { printf '%s\n' "$*" >> "$KILL_LOG"; }
kill() { printf '%s\n' "$*" >> "$KILL_LOG"; return 0; }
'''
            result = subprocess.run(
                ["bash", "-c", script + source + f"\n{function_name} trigger\n"],
                env={**os.environ, "ROOT": str(ROOT), "FAKE_COMMAND_LINE": command_line,
                     "KILL_LOG": str(kill_log)},
                text=True, capture_output=True,
            )
            kill_commands = kill_log.read_text() if kill_log.exists() else ""
        return result, kill_commands

    def run_start_all_port_cleanup(self, command_line):
        source = function_source(ROOT / "scripts/local/start-all.sh", "_clear_occupied_ports")
        with tempfile.TemporaryDirectory() as temporary:
            kill_log = Path(temporary) / "kill.log"
            script = r'''
set -euo pipefail
source "$ROOT/scripts/lib/runtime-defaults.sh"
source "$ROOT/scripts/lib/process.sh"
ROOT="$ROOT"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
PID_FILE="$TMP_DIR/no-pids"
FORCE_KILL=0
process_listen_pids() { [[ "$1" == "$BATCH_DEFAULT_ORCHESTRATOR_PORT" ]] && printf '54321\n'; }
ps() { printf '%s\n' "$FAKE_COMMAND_LINE"; }
kill() { printf '%s\n' "$*" >> "$KILL_LOG"; [[ "$1" == '-0' ]] && return 0; return 0; }
sleep() { :; }
'''
            script += source + "\n_clear_occupied_ports\n"
            return subprocess.run(
                ["bash", "-c", script],
                env={**os.environ, "ROOT": str(ROOT), "TMP_DIR": temporary,
                     "FAKE_COMMAND_LINE": command_line, "KILL_LOG": str(kill_log)},
                text=True, capture_output=True,
            ), kill_log.read_text() if kill_log.exists() else ""

    def test_start_all_refuses_to_kill_same_relative_jar_from_another_worktree(self):
        foreign_jar = "/other/worktree/build/runtime-jars/orchestrator.jar"
        result, kill_log = self.run_start_all_port_cleanup(f"java -jar {foreign_jar} --spring.profiles.active=local")

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("拒绝自动终止", result.stderr)
        self.assertFalse(kill_log.strip())

    def test_restart_refuses_to_kill_listener_from_another_worktree(self):
        source = function_source(ROOT / "scripts/local/restart.sh", "stop_module")
        with tempfile.TemporaryDirectory() as temporary:
            kill_log = Path(temporary) / "kill.log"
            script = r'''
set -euo pipefail
source "$ROOT/scripts/lib/process.sh"
ROOT="$ROOT"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
is_containerized_module() { return 1; }
port_for() { printf '18081\n'; }
process_listen_pids() { printf '54322\n'; }
ps() { printf '%s\n' "$FAKE_COMMAND_LINE"; }
kill() { printf '%s\n' "$*" >> "$KILL_LOG"; return 0; }
'''
            result = subprocess.run(
                ["bash", "-c", script + source + "\nstop_module trigger\n"],
                env={**os.environ, "ROOT": str(ROOT),
                     "FAKE_COMMAND_LINE": "java -jar /other/worktree/build/runtime-jars/trigger.jar --spring.profiles.active=local",
                     "KILL_LOG": str(kill_log)},
                text=True, capture_output=True,
            )
            kill_commands = kill_log.read_text() if kill_log.exists() else ""

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(kill_commands.strip())

    def test_restart_recognizes_current_worktree_jar_when_command_path_is_relative(self):
        expected_jar = ROOT / "build/runtime-jars/worker-import.jar"
        result = self.run_relative_jar_ownership_check(expected_jar, ROOT)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_restart_rejects_relative_jar_when_process_cwd_is_another_worktree(self):
        expected_jar = ROOT / "build/runtime-jars/worker-import.jar"
        result = self.run_relative_jar_ownership_check(expected_jar, Path("/other/worktree"))

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_stop_all_pid_file_entry_rejects_same_relative_jar_from_another_worktree(self):
        source = function_source(ROOT / "scripts/local/stop-all.sh", "_should_kill_pid_file_entry")
        with tempfile.TemporaryDirectory() as temporary:
            script = r'''
set -euo pipefail
source "$ROOT/scripts/lib/process.sh"
ROOT="$ROOT"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
kill() { [[ "$1" == '-0' ]] && return 0; return 0; }
ps() { printf '%s\\n' "$FAKE_COMMAND_LINE"; }
'''
            result = subprocess.run(
                ["bash", "-c", script + source + "\n_should_kill_pid_file_entry orchestrator 54323 build/runtime-jars/orchestrator.jar\n"],
                env={**os.environ, "ROOT": str(ROOT),
                     "FAKE_COMMAND_LINE": "java -jar /other/worktree/build/runtime-jars/orchestrator.jar --spring.profiles.active=local"},
                text=True, capture_output=True,
            )

        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("避免误杀", result.stderr)

    def test_stop_all_discovery_ignores_same_relative_jar_from_another_worktree(self):
        foreign_jar = "/other/worktree/build/runtime-jars/trigger.jar"
        result, kill_commands = self.run_stop_all_discovery(
            "_scan_runtime_and_legacy_for_names", f"java -jar {foreign_jar} --spring.profiles.active=local"
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(kill_commands.strip())

    def test_stop_all_force_cleanup_ignores_same_relative_jar_from_another_worktree(self):
        foreign_jar = "/other/worktree/build/runtime-jars/trigger.jar"
        result, kill_commands = self.run_stop_all_discovery(
            "_force_kill_names", f"java -jar {foreign_jar} --spring.profiles.active=local"
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(kill_commands.strip())

    def test_stop_all_discovery_still_stops_current_worktree_jar(self):
        current_jar = ROOT / "build/runtime-jars/trigger.jar"
        result, kill_commands = self.run_stop_all_discovery(
            "_scan_runtime_and_legacy_for_names", f"java -jar {current_jar} --spring.profiles.active=local"
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("trigger 54324", kill_commands)


if __name__ == "__main__":
    unittest.main()
