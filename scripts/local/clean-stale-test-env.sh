#!/usr/bin/env bash
# 用途：清理上一轮 sim / 测试遗留的容器(幂等,随时可跑)。
# 用法：在仓库根目录执行 bash scripts/local/clean-stale-test-env.sh；参数与前置环境见下方说明及所属 README。
# 清理上一轮 sim / 测试遗留的容器(幂等,随时可跑)。
#
# 为什么需要:teardown 靠"正常退出"才触发,但 kill -9 / IDE 强停 / mvn 中断会绕过
# EXIT trap,Ryuk 也收不到信号 → 孤儿 testcontainers + biz-shard 容器残留。
# 业界标准是 clean-at-START(幂等)而非只靠退出清理:sim-harness.sh 启动时先调本脚本,
# 这样无论上次怎么死的,下次一开跑就自净;也可手动 `bash scripts/local/clean-stale-test-env.sh`。
#
# 只清"残留",不碰 batch-local 受管 dev 栈(batch-postgres-primary/valkey/minio/kafka):
#   - 已退出的 testcontainers:label org.testcontainers=true 且无 org.testcontainers.hash
#     (反复 kill-9 / Ryuk 没机会清的;保留显式 withReuse 容器和仍运行的未知容器)。
#   - biz-shard:仅清理当前 Compose project 下显式带本仓库 SIM 所有权标签的临时分片；持久分片保留。
#   - SDK E2E 本机样例 worker:go run/java/python/rust/ts 等后台进程若父脚本被 kill,
#     EXIT trap 来不及执行,会继续消费 Kafka 并干扰后续 sim。
set -euo pipefail

removed=0
stopped_processes=0
cleanup_errors=0

current_ancestry() {
  local pid="$$" parent
  local ancestry=" $$ "
  while [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]]; do
    parent="$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
    [[ "$parent" =~ ^[0-9]+$ ]] || break
    ancestry+="$parent "
    pid="$parent"
  done
  printf '%s' "$ancestry"
}

stop_local_processes() {
  local pattern="$1" label="$2" pids pid ancestry
  local -a candidates=() remaining=()
  ancestry="$(current_ancestry)"
  pids="$(pgrep -f "$pattern" 2>/dev/null || true)"
  if [ -z "$pids" ]; then
    return 0
  fi

  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    case "$ancestry" in *" $pid "*) continue ;; esac
    candidates+=("$pid")
  done <<< "$pids"
  [ "${#candidates[@]}" -gt 0 ] || return 0
  printf '  停止 %d 个 %s 残留进程\n' "${#candidates[@]}" "$label"
  for pid in "${candidates[@]}"; do
    if ! kill -TERM "$pid" 2>/dev/null; then
      printf '  无法发送 TERM 到 %s PID=%s\n' "$label" "$pid" >&2
      cleanup_errors=$((cleanup_errors + 1))
    fi
  done
  sleep 1
  for pid in "${candidates[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      remaining+=("$pid")
    fi
  done
  for pid in "${remaining[@]}"; do
    if ! kill -KILL "$pid" 2>/dev/null; then
      printf '  无法发送 KILL 到 %s PID=%s\n' "$label" "$pid" >&2
      cleanup_errors=$((cleanup_errors + 1))
    fi
  done
  sleep 1
  for pid in "${candidates[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      printf '  %s PID=%s 仍存活\n' "$label" "$pid" >&2
      cleanup_errors=$((cleanup_errors + 1))
    else
      stopped_processes=$((stopped_processes + 1))
    fi
  done
}

# 0) SDK E2E 样例 worker 残留。匹配样例目录和编译后的固定二进制名,避免误杀正常业务进程。
stop_local_processes "examples/self-hosted-sdk/sample-tenant-worker-(go|python|typescript|java|rust)" "SDK sample worker"
stop_local_processes "sample-tenant-worker-(go|rust|java)" "SDK sample worker"

