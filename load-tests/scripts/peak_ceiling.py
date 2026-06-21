#!/usr/bin/env python3
"""多租户并发峰值流量 — 单机控制面吞吐天花板压测。

对 ta/tb/tc 等比并发 launch 轻量 atomic_sql_demo(纯控制面:orchestrator+DB+Kafka+worker,
不含重 worker CPU),按梯度抬升 offered load,测每级的:
  - launch 成功率 / p95
  - 完成排空时间 → 完成吞吐 (terminal jobs/s)
  - 峰值持续完成率 (max Δterminal / 采样窗)  ← 这是真正的「天花板」
  - REJECT / FAILED 计数(背压信号)

统计走 docker exec psql(无本地密码依赖);单机 join 可用(Citus 需改共置 join)。
环境变量:TRIGGER_BASE / SECRET / PG_CONTAINER / PG_USER / PG_DB / BIZ_DATE / LEVELS / PER_LEVEL。
"""
import concurrent.futures
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

TRIGGER_BASE = os.environ.get("TRIGGER_BASE", "http://localhost:18081").rstrip("/")
SECRET = os.environ["SECRET"]
PG_CONTAINER = os.environ.get("PG_CONTAINER", "batch-postgres-primary")
PG_USER = os.environ.get("PG_USER", "batch_user")
PG_DB = os.environ.get("PG_DB", "batch_platform")
BIZ_DATE = os.environ.get("BIZ_DATE", "2026-05-05")
JOB_CODE = os.environ.get("JOB_CODE", "atomic_sql_demo")
TENANTS = os.environ.get("TENANTS", "ta,tb,tc").split(",")
# 每级 = (concurrency, total_requests)
LEVELS = os.environ.get("LEVELS", "32:600,64:600,128:600,256:600")
DRAIN_TIMEOUT = int(os.environ.get("DRAIN_TIMEOUT", "600"))
SAMPLE_SEC = int(os.environ.get("SAMPLE_SEC", "3"))
COLOCATED = os.environ.get("COLOCATED", "0") == "1"  # Citus:join 补 tenant_id 共置


def psql(sql):
    cmd = ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB, "-At", "-c", sql]
    return subprocess.check_output(cmd, text=True).strip()


def launch_one(tenant, rid):
    body = {
        "tenantId": tenant, "jobCode": JOB_CODE, "triggerType": "API",
        "bizDate": BIZ_DATE, "requestId": rid,
        "params": {"metadata": {"runId": rid}},
    }
    req = urllib.request.Request(
        f"{TRIGGER_BASE}/api/triggers/launch", data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "X-Internal-Secret": SECRET,
                 "X-Tenant-Id": tenant, "Idempotency-Key": rid, "X-Request-Id": rid},
    )
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, int((time.monotonic() - t0) * 1000)
    except urllib.error.HTTPError as e:
        return e.code, int((time.monotonic() - t0) * 1000)
    except Exception:
        return 0, int((time.monotonic() - t0) * 1000)


def counts(run_id):
    join = ("join batch.job_instance ji on ji.id=tr.related_job_instance_id and ji.tenant_id=tr.tenant_id"
            if COLOCATED else
            "left join batch.job_instance ji on ji.id=tr.related_job_instance_id")
    tenant_filter = "tr.tenant_id in (" + ",".join(f"'{t}'" for t in TENANTS) + ")"
    sql = f"""
    select count(*),
      count(*) filter (where ji.instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED','REJECTED')),
      count(*) filter (where ji.instance_status='SUCCESS'),
      count(*) filter (where tr.request_status='REJECTED')
    from batch.trigger_request tr {join}
    where {tenant_filter} and tr.request_id like '{run_id}%'
    """
    a, b, c, d = psql(sql).split("|")
    return int(a), int(b), int(c), int(d)


def run_level(level_idx, concurrency, total):
    run_id = f"pk{int(time.time())}-L{level_idx}"
    plan = [(TENANTS[i % len(TENANTS)], f"{run_id}-{i}") for i in range(total)]
    print(f"\n=== L{level_idx} concurrency={concurrency} total={total} run_id={run_id} ===", flush=True)
    t_start = time.monotonic()
    statuses = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futs = [pool.submit(launch_one, t, rid) for t, rid in plan]
        for f in concurrent.futures.as_completed(futs):
            statuses.append(f.result())
    t_launch = time.monotonic()
    ok = sum(1 for s, _ in statuses if s in (200, 201))
    lat = sorted(l for _, l in statuses)
    p95 = lat[int(len(lat) * 0.95) - 1] if lat else 0
    launch_tput = ok / (t_launch - t_start) if t_launch > t_start else 0
    print(f"launch_ok={ok}/{total} launch_p95_ms={p95} launch_tput={launch_tput:.1f}/s "
          f"launch_wall={t_launch-t_start:.1f}s", flush=True)

    # drain:采样完成曲线,记峰值持续完成率
    deadline = time.time() + DRAIN_TIMEOUT
    prev_term, prev_t = 0, time.monotonic()
    peak_rate = 0.0
    last = (0, 0, 0, 0)
    while time.time() < deadline:
        time.sleep(SAMPLE_SEC)
        tot, term, succ, rej = counts(run_id)
        last = (tot, term, succ, rej)
        now = time.monotonic()
        rate = (term - prev_term) / (now - prev_t) if now > prev_t else 0
        peak_rate = max(peak_rate, rate)
        print(f"  t+{now-t_start:5.0f}s terminal={term}/{tot} success={succ} rejected={rej} "
              f"rate={rate:.1f}/s", flush=True)
        prev_term, prev_t = term, now
        if tot >= ok and term >= tot:
            break
    t_drain = time.monotonic()
    tot, term, succ, rej = last
    drain_tput = ok / (t_drain - t_start) if t_drain > t_start else 0
    print(f"L{level_idx} RESULT: launch_ok={ok} terminal={term} success={succ} rejected={rej} "
          f"drain_wall={t_drain-t_start:.1f}s end2end_tput={drain_tput:.1f}/s peak_rate={peak_rate:.1f}/s",
          flush=True)
    return {"level": level_idx, "concurrency": concurrency, "total": total, "launch_ok": ok,
            "launch_p95_ms": p95, "launch_tput": round(launch_tput, 1), "terminal": term,
            "success": succ, "rejected": rej, "end2end_tput": round(drain_tput, 1),
            "peak_rate": round(peak_rate, 1)}


def main():
    results = []
    for idx, spec in enumerate(LEVELS.split(","), 1):
        c, t = spec.split(":")
        results.append(run_level(idx, int(c), int(t)))
    print("\n\n======== RAMP SUMMARY ========", flush=True)
    print(f"{'L':>2} {'conc':>5} {'total':>6} {'launch_ok':>9} {'p95ms':>6} "
          f"{'lnch/s':>7} {'succ':>5} {'rej':>5} {'e2e/s':>6} {'peak/s':>6}", flush=True)
    for r in results:
        print(f"{r['level']:>2} {r['concurrency']:>5} {r['total']:>6} {r['launch_ok']:>9} "
              f"{r['launch_p95_ms']:>6} {r['launch_tput']:>7} {r['success']:>5} {r['rejected']:>5} "
              f"{r['end2end_tput']:>6} {r['peak_rate']:>6}", flush=True)
    print("\nJSON " + json.dumps(results), flush=True)


if __name__ == "__main__":
    sys.exit(main())
