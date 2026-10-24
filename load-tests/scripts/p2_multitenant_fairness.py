#!/usr/bin/env python3
# 用途：分析多租户压测结果的资源公平性。
# 用法：在仓库根目录执行 python3 load-tests/scripts/p2_multitenant_fairness.py；参数与前置环境见下方说明及所属 README。
import concurrent.futures
import json
import os
import queue
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


RUN_ID = os.environ["RUN_ID"]
TRIGGER_BASE_URL = os.environ["TRIGGER_BASE_URL"].rstrip("/")
INTERNAL_SECRET = os.environ["INTERNAL_SECRET"]
BIZ_DATE = os.environ["BIZ_DATE"]
TOTAL = int(os.environ.get("FAIRNESS_TOTAL_REQUESTS", "6000"))
CONCURRENCY = int(os.environ.get("FAIRNESS_CONCURRENCY", "96"))
LAUNCH_WEIGHTS = os.environ.get("FAIRNESS_LAUNCH_WEIGHTS", "p2fa:1,p2fb:1,p2fc:1")
WAIT_SECONDS = int(os.environ.get("FAIRNESS_WAIT_SECONDS", "1200"))
# 顶层启动必须经过 Trigger，使准入流程持久化 Workflow 目标。
MODE = os.environ.get("FAIRNESS_MODE", "trigger")


def parse_launch_weights(raw):
    out = []
    for item in raw.split(","):
        tenant, weight = item.split(":", 1)
        tenant = tenant.strip()
        if not tenant or any(not (char.isalnum() or char in "_-") for char in tenant):
            raise ValueError(f"invalid fairness tenant: {tenant!r}")
        out.append((tenant, int(weight)))
    if not out:
        raise ValueError("empty FAIRNESS_LAUNCH_WEIGHTS")
    return out


def planned_requests():
    weights = parse_launch_weights(LAUNCH_WEIGHTS)
    weight_sum = sum(weight for _, weight in weights)
    counts = []
    used = 0
    for idx, (tenant, weight) in enumerate(weights):
        if idx == len(weights) - 1:
            count = TOTAL - used
        else:
            count = int(TOTAL * weight / weight_sum)
            used += count
        counts.append((tenant, count))
    return counts


def sql_literal(value):
    return "'" + value.replace("'", "''") + "'"


def resolve_workflow_ids(tenants):
    tenant_list = ",".join(sql_literal(tenant) for tenant in tenants)
    raw = psql(f"""
        select jd.tenant_id || '|' || wd.id
        from batch.job_definition jd
        join batch.workflow_definition wd
          on wd.tenant_id = jd.tenant_id
         and wd.single_job_definition_id = jd.id
         and wd.enabled
        where jd.tenant_id in ({tenant_list})
          and jd.job_code = 'atomic_sql_demo'
          and jd.enabled
        order by jd.tenant_id, wd.id
    """)
    resolved = {}
    for line in raw.splitlines():
        tenant, workflow_id = line.split("|", 1)
        if tenant in resolved:
            raise RuntimeError(f"multiple enabled single-job Workflows for tenant={tenant}")
        resolved[tenant] = int(workflow_id)
    missing = sorted(set(tenants) - set(resolved))
    if missing:
        raise RuntimeError(f"missing explicit single-job Workflow for tenants: {','.join(missing)}")
    return resolved


def launch_one(tenant, seq, workflow_definition_id):
    request_id = f"{RUN_ID}-{tenant}-{seq}-{uuid.uuid4().hex[:8]}"
    body = {
        "tenantId": tenant,
        "workflowDefinitionId": workflow_definition_id,
        "triggerType": "API",
        "bizDate": BIZ_DATE,
        "requestId": request_id,
        "params": {
            "metadata": {
                "runId": RUN_ID,
                "p2Profile": "multitenant-fairness",
                "tenant": tenant,
                "seq": seq,
            }
        },
    }
    url = f"{TRIGGER_BASE_URL}/api/triggers/launch"
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Internal-Secret": INTERNAL_SECRET,
            "X-Tenant-Id": tenant,
            "Idempotency-Key": request_id,
            "X-Request-Id": request_id,
            "X-Trace-Id": f"{RUN_ID}-p2mt-{tenant}-{seq}",
        },
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode()
            latency_ms = int((time.monotonic() - started) * 1000)
            return tenant, resp.status, latency_ms, text[:200]
    except urllib.error.HTTPError as exc:
        latency_ms = int((time.monotonic() - started) * 1000)
        return tenant, exc.code, latency_ms, exc.read().decode()[:200]
    except Exception as exc:
        latency_ms = int((time.monotonic() - started) * 1000)
        return tenant, 0, latency_ms, str(exc)[:200]


