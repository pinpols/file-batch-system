#!/usr/bin/env bash
# 用途：seed-source-data.sh:把假业务文件投放到 SFTP 入站目录。
# 用法：在仓库根目录执行 bash scripts/sim/04-seed-source-data.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# 04-seed-source-data.sh:把假业务文件投放到 SFTP 入站目录
#   ta: customer.csv → ImportIngressScanner 扫到 → 跑 TA_IMPORT_CUSTOMER → biz.customer_account
#   tb: transaction.csv → TB_IMPORT_TRANSACTION → biz.transaction
#   tc: risk-score.csv → TC_IMPORT_RISK_SCORE → biz.risk_score
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
if [[ "$BIZ_DATE" =~ ^[0-9]{8}$ ]]; then
  BIZ_DATE="${BIZ_DATE:0:4}-${BIZ_DATE:4:2}-${BIZ_DATE:6:2}"
fi
if ! [[ "$BIZ_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "❌ BIZ_DATE must be YYYY-MM-DD or YYYYMMDD, got: $BIZ_DATE" >&2
  exit 2
fi
BIZ_DAY="$BIZ_DATE"
SFTP_CONTAINER="${SFTP_CONTAINER:-batch-sim-sftp}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "==> 生成假数据(bizDate=$BIZ_DATE)"

# ta: customer 100 行
cat > "$TMP/ta-customer-${BIZ_DATE}.csv" << 'EOF'
customer_no,customer_name,customer_type,certificate_no,mobile_no,email,status
EOF
for i in $(seq 1 100); do
  echo "TA-CUST-$(printf '%06d' $i),企业 $i 号,ENTERPRISE,910000000000$(printf '%04d' $i),1390000$(printf '%04d' $i),ta$(printf '%04d' $i)@example.com,ACTIVE" >> "$TMP/ta-customer-${BIZ_DATE}.csv"
done

# tb: transaction 200 行
cat > "$TMP/tb-transaction-${BIZ_DATE}.csv" << 'EOF'
txn_no,account_no,txn_type,amount,currency_code,txn_date,remark
EOF
for i in $(seq 1 200); do
  echo "TB-TXN-$(printf '%08d' $i),ACC$(printf '%010d' $i),DEPOSIT,$((100 + i)).50,CNY,${BIZ_DAY},自动生成-$i" >> "$TMP/tb-transaction-${BIZ_DATE}.csv"
done

# tc: risk score 50 行
cat > "$TMP/tc-risk-score-${BIZ_DATE}.csv" << 'EOF'
entity_id,entity_type,score_value,score_band,score_date
EOF
for i in $(seq 1 50); do
  score=$((i * 37 % 100))
  band=LOW
  [ "$score" -ge 80 ] && band=HIGH
  echo "TC-ENTITY-$(printf '%06d' $i),ACCOUNT,${score},${band},${BIZ_DAY}" >> "$TMP/tc-risk-score-${BIZ_DATE}.csv"
done

echo "==> 投放到 SFTP /inbound/(用户各自 chroot)"
for t in ta tb tc; do
  case $t in
    ta) f="ta-customer-${BIZ_DATE}.csv" ;;
    tb) f="tb-transaction-${BIZ_DATE}.csv" ;;
    tc) f="tc-risk-score-${BIZ_DATE}.csv" ;;
  esac
  # 这些确切名称归当前 SIM 夹具所有。替换它们可使重复运行保持
  # 结果确定，同时保留 SFTP 卷中与测试无关的用户文件。
  docker exec "$SFTP_CONTAINER" rm -f "/home/$t/inbound/$f"
  docker cp "$TMP/$f" "$SFTP_CONTAINER:/home/$t/inbound/$f"
  docker exec "$SFTP_CONTAINER" chown "$t:users" "/home/$t/inbound/$f"
  size=$(docker exec "$SFTP_CONTAINER" stat -c %s "/home/$t/inbound/$f" 2>/dev/null || echo "?")
  echo "  ✓ $t:/inbound/$f ($size bytes)"
done

echo
echo "==> SFTP /inbound 现状"
for t in ta tb tc; do
  echo "── $t"
  docker exec "$SFTP_CONTAINER" ls -la "/home/$t/inbound/" 2>&1 | awk 'NR <= 10'
done

echo
echo "==> ✅ 种子数据投放完毕"
