#!/usr/bin/env bash
# ADR-sim 4day · P3 大文件:生成几个大 CSV 投到 MinIO ingress,演示大文件摄取(扫描器登记 + 真实体积)。
# 注:本环境 import 真加载走「内联 content」(40-run-day),大文件主要演示 ingest/scan/登记大对象;
#     体积可观(默认 customer 80万行≈70MB / transaction 150万行≈120MB / risk 50万行≈30MB)。
# 用法: bash 30-gen-bigfiles.sh [bizDate YYYYMMDD]
set -uo pipefail
BDC="${1:-$(date +%Y%m%d)}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
MC(){ docker exec -i batch-minio mc "$@"; }
docker exec batch-minio mc alias set local http://localhost:9000 minioadmin minioadmin123 >/dev/null 2>&1 || true

gen_put(){ # tenant fname header rows rowfmt
  local t="$1" f="$2" header="$3" rows="$4" fmt="$5"
  echo "==> 生成 $f ($rows 行)…"
  { echo "$header"; awk "BEGIN{for(i=1;i<=$rows;i++) printf \"$fmt\n\", i,i,i,i,i}"; } > "$TMP/$f"
  local sz; sz=$(du -h "$TMP/$f" | cut -f1)
  docker cp "$TMP/$f" batch-minio:/tmp/"$f" >/dev/null 2>&1
  docker exec batch-minio mc cp /tmp/"$f" "local/batch-dev/ingress/$t/$f" >/dev/null 2>&1
  docker exec batch-minio rm -f /tmp/"$f" >/dev/null 2>&1
  echo "    ✓ ingress/$t/$f  ($sz)"
  rm -f "$TMP/$f"
}

BIG="${ROWS_BIG:-800000}"; BIGT="${ROWS_BIGT:-1500000}"; BIGR="${ROWS_BIGR:-500000}"
gen_put ta "ta-customer-BIG-${BDC}.csv" "customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status" "$BIG" "TA-BIG-%08d,企业大%d,ENTERPRISE,9100%09d,139%08d,big%d@ex.com,ACTIVE"
gen_put tb "tb-transaction-BIG-${BDC}.csv" "txn_no,account_no,txn_type,amount,currency_code,txn_date,remark" "$BIGT" "TB-BIG-%010d,ACC%012d,DEPOSIT,%d.00,CNY,big-%d-%d"
gen_put tc "tc-risk-BIG-${BDC}.csv" "entity_id,entity_type,score_value,score_band,score_date,model_version" "$BIGR" "TC-BIG-%08d,CUSTOMER,%d,HIGH,2026-06-06,v%d-%d-%d"
echo "==> 大文件已投放。等扫描器(~30-90s)后 file_record 会登记为 RECEIVED(大体积对象)。"
echo "    查看: docker exec batch-minio mc ls --recursive local/batch-dev/ingress/ | grep BIG"