command -v docker >/dev/null 2>&1 || {
  echo "docker CLI 不可用，无法确认容器残留已清理" >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Docker daemon 不可用，无法确认容器残留已清理" >&2
  exit 1
}

# 1) 已退出的孤儿 testcontainers。运行中容器没有足够的所有权证据，不能自动杀掉。
orphans=$(docker ps -aq --filter "label=org.testcontainers=true" --filter "status=exited" 2>/dev/null | while read -r cid; do
  [ -n "$cid" ] || continue
  labels="$(docker inspect "$cid" --format '{{json .Config.Labels}}' 2>/dev/null)" || {
    printf '无法读取 testcontainer %s 的所有权标签\n' "$cid" >&2
    exit 1
  }
  if ! printf '%s' "$labels" | grep -F "org.testcontainers.hash" >/dev/null; then
    printf '%s\n' "$cid"
  fi
done)
if [ -n "$orphans" ]; then
  n=0
  while IFS= read -r cid; do
    [ -n "$cid" ] || continue
    if docker rm -f "$cid" >/dev/null; then
      removed=$((removed + 1))
      n=$((n + 1))
    else
      printf '无法移除 testcontainer %s\n' "$cid" >&2
      cleanup_errors=$((cleanup_errors + 1))
    fi
  done <<< "$orphans"
  printf '  已移除 %d 个孤儿 testcontainer\n' "$n"
fi

running_unowned=0
running_testcontainers=$(docker ps -q --filter "label=org.testcontainers=true" 2>/dev/null)
if [ -n "$running_testcontainers" ]; then
  while IFS= read -r cid; do
    [ -n "$cid" ] || continue
    labels="$(docker inspect "$cid" --format '{{json .Config.Labels}}' 2>/dev/null)" || {
      printf '无法读取运行中 testcontainer %s 的所有权标签\n' "$cid" >&2
      exit 1
    }
    if ! printf '%s' "$labels" | grep -F "org.testcontainers.hash" >/dev/null; then
      running_unowned=$((running_unowned + 1))
    fi
  done <<< "$running_testcontainers"
fi

# 2) 当前 Compose project 下 SIM harness 创建的临时 biz-shard。双标签限定本仓库和部署环境；
#    -v 只删除该自有容器附带的匿名数据卷，不触碰 Compose 命名卷或其它项目。
shard_project="${COMPOSE_PROJECT_NAME:-batch-platform}"
shards=$(docker ps -aq \
  --filter 'label=io.github.pinpols.batch.testcontainers.owner=file-batch-system' \
  --filter "label=io.github.pinpols.batch.sim.project=${shard_project}" 2>/dev/null)
if [ -n "$shards" ]; then
  n=0
  while IFS= read -r cid; do
    [ -n "$cid" ] || continue
    if docker rm -fv "$cid" >/dev/null; then
      removed=$((removed + 1))
      n=$((n + 1))
    else
      printf '无法移除当前 SIM biz-shard %s\n' "$cid" >&2
      cleanup_errors=$((cleanup_errors + 1))
    fi
  done <<< "$shards"
  printf '  已移除 %d 个当前 SIM biz-shard 残留容器及匿名卷\n' "$n"
fi

if [ "$cleanup_errors" -gt 0 ]; then
  printf '  清理失败项=%d；不能报告清理完成\n' "$cleanup_errors" >&2
  exit 1
fi
printf '  本脚本管理范围：已移除容器=%d，已停止 SDK 样例进程=%d\n' "$removed" "$stopped_processes"
if [ "$running_unowned" -gt 0 ]; then
  printf '  保留 %d 个运行中且无复用标签的 Testcontainers(缺少所有权证据，不自动删除)\n' "$running_unowned"
fi
echo "  持久 SFTP/MockServer/MinIO、未标记分片、其它 Compose project、运行中未知容器和开发栈不属于本脚本清理范围"
exit 0
