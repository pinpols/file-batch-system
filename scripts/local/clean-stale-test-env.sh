#!/usr/bin/env bash
# 清理上一轮 sim / 测试遗留的容器(幂等,随时可跑)。
#
# 为什么需要:teardown 靠"正常退出"才触发,但 kill -9 / IDE 强停 / mvn 中断会绕过
# EXIT trap,Ryuk 也收不到信号 → 孤儿 testcontainers + biz-shard 容器残留。
# 业界标准是 clean-at-START(幂等)而非只靠退出清理:sim-harness.sh 启动时先调本脚本,
# 这样无论上次怎么死的,下次一开跑就自净;也可手动 `bash scripts/local/clean-stale-test-env.sh`。
#
# 只清"残留",不碰 batch-local 受管 dev 栈(batch-postgres-primary/valkey/minio/kafka):
#   - 孤儿 testcontainers:label org.testcontainers=true 且无 reuse-hash(反复 kill-9 / Ryuk
#     没机会清的;保留显式 withReuse 容器,它们带 reuse-hash)。
#   - biz-shard:名字 batch-postgres-biz-shard-*(sim routing 用 docker run 直起,不归 compose)。
set -uo pipefail

command -v docker >/dev/null 2>&1 || { echo "docker 不可用,跳过清理"; exit 0; }

removed=0

# 1) 孤儿 testcontainers(无 reuse-hash label;running + exited 都清)
orphans=$(docker ps -aq --filter "label=org.testcontainers=true" 2>/dev/null | while read -r cid; do
  [ -n "$cid" ] || continue
  if ! docker inspect "$cid" --format '{{json .Config.Labels}}' 2>/dev/null | grep -q "reuse-hash"; then
    printf '%s\n' "$cid"
  fi
done)
if [ -n "$orphans" ]; then
  n=$(printf '%s\n' "$orphans" | grep -c .)
  printf '  清理 %d 个孤儿 testcontainer\n' "$n"
  printf '%s\n' "$orphans" | xargs docker rm -f >/dev/null 2>&1 || true
  removed=$((removed + n))
fi

# 2) biz-shard 残留(sim routing 直起的 docker run 容器,不归 compose 管)
shards=$(docker ps -aq --filter "name=batch-postgres-biz-shard-" 2>/dev/null)
if [ -n "$shards" ]; then
  n=$(printf '%s\n' "$shards" | grep -c .)
  printf '  清理 %d 个 biz-shard 残留容器\n' "$n"
  printf '%s\n' "$shards" | xargs docker rm -f >/dev/null 2>&1 || true
  removed=$((removed + n))
fi

if [ "$removed" -eq 0 ]; then
  echo "  无残留容器,环境干净"
fi
exit 0
