# 用途：控制 SIM 导入完成后再触发下游导出与分发的顺序。
# 用法：由SIM 负载入口导入 load_orchestration 模块并调用其公共函数。
"""提供本地 SIM 负载流程的顺序控制辅助函数。"""

from collections.abc import Callable, Mapping, Sequence
from time import monotonic, sleep


def wait_for_import_terminal(
    tenant: str,
    job: str,
    request_id: str,
    read_status: Callable[[str, str], str],
    *,
    timeout_seconds: int = 180,
    poll_interval_seconds: int = 2,
    now: Callable[[], float] = monotonic,
    pause: Callable[[float], None] = sleep,
) -> str:
    """等待已接纳的 IMPORT 请求对应 JobInstance 到达终态。"""
    deadline = now() + timeout_seconds
    terminal = {"SUCCESS", "FAILED", "CANCELLED", "ABORTED"}
    while now() < deadline:
        status = read_status(tenant, request_id)
        if status in terminal:
            return status
        pause(poll_interval_seconds)
    return "TIMEOUT"


def launch_exports_after_imports(
    tenants: Sequence[str],
    imports_by_tenant: Mapping[str, Sequence[tuple[str, str]]],
    expected_import_counts: Mapping[str, int],
    wait_import_terminal: Callable[[str, str, str], str],
    launch_export: Callable[[str], None],
    report: Callable[[str], None] = print,
) -> tuple[dict[str, bool], list[str]]:
    """先等待各租户导入完成，再为已就绪租户触发导出。"""
    import_ok_by_tenant: dict[str, bool] = {}
    errors: list[str] = []

    for tenant in tenants:
        requests = imports_by_tenant.get(tenant, ())
        import_ok = len(requests) == expected_import_counts.get(tenant, 0)
        for job, request_id in requests:
            status = wait_import_terminal(tenant, job, request_id)
            report(f"  [IMPORT WAIT] {tenant}/{job:24s} requestId={request_id} status={status}")
            if status != "SUCCESS":
                errors.append(
                    f"IMPORT {tenant}/{job} did not reach SUCCESS before dependent EXPORT: {status}"
                )
                import_ok = False
        import_ok_by_tenant[tenant] = import_ok

    for tenant in tenants:
        if not import_ok_by_tenant[tenant]:
            report(f"  [EXPORT SKIP] {tenant}: prerequisite IMPORT did not reach SUCCESS")
            continue
        launch_export(tenant)

    return import_ok_by_tenant, errors
