#!/usr/bin/env python3
"""SDK 真栈 E2E 启动脚本的竞态与失败传播回归测试。"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[3]
CI_RUNNER = ROOT / "scripts/ci/run-sdk-orchestrator-e2e.sh"


def write_executable(path: Path, content: str) -> None:
    path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
    path.chmod(0o755)


class SdkOrchestratorE2eScriptsTest(unittest.TestCase):
    def assert_worker_preparation_failure_is_propagated(
        self,
        language: str,
        executable: str,
        executable_body: str,
        extra_env: dict[str, str] | None = None,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            bin_dir = temp / "bin"
            bin_dir.mkdir()
            worker_log = temp / "worker.log"
            write_executable(bin_dir / executable, executable_body)
            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            env.update(extra_env or {})
            command = f"""
                set -euo pipefail
                source {ROOT / 'scripts/lib/sdk-e2e-common.sh'}
                if worker_pid="$(sdk_e2e_start_worker {language} worker-code api-key {worker_log})"; then
                  exit 0
                else
                  status=$?
                  exit "$status"
                fi
            """

            result = subprocess.run(
                ["bash", "-c", command],
                cwd=ROOT,
                env=env,
                text=True,
                errors="replace",
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(result.returncode, 0, language)
            self.assertIn(
                f"simulated {language} preparation failure",
                worker_log.read_text(encoding="utf-8"),
            )

    def test_ci_runner_dumps_worker_log_when_preparation_fails(self) -> None:
        runner = CI_RUNNER.read_text(encoding="utf-8")

        self.assertIn(
            'if ! WORKER_PID="$(sdk_e2e_start_worker "$LANG_ID" "$WORKER_CODE" "$RAW_KEY" "$WORKER_LOG")"; then',
            runner,
        )
        failure_branch = runner.split('if ! WORKER_PID="$(sdk_e2e_start_worker', maxsplit=1)[1]
        failure_branch = failure_branch.split("fi", maxsplit=1)[0]
        self.assertIn("dump_diagnostics", failure_branch)

    def test_business_bootstrap_waits_for_final_postgres_tcp_server(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            bin_dir = temp / "bin"
            bin_dir.mkdir()
            calls = temp / "docker-calls.log"
            readiness_count = temp / "readiness-count"
            readiness_count.write_text("0", encoding="utf-8")
            write_executable(
                bin_dir / "docker",
                f"""
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\n' "$*" >> {calls!s}
                if [[ "$1" == "compose" ]]; then
                  exit 0
                fi
                if [[ " $* " == *" pg_isready "* ]]; then
                  if [[ " $* " != *" -h 127.0.0.1 "* ]]; then
                    exit 0
                  fi
                  count=$(cat {readiness_count!s})
                  count=$((count + 1))
                  printf '%s' "$count" > {readiness_count!s}
                  ((count >= 2))
                  exit
                fi
                if [[ " $* " == *" psql "* ]]; then
                  [[ "$(cat {readiness_count!s})" -ge 2 ]] || exit 91
                  cat >/dev/null
                  exit 0
                fi
                exit 0
                """,
            )
            write_executable(bin_dir / "sleep", "#!/usr/bin/env bash\nexit 0\n")

            fixture_root = temp / "fixture-root"
            sql_dir = fixture_root / "scripts/db/business"
            sql_dir.mkdir(parents=True)
            (sql_dir / "create_biz_tables.sql").write_text("SELECT 1;\n", encoding="utf-8")
            (sql_dir / "rls-phase-a.sql").write_text("SELECT 1;\n", encoding="utf-8")
            (fixture_root / ".env").write_text("", encoding="utf-8")

            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{bin_dir}:{env['PATH']}",
                    "POSTGRES_USER": "batch_user",
                    "POSTGRES_DB": "batch_platform",
                    "POSTGRES_PASSWORD": "password",
                    "PGUSER": "batch_user",
                    "BUSINESS_DB": "batch_business",
                }
            )
            command = f"""
                set -euo pipefail
                source {ROOT / 'scripts/lib/business-db-bootstrap.sh'}
                batch_bootstrap_business_database {fixture_root} {fixture_root / '.env'} sdk-e2e-test
            """

            result = subprocess.run(
                ["bash", "-c", command],
                cwd=ROOT,
                env=env,
                text=True,
                errors="replace",
                capture_output=True,
                check=False,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("pg_isready -h 127.0.0.1", calls.read_text(encoding="utf-8"))
            self.assertGreaterEqual(int(readiness_count.read_text(encoding="utf-8")), 2)

    def test_typescript_dependency_failure_is_logged_and_propagated(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            bin_dir = temp / "bin"
            bin_dir.mkdir()
            invocation_count = temp / "npm-count"
            invocation_count.write_text("0", encoding="utf-8")
            worker_log = temp / "worker.log"
            write_executable(
                bin_dir / "npm",
                f"""
                #!/usr/bin/env bash
                set -euo pipefail
                count=$(cat {invocation_count!s})
                count=$((count + 1))
                printf '%s' "$count" > {invocation_count!s}
                if ((count == 2)); then
                  echo "simulated sample dependency install failure" >&2
                  exit 42
                fi
                """,
            )
            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            command = f"""
                set -euo pipefail
                source {ROOT / 'scripts/lib/sdk-e2e-common.sh'}
                sdk_e2e_start_worker typescript worker-code api-key {worker_log} >/dev/null
            """

            result = subprocess.run(
                ["bash", "-c", command],
                cwd=ROOT,
                env=env,
                text=True,
                errors="replace",
                capture_output=True,
                check=False,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "simulated sample dependency install failure",
                worker_log.read_text(encoding="utf-8"),
            )

    def test_python_dependency_failure_is_logged_and_propagated(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            python_bin = Path(temp_dir) / "fake-python"
            write_executable(
                python_bin,
                """
                #!/usr/bin/env bash
                if [[ "$*" == "-m pip --version" ]]; then
                  exit 0
                fi
                echo "simulated python preparation failure" >&2
                exit 42
                """,
            )
            self.assert_worker_preparation_failure_is_propagated(
                "python",
                "unused-python-command",
                "#!/usr/bin/env bash\nexit 0\n",
                {"SDK_E2E_PYTHON_BIN": str(python_bin)},
            )

    def test_java_build_failure_is_logged_and_propagated(self) -> None:
        self.assert_worker_preparation_failure_is_propagated(
            "java",
            "mvn",
            "#!/usr/bin/env bash\necho 'simulated java preparation failure' >&2\nexit 42\n",
        )

    def test_rust_build_failure_is_logged_and_propagated(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cargo_dir = Path(temp_dir) / ".cargo/bin"
            cargo_dir.mkdir(parents=True)
            write_executable(
                cargo_dir / "cargo",
                "#!/usr/bin/env bash\necho 'simulated rust preparation failure' >&2\nexit 42\n",
            )
            self.assert_worker_preparation_failure_is_propagated(
                "rust",
                "unused-rust-command",
                "#!/usr/bin/env bash\nexit 0\n",
                {"HOME": temp_dir},
            )


if __name__ == "__main__":
    unittest.main()