def psql(sql):
    env = os.environ.copy()
    cmd = [
        "psql",
        "-h",
        env["PGHOST"],
        "-p",
        env["PGPORT"],
        "-U",
        env["PGUSER"],
        "-d",
        env["PLATFORM_DB"],
        "-At",
        "-c",
        sql,
    ]
    return subprocess.check_output(cmd, env=env, text=True).strip()


def terminal_counts(tenants):
    tenant_list = ",".join(sql_literal(tenant) for tenant in tenants)
    run_id = sql_literal(RUN_ID)
    sql = f"""
        select tr.tenant_id || '|' || count(*) || '|' ||
               count(wr.id) filter (where wr.run_status in ('SUCCESS','FAILED','TERMINATED'))
        from batch.trigger_request tr
        left join batch.workflow_run wr
          on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id
        where tr.tenant_id in ({tenant_list})
          and tr.workflow_definition_id is not null
          and tr.request_id like {run_id} || '%'
        group by tr.tenant_id
        order by tr.tenant_id
    """
    raw = psql(sql)
    counts = {}
    for line in raw.splitlines():
        tenant, total, terminal = line.split("|")
        counts[tenant] = (int(total), int(terminal))
    return counts


def interleaved_tasks(plan):
    # 保持各租户获得相同的提交机会；受限资源的分配应由租户公平分值决定，
    # 不应受客户端队列顺序影响。
    counts = dict(plan)
    for seq in range(max(counts.values(), default=0)):
        for tenant, _ in plan:
            if seq < counts[tenant]:
                yield tenant, seq


def main():
    if MODE != "trigger":
        raise ValueError(
            "FAIRNESS_MODE must be 'trigger': Workflow launches require persisted Trigger admission"
        )
    plan = planned_requests()
    tenants = [tenant for tenant, _ in plan]
    workflows = resolve_workflow_ids(tenants)
    print(f"plan={plan} total={TOTAL} concurrency={CONCURRENCY} mode={MODE}", flush=True)
    tasks = queue.Queue()
    for tenant, seq in interleaved_tasks(plan):
        tasks.put((tenant, seq))

    results = []

    def worker():
        local = []
        while True:
            try:
                tenant, seq = tasks.get_nowait()
            except queue.Empty:
                return local
            local.append(launch_one(tenant, seq, workflows[tenant]))
            tasks.task_done()

    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        for chunk in pool.map(lambda _: worker(), range(CONCURRENCY)):
            results.extend(chunk)

    ok = sum(1 for _, status, _, _ in results if status in (200, 201))
    by_tenant = {}
    for tenant, status, latency_ms, _ in results:
        item = by_tenant.setdefault(tenant, {"total": 0, "ok": 0, "latencies": []})
        item["total"] += 1
        if status in (200, 201):
            item["ok"] += 1
        item["latencies"].append(latency_ms)

    print(f"launch_ok={ok}/{len(results)}", flush=True)
    failures = {}
    for _, status, _, body in results:
        if status in (200, 201):
            continue
        key = (status, body.replace("\n", " ")[:160])
        failures[key] = failures.get(key, 0) + 1
    for (status, body), count in sorted(failures.items(), key=lambda item: item[1], reverse=True)[:10]:
        print(f"launch_failure status={status} count={count} body={body}", flush=True)
    for tenant, item in sorted(by_tenant.items()):
        latencies = sorted(item["latencies"])
        p95 = latencies[int(len(latencies) * 0.95) - 1] if latencies else 0
        print(f"tenant={tenant} launch={item['ok']}/{item['total']} p95_ms={p95}", flush=True)

    deadline = time.time() + WAIT_SECONDS
    while time.time() < deadline:
        counts = terminal_counts([tenant for tenant, _ in plan])
        total = sum(item[0] for item in counts.values())
        terminal = sum(item[1] for item in counts.values())
        by_tenant = ",".join(
            f"{tenant}:{item[1]}/{item[0]}" for tenant, item in sorted(counts.items())
        )
        print(f"terminal={terminal}/{total} tenants={by_tenant}", flush=True)
        if total >= ok and terminal >= total:
            return 0
        time.sleep(5)
    print("timeout waiting multi-tenant terminal states", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
