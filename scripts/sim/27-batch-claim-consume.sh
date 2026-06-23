#!/usr/bin/env bash
# =========================================================
# 27-batch-claim-consume.sh:ADR-046 P2 worker 消费端攒批 sim 回归
#
# 命题:flag batch.worker.batch-claim.enabled=true 时,worker 走「攒 K 条 → 一次
#   claim-batch → 逐 partition 执行」路径,控制面 CLAIM 往返 O(N)→⌈N/K⌉。
#
# 设计(适配 sim 套件 flag 默认关):
#   - 触发既有 4 分片 process 作业 TA_PROCESS_STAGE4_SHARDED(stage19 已 seed),等 SUCCESS;
#   - 比对 orchestrator batch_task_batch_claim_size 指标增量:
#       · 增量>0 → 攒批路径被实际命中 + 作业 SUCCESS → PASS(回归绿)。
#       · 增量=0 → workers 跑单条路径(flag 关,sim 默认)→ SKIP(不破默认套件)。
#   - 即:本 stage 在 flag 开时是端到端攒批回归,flag 关时自动 SKIP。
#
# 退出码:0 = PASS 或 SKIP;非 0 = 攒批路径命中但作业未达 SUCCESS(真回归)。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="batch-claim-consume"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/batch-claim-consume.log"
import json, os, subprocess, sys, time, urllib.request

TRIGGER = os.environ["TRIGGER_BASE"]
ORCH = os.environ["ORCH_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
PG = os.environ.get("PG_CONTAINER", "batch-postgres-primary")
PGU = os.environ.get("POSTGRES_USER", "batch_user")
PLAT = os.environ.get("PLATFORM_DB", "batch_platform")

def psql(sql):
    out = subprocess.run(
        ["docker", "exec", PG, "psql", "-U", PGU, "-d", PLAT, "-tA", "-P", "pager=off", "-c", sql],
        check=False, capture_output=True, text=True)
    return (out.stdout or "").strip()

def scrape(metric):
    # micrometer DistributionSummary batch.task.batch_claim.size → *_count / *_sum(prometheus 文本)
    try:
        with urllib.request.urlopen(f"{ORCH}/actuator/prometheus", timeout=10) as r:
            for line in r.read().decode().splitlines():
                if line.startswith(metric + " "):
                    return float(line.split(" ", 1)[1])
    except Exception as ex:  # noqa: BLE001
        print(f"  [warn] scrape {metric} failed: {ex}", flush=True)
    return 0.0

def launch(job, label, params):
    rid = f"sim-batchclaim-{label}-{int(time.time()*1000)%100000000}"
    body = {"tenantId": "ta", "jobCode": job, "triggerType": "API",
            "bizDate": BIZ, "requestId": rid, "params": params}
    req = urllib.request.Request(
        f"{TRIGGER}/api/triggers/launch", data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-Tenant-Id": "ta",
                 "X-Internal-Secret": SECRET, "Idempotency-Key": rid, "X-Request-Id": rid})
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        if resp.status != 200 or '"SUCCESS"' not in text:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {job}")
    return rid

def wait_instance(rid, expected, timeout=240):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = psql(
            "select i.id || '|' || coalesce(i.instance_status,'') "
            "from batch.trigger_request tr join batch.job_instance i on i.id=tr.related_job_instance_id "
            f"where tr.tenant_id='ta' and tr.request_id='{rid}' order by tr.created_at desc limit 1")
        if value:
            iid, status = value.split("|", 1)
            if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
                print(f"  [result] instance={iid} status={status} expected={expected}", flush=True)
                return iid, status
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {rid}")

# 1) 指标基线
before_calls = scrape("batch_task_batch_claim_size_count")
before_parts = scrape("batch_task_batch_claim_size_sum")
print(f"==> baseline: claim-batch calls={before_calls:.0f} partitions(sum)={before_parts:.0f}", flush=True)

# 2) 触发 4 分片 process 作业(stage19 已 seed TA_PROCESS_STAGE4_SHARDED + fixtures)
print("==> launch TA_PROCESS_STAGE4_SHARDED (4 shards)", flush=True)
rid = launch("TA_PROCESS_STAGE4_SHARDED", "sharded", {"batchNo": BATCH + "-bc", "bizDate": BIZ, "partitionCount": 4})
iid, status = wait_instance(rid, "SUCCESS")

# 3) 指标增量 → 判定攒批路径是否被命中
after_calls = scrape("batch_task_batch_claim_size_count")
after_parts = scrape("batch_task_batch_claim_size_sum")
d_calls = after_calls - before_calls
d_parts = after_parts - before_parts
print(f"==> delta: claim-batch calls={d_calls:.0f} partitions={d_parts:.0f}", flush=True)

if d_calls <= 0:
    # flag 关(sim 默认):workers 走单条路径,batch listener 未启动 → SKIP,不破默认套件
    print("🟡 SKIP: batch-claim flag 未开(单条路径),攒批 listener 未命中。", flush=True)
    print("        要跑攒批回归:workers 以 BATCH_WORKER_BATCH_CLAIM_ENABLED=true 启动后重跑本 stage。", flush=True)
    sys.exit(0)

# flag 开:既要作业 SUCCESS,又要攒批路径确被命中
if status != "SUCCESS":
    print(f"❌ 攒批路径命中但作业未 SUCCESS: status={status}", flush=True)
    sys.exit(1)

eff_k = (d_parts / d_calls) if d_calls else 0
print(f"✅ PASS: 攒批路径命中 + 作业 SUCCESS;有效 K(parts/call)={eff_k:.1f},往返 {d_parts:.0f}→{d_calls:.0f}", flush=True)
PY
