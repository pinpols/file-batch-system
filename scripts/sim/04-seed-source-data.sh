#!/usr/bin/env bash
# =========================================================
# 04-seed-source-data.sh:把假业务文件投放到 SFTP 入站目录
#   ta: customer.csv → ImportIngressScanner 扫到 → 跑 TA_IMPORT_CUSTOMER → biz.customer_account
#   tb: transaction.csv → TB_IMPORT_TRANSACTION → biz.transaction
#   tc: risk-score.csv → TC_IMPORT_RISK_SCORE → biz.risk_score
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

BIZ_DATE="${BIZ_DATE:-$(date +%Y%m%d)}"
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
txn_no,account_no,txn_type,currency_code,remark
EOF
for i in $(seq 1 200); do
  echo "TB-TXN-$(printf '%08d' $i),ACC$(printf '%010d' $i),DEPOSIT,CNY,自动生成-$i" >> "$TMP/tb-transaction-${BIZ_DATE}.csv"
done

# tc: risk score 50 行
cat > "$TMP/tc-risk-score-${BIZ_DATE}.csv" << 'EOF'
customer_no,score,risk_level,assessed_at
EOF
for i in $(seq 1 50); do
  echo "TC-CUST-$(printf '%06d' $i),$((RANDOM % 100)),$([ $((RANDOM % 3)) -eq 0 ] && echo HIGH || echo LOW),$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$TMP/tc-risk-score-${BIZ_DATE}.csv"
done

echo "==> 投放到 SFTP /inbound/(用户各自 chroot)"
for t in ta tb tc; do
  case $t in
    ta) f="ta-customer-${BIZ_DATE}.csv" ;;
    tb) f="tb-transaction-${BIZ_DATE}.csv" ;;
    tc) f="tc-risk-score-${BIZ_DATE}.csv" ;;
  esac
  docker cp "$TMP/$f" "sftp:/home/$t/inbound/$f"
  docker exec sftp chown "$t:users" "/home/$t/inbound/$f"
  size=$(docker exec sftp stat -c %s "/home/$t/inbound/$f" 2>/dev/null || echo "?")
  echo "  ✓ $t:/inbound/$f ($size bytes)"
done

echo
echo "==> SFTP /inbound 现状"
for t in ta tb tc; do
  echo "── $t"
  docker exec sftp ls -la "/home/$t/inbound/" 2>&1 | head
done

echo
echo "==> ✅ 种子数据投放完毕"
