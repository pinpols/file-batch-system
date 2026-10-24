"""Workflow-root helpers for local simulation fixtures."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def workflow_id_for_fixture(tenant_id: str, job_code: str) -> int:
    """Provision and resolve the managed Workflow used to exercise a seeded Job."""
    sim_root = Path(__file__).resolve().parents[2]
    platform_db = (
        os.environ.get("PLATFORM_DB")
        or os.environ.get("PG_PLATFORM_DB")
        or os.environ.get("PGDATABASE")
        or os.environ.get("BATCH_DEFAULT_POSTGRES_DATABASE", "batch_platform")
    )
    postgres_user = (
        os.environ.get("POSTGRES_USER")
        or os.environ.get("PGUSER")
        or os.environ.get("BATCH_DEFAULT_POSTGRES_USERNAME", "batch_user")
    )
    container = (
        os.environ.get("PG_CONTAINER")
        or os.environ.get("POSTGRES_CONTAINER")
        or os.environ.get("BATCH_DEFAULT_POSTGRES_CONTAINER", "batch-postgres-primary")
    )
    fixture_tag = os.environ.get("BATCH_NO") or os.environ.get("RUN_ID") or "sim-fixture"

    provision_sql = sim_root / "scripts/local/sql/provision-single-job-workflow.sql"
    resolve_sql = sim_root / "scripts/sim/sql/select-single-job-workflow-id.sql"

    def run_psql(sql_path: Path) -> str:
        command = [
            "docker", "exec", "-i", container, "psql", "-X", "-q", "-t", "-A",
            "-v", "ON_ERROR_STOP=1", "-U", postgres_user, "-d", platform_db,
            "-v", f"tenant_id={tenant_id}", "-v", f"job_code={job_code}",
            "-v", f"fixture_tag={fixture_tag}", "-f", "/dev/stdin",
        ]
        result = subprocess.run(
            command,
            input=sql_path.read_text(encoding="utf-8"),
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            detail = result.stderr.strip().splitlines()
            raise RuntimeError(
                f"Workflow fixture SQL failed for {tenant_id}/{job_code}: "
                f"{detail[-1] if detail else 'psql returned non-zero'}"
            )
        return result.stdout.strip()

    run_psql(provision_sql)
    workflow_id = run_psql(resolve_sql)
    if not workflow_id.isdecimal():
        raise RuntimeError(
            f"managed single-job Workflow not found for {tenant_id}/{job_code}"
        )
    return int(workflow_id)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: launch_contract.py <tenant_id> <job_code>")
    print(workflow_id_for_fixture(sys.argv[1], sys.argv[2]))
