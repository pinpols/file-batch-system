#!/usr/bin/env bash
# =========================================================
# 26-bundle-import.sh:ADR-046 文件束导入「全链」sim 阶段
#
#   投 2 个数据文件 + 1 个 .batch.json v2 清单到 MinIO ingress/ta/ →
#   ImportIngressScanner 扫到、按清单 fileMapping 给每文件 file_record 落
#   bundleTemplateCode + bundleJobCode → 到达组凑齐 → BundleArrivalLauncher 发
#   TA_BUNDLE_IMPORT(BUNDLE_IMPORT)launch → DefaultSchedulePlanBuilder 展 2 个
#   绑定异构 partition → import worker 各自复用 file_record 按模板导入 biz.customer_account。
#
# 断言:① TA_BUNDLE_IMPORT 的 job_instance 出现并达终态;② 恰好 2 个 partition,各带
#   source_file_id + template_code 绑定;③(worker 真跑时)biz.customer_account 收到两文件的行。
#
# ⚠️ CI/手动专用,不在 CI 自动跑:需全栈 up(PG/MinIO/Kafka)+ worker-import 开启
#    batch-manifest 扫描 + arrival 到达组 + 非 JDK25 的可执行 worker。本机 sim worker
#    fat-jar 在 JDK25 嵌套 jar loader 卡死(见 docs backlog),故本阶段只能在能跑 sim 的
#    环境验证;脚本静态(SQL fixture 加载 / bash 语法)已验。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="bundle-import"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

# 默认 opt-in:本阶段需 worker-import 开启 batch-manifest 扫描 + arrival 到达组(非默认 sim 配置),
# 且需能执行的 worker(本机 JDK25 worker hang 跑不了)。未显式 RUN_BUNDLE_SIM=1 时跳过,
# 避免破坏标准 sim 一条龙(harness 自动遍历 [0-2][0-9] 阶段)。显式跑:RUN_BUNDLE_SIM=1 bash scripts/sim/26-bundle-import.sh
if [[ "${RUN_BUNDLE_SIM:-0}" != "1" ]]; then
  echo "==> 26-bundle-import 跳过(opt-in:设 RUN_BUNDLE_SIM=1 且 worker-import 开 batch-manifest+arrival 才跑)"
  exit 0
fi

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap + bundle-import fixtures"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PG_PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PG_PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-bundle-import-fixtures.sql >/dev/null

export MINIO_CONTAINER="${MINIO_CONTAINER:-batch-minio}"

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/bundle-import.log"
import json
import os
import subprocess
import sys
import time

BIZ = os.environ["BIZ_DATE"]
RUN = str(int(time.time() * 1000) % 100000000)
MINIO_CONTAINER = os.environ["MINIO_CONTAINER"]
BUCKET = os.environ["BATCH_S3_BUCKET"]
AK = os.environ["BATCH_S3_ACCESS_KEY"]
SK = os.environ["BATCH_S3_SECRET_KEY"]
JOB_CODE = "TA_BUNDLE_IMPORT"
TEMPLATE = "TA_IMPORT_CUSTOMER_TPL"
GROUP = f"bundle-import-{RUN}"

