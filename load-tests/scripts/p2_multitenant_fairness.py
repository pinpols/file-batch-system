#!/usr/bin/env python3
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
ORCHESTRATOR_BASE_URL = os.environ["ORCHESTRATOR_BASE_URL"].rstrip("/")
INTERNAL_SECRET = os.environ["INTERNAL_SECRET"]
BIZ_DATE = os.environ["BIZ_DATE"]
TOTAL = int(os.environ.get("FAIRNESS_TOTAL_REQUESTS", "6000"))
CONCURRENCY = int(os.environ.get("FAIRNESS_CONCURRENCY", "96"))
LAUNCH_WEIGHTS = os.environ.get("FAIRNESS_LAUNCH_WEIGHTS", "p2fa:1,p2fb:1,p2fc:1")
WAIT_SECONDS = int(os.environ.get("FAIRNESS_WAIT_SECONDS", "1200"))
# The trigger endpoint creates the trigger_request that the asynchronous
# orchestrator consumer requires. Direct orchestrator mode is retained for
# pre-seeded trigger_request fixtures only.
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


def launch_one(tenant, seq):
    request_id = f"{RUN_ID}-{tenant}-{seq}-{uuid.uuid4().hex[:8]}"
    body = {
        "tenantId": tenant,
        "jobCode": "atomic_sql_demo",
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
    if MODE == "orchestrator":
        url = f"{ORCHESTRATOR_BASE_URL}/internal/orchestrator/launch"
    else:
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
    tenant_list = ",".join(f"'{tenant}'" for tenant in tenants)
    if MODE == "orchestrator":
        sql = f"""
        select tenant_id || '|' || count(*) || '|' ||
               count(*) filter (where instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED','REJECTED'))
        from batch.job_instance
        where tenant_id in ({tenant_list})
          and params_snapshot::text like '%{RUN_ID}%'
        group by tenant_id
        order by tenant_id
        """
    else:
        sql = f"""
        select tr.tenant_id || '|' || count(*) || '|' ||
               count(*) filter (where instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED','REJECTED'))
        from batch.trigger_request tr
        left join batch.job_instance ji on ji.id = tr.related_job_instance_id
        where tr.tenant_id in ({tenant_list})
          and tr.request_id like '{RUN_ID}%'
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
    # Preserve equal ingress opportunity. A tenant's configured fairness score,
    # not client-side queue ordering, must decide who obtains constrained slots.
    counts = dict(plan)
    for seq in range(max(counts.values(), default=0)):
        for tenant, _ in plan:
            if seq < counts[tenant]:
                yield tenant, seq


def main():
    plan = planned_requests()
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
            local.append(launch_one(tenant, seq))
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
