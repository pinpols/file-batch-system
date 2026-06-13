#!/usr/bin/env bash
# ④ HA 故障演练(混沌):逐组件 kill leader / 滚动重启,断言自动恢复。
# 这是"HA 真的成立"的证据——把 deploy/ha/README.md 的验证表变成可重复跑的脚本。
# ⚠️ 会真删 pod,**只在非生产/演练集群跑**(prod 演练需变更窗口 + 知会)。
# 用法:bash scripts/ha/failover-drill.sh [pg|kafka|redis|minio|all]
set -euo pipefail
TARGET=${1:-all}
PASS=(); FAIL=()
log() { echo "[drill] $*"; }
ok()  { PASS+=("$1"); echo "  ✅ $1"; }
bad() { FAIL+=("$1"); echo "  ❌ $1"; }

# 等待条件,timeout 秒;cmd 返回 0 即过
wait_until() { local to=$1; shift; local end=$((SECONDS+to)); until "$@"; do [ $SECONDS -lt $end ] || return 1; sleep 3; done; }

drill_pg() {
  log "PG:kill 当前 leader,期望 operator 30-60s 内 promote replica"
  local ns=batch-data
  local leader
  leader=$(kubectl -n $ns get pods -l application=spilo,spilo-role=master -o jsonpath='{.items[0].metadata.name}' 2>/dev/null) || true
  [ -n "${leader:-}" ] || { bad "PG 找不到 leader(标签按 operator 实际调整)"; return; }
  log "  当前 leader=$leader,删除之"
  kubectl -n $ns delete pod "$leader" --wait=false
  if wait_until 120 bash -c "
      nl=\$(kubectl -n $ns get pods -l application=spilo,spilo-role=master -o jsonpath='{.items[0].metadata.name}' 2>/dev/null);
      [ -n \"\$nl\" ] && [ \"\$nl\" != \"$leader\" ]"; then
    ok "PG failover:新 leader 已选出(≤120s)"
  else
    bad "PG failover:120s 内未选出新 leader"
  fi
}

drill_kafka() {
  log "Kafka:滚动重启 1 broker,期望 ISR 恢复、生产消费不断"
  local ns=kafka
  local pod
  pod=$(kubectl -n $ns get pods -l strimzi.io/cluster=batch-kafka -o jsonpath='{.items[0].metadata.name}' 2>/dev/null) || true
  [ -n "${pod:-}" ] || { bad "Kafka 找不到 broker pod"; return; }
  kubectl -n $ns delete pod "$pod" --wait=false
  if wait_until 180 bash -c "kubectl -n $ns get pods -l strimzi.io/cluster=batch-kafka \
      -o jsonpath='{range .items[*]}{.status.phase}{\"\n\"}{end}' | grep -vqx Running; then false; else true; fi" 2>/dev/null; then
    ok "Kafka:broker 重启后全 Running(ISR 需手工 kafka-topics --describe 复核=3)"
  else
    bad "Kafka:broker 未在 180s 内全部 Running"
  fi
}

drill_redis() {
  log "Redis:删 master,期望 Sentinel 选新 master"
  local ns=redis
  # spotahome:master 由 redis pod 承载,sentinel 选主;简化为删一个 redis pod 看自愈
  local pod
  pod=$(kubectl -n $ns get pods -l app.kubernetes.io/component=redis -o jsonpath='{.items[0].metadata.name}' 2>/dev/null) || true
  [ -n "${pod:-}" ] || { bad "Redis 找不到 redis pod"; return; }
  kubectl -n $ns delete pod "$pod" --wait=false
  if wait_until 120 bash -c "kubectl -n $ns get pods -l app.kubernetes.io/component=redis \
      -o jsonpath='{range .items[*]}{.status.phase}{\"\n\"}{end}' | grep -cx Running | grep -q '[1-9]'"; then
    ok "Redis:pod 自愈 Running(Sentinel 选主需 redis-cli sentinel masters 复核)"
  else
    bad "Redis:120s 内未恢复"
  fi
}

drill_minio() {
  log "MinIO:删 1 个 server pod,期望纠删码读写不中断、pod 自愈"
  local ns=minio
  local pod
  pod=$(kubectl -n $ns get pods -l v1.min.io/tenant=batch-minio -o jsonpath='{.items[0].metadata.name}' 2>/dev/null) || true
  [ -n "${pod:-}" ] || { bad "MinIO 找不到 server pod"; return; }
  kubectl -n $ns delete pod "$pod" --wait=false
  if wait_until 180 bash -c "kubectl -n $ns get pods -l v1.min.io/tenant=batch-minio \
      -o jsonpath='{range .items[*]}{.status.phase}{\"\n\"}{end}' | grep -vqx Running; then false; else true; fi" 2>/dev/null; then
    ok "MinIO:server 自愈全 Running(读写不中断需在线 mc 验证)"
  else
    bad "MinIO:180s 内未全部 Running"
  fi
}

case "$TARGET" in
  pg) drill_pg ;;
  kafka) drill_kafka ;;
  redis) drill_redis ;;
  minio) drill_minio ;;
  all) drill_pg; drill_kafka; drill_redis; drill_minio ;;
  *) echo "用法: $0 [pg|kafka|redis|minio|all]"; exit 1 ;;
esac

echo ""; echo "===== 演练结果 ====="
printf '通过 %d:%s\n' "${#PASS[@]}" "${PASS[*]:-}"
printf '失败 %d:%s\n' "${#FAIL[@]}" "${FAIL[*]:-}"
[ ${#FAIL[@]} -eq 0 ] || { echo "有失败项,HA 未达标"; exit 1; }
echo "全部通过。注:应用侧零中断需配合真实流量观测(本脚本只验基础件自愈)。"