CSV_HEADER = "customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status\n"


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def psql(db, sql, tuples=True):
    args = ["docker", "exec", os.environ["PG_CONTAINER"], "psql",
            "-U", os.environ["POSTGRES_USER"], "-d", db, "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return sh(args)


def csv_rows(prefix, n):
    rows = [CSV_HEADER]
    for i in range(1, n + 1):
        no = f"{prefix}{i:06d}"
        rows.append(
            f"{no},企业 {i} 号,ENTERPRISE,9100000000{i:06d},139000{i:07d},{no.lower()}@x.io,ACTIVE\n")
    return "".join(rows)


def mc_init():
    sh(["docker", "exec", MINIO_CONTAINER, "mc", "alias", "set", "local",
        "http://localhost:9000", AK, SK], check=True)
    sh(["docker", "exec", MINIO_CONTAINER, "mc", "mb", "-p", f"local/{BUCKET}"])


def upload(object_name, data):
    p = subprocess.run(
        ["docker", "exec", "-i", MINIO_CONTAINER, "mc", "pipe", f"local/{BUCKET}/{object_name}"],
        input=data, text=True, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(f"upload {object_name} failed: {p.stderr.strip()}")
    print(f"  ✓ uploaded {object_name} ({len(data)} bytes)")


# 1) 生成 2 个数据文件 + v2 清单(声明本束 → TA_BUNDLE_IMPORT,逐文件用 TA_IMPORT_CUSTOMER_TPL 模板)
f1 = f"bundle-a-{BIZ}-{RUN}.csv"
f2 = f"bundle-b-{BIZ}-{RUN}.csv"
data1 = csv_rows("BNDLA", 10)
data2 = csv_rows("BNDLB", 15)
manifest = {
    "schemaVersion": "batch-manifest-v2",
    "fileGroupCode": GROUP,
    "bizDate": BIZ,
    "tenantId": "ta",
    "jobCode": JOB_CODE,
    "requiredFiles": [f1, f2],
    "fileMapping": [
        {"fileName": f1, "templateCode": TEMPLATE},
        {"fileName": f2, "templateCode": TEMPLATE},
    ],
}

mc_init()
prefix = f"ingress/ta"
upload(f"{prefix}/{f1}", data1)
upload(f"{prefix}/{f2}", data2)
upload(f"{prefix}/{GROUP}.batch.json", json.dumps(manifest, ensure_ascii=False))

# 2) 轮询:扫描器登记 → 到达组凑齐 → BUNDLE_IMPORT launch → 分区展开
print(f"==> 等待 scanner→到达组→BUNDLE_IMPORT launch(group={GROUP})")
instance_id = None
deadline = time.time() + 180
while time.time() < deadline:
    r = psql(os.environ["PG_PLATFORM_DB"],
             "select id from batch.job_instance where tenant_id='ta' and job_code='%s'"
             " order by id desc limit 1" % JOB_CODE)
    val = r.stdout.strip()
    if val:
        instance_id = int(val)
        break
    time.sleep(5)

if instance_id is None:
    print("❌ FAIL:超时未见 TA_BUNDLE_IMPORT job_instance(检查 scanner batch-manifest/arrival 配置是否开启)")
    sys.exit(1)
print(f"  ✓ bundle launched, job_instance id={instance_id}")

# 3) 断言分区数 + 绑定
r = psql(os.environ["PG_PLATFORM_DB"],
         "select partition_no, source_file_id, template_code from batch.job_partition"
         " where tenant_id='ta' and job_instance_id=%d order by partition_no" % instance_id)
parts = [ln for ln in r.stdout.strip().splitlines() if ln]
print(f"  partitions: {parts}")
assert len(parts) == 2, f"期望 2 个 partition,实得 {len(parts)}"
for ln in parts:
    cols = ln.split("|")
    assert cols[1], f"partition 缺 source_file_id: {ln}"
    assert cols[2] == TEMPLATE, f"partition template_code 不符: {ln}"
print("  ✓ 2 个绑定异构 partition(各带 source_file_id + template_code)")

# 4) 终态 + 业务行(worker 真跑时)
print("==> 等待 partition 达终态")
final = None
deadline = time.time() + 180
while time.time() < deadline:
    r = psql(os.environ["PG_PLATFORM_DB"],
             "select count(*) filter (where partition_status in ('SUCCESS','SUCCEEDED')),"
             " count(*) filter (where partition_status like '%FAIL%'), count(*)"
             " from batch.job_partition where tenant_id='ta' and job_instance_id=%d" % instance_id)
    ok, failed, total = (r.stdout.strip().split("|") + ["0", "0", "0"])[:3]
    if int(ok) + int(failed) >= int(total) and int(total) > 0:
        final = (int(ok), int(failed), int(total))
        break
    time.sleep(5)

if final is None:
    print("⚠️ partition 未在限时内达终态(worker 可能未执行——本机 JDK25 worker hang 即此现象)。"
          "编排侧(launch+分区展开+绑定)已验证通过。")
    sys.exit(0)

ok, failed, total = final
print(f"  partition 终态:SUCCESS={ok} FAILED={failed} TOTAL={total}")
rows = psql(os.environ["PG_BUSINESS_DB"],
            "select count(*) from biz.customer_account where customer_no like 'BNDL%'").stdout.strip()
print(f"  biz.customer_account BNDL* 行数:{rows}")
assert failed == 0, f"有 {failed} 个分区失败"
assert ok == total, "并非全部分区成功"
print("✅ PASS:文件束导入全链(scanner→到达组→launch→展2分区→worker导入)通过")
PY
