#!/usr/bin/env bash
# P2 tenant-routing 基础设施验证:起两片真实 PG,跑活体路由测试证明租户落到选定实例。
#
#   shard-0 = 已在跑的 postgres-primary 的 batch_business(基线库)
#   shard-1 = 本脚本拉起的 batch-postgres-biz-shard-1
#
# 因 compose network 为 external 且本仓多 worktree 共用同一运行栈(见记忆:worktree env 污染),
# 这里用 docker run 把 shard-1 直接挂到运行中栈的网络上,避免 compose env/project 解析坑——
# compose 里的 postgres-biz-shard-1 service 是「生产形态」声明,本脚本是「就地验证」路径。
#
# 凭据从 secrets/biz-shards/<key>.env 读(不在仓库 / 不在 placement 表),注入活体测试。
set -euo pipefail
cd "$(dirname "$0")/../.."

SECRETS_DIR="secrets/biz-shards"
SHARD1_NAME="batch-postgres-biz-shard-1"
SHARD1_PORT="${BIZ_SHARD_1_PORT:-15442}"
PRIMARY_NAME="batch-postgres-primary"

echo "==> 1/4 确保两片 secrets 就绪(缺则从 .example 生成 dev 默认)"
for key in shard-0 shard-1; do
  if [ ! -f "$SECRETS_DIR/$key.env" ]; then
    cp "$SECRETS_DIR/$key.env.example" "$SECRETS_DIR/$key.env"
    echo "    生成 $SECRETS_DIR/$key.env(dev 默认账密)"
  fi
done

echo "==> 2/4 拉起 shard-1(docker run,挂到运行栈网络)"
if ! docker ps --format '{{.Names}}' | grep -qx "$PRIMARY_NAME"; then
  echo "    ✗ 基线栈 $PRIMARY_NAME 未运行——shard-0 = 它的 batch_business,请先起基线栈" >&2
  exit 1
fi
NETWORK="$(docker inspect "$PRIMARY_NAME" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
IMAGE="$(docker inspect "$PRIMARY_NAME" --format '{{.Config.Image}}')"
echo "    network=$NETWORK image=$IMAGE"

if docker ps -a --format '{{.Names}}' | grep -qx "$SHARD1_NAME"; then
  echo "    复用已存在的 $SHARD1_NAME"
  docker start "$SHARD1_NAME" >/dev/null
else
  docker run -d --name "$SHARD1_NAME" --network "$NETWORK" \
    -p "$SHARD1_PORT:5432" \
    -e POSTGRES_DB=batch_platform \
    -e POSTGRES_USER=batch_user \
    -e POSTGRES_PASSWORD=batch_pass_123 \
    -e POSTGRES_INITDB_ARGS="--encoding=UTF8" \
    -v "$(pwd)/docker/postgres-biz-shard-1/init:/docker-entrypoint-initdb.d:ro" \
    -v "$(pwd)/scripts/db/business/create_biz_tables.sql:/biz-sql/create_biz_tables.sql:ro" \
    "$IMAGE" >/dev/null
  echo "    已创建 $SHARD1_NAME(host :$SHARD1_PORT)"
fi

echo "    等待 shard-1 接受连接 + 完成 init ..."
for i in $(seq 1 40); do
  if docker exec "$SHARD1_NAME" pg_isready -U batch_user -d batch_business >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 2
done
[ "${ready:-0}" = 1 ] || { echo "    ✗ shard-1 未就绪"; docker logs --tail 30 "$SHARD1_NAME"; exit 1; }
echo "    shard-1 就绪"

echo "==> 3/4 读取两片凭据(secrets)"
# shellcheck disable=SC1090
set -a; . "$SECRETS_DIR/shard-0.env"; S0_URL="$BIZ_SHARD_URL"; S0_USER="$BIZ_SHARD_USERNAME"; S0_PASS="$BIZ_SHARD_PASSWORD"; set +a
set -a; . "$SECRETS_DIR/shard-1.env"; S1_URL="$BIZ_SHARD_URL"; S1_USER="$BIZ_SHARD_USERNAME"; S1_PASS="$BIZ_SHARD_PASSWORD"; set +a
echo "    shard-0 $S0_URL"
echo "    shard-1 $S1_URL"

echo "==> 4/4 跑活体路由测试(真实双实例 + platform placement 表;config 路由 + 表驱动覆盖两证明)"
# platform 库 = primary 的 batch_platform(表驱动 placement 表所在),dev 默认账密
PLATFORM_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-15432}/batch_platform"
BIZ_SHARD_0_URL="$S0_URL" BIZ_SHARD_0_USERNAME="$S0_USER" BIZ_SHARD_0_PASSWORD="$S0_PASS" \
BIZ_SHARD_1_URL="$S1_URL" BIZ_SHARD_1_USERNAME="$S1_USER" BIZ_SHARD_1_PASSWORD="$S1_PASS" \
BIZ_PLATFORM_URL="$PLATFORM_URL" BIZ_PLATFORM_USERNAME="$S0_USER" BIZ_PLATFORM_PASSWORD="$S0_PASS" \
  mvn -q -pl batch-common test \
    -Dtest='BusinessMultiShardRoutingLiveTest,DbTablePlacementResolverTest' \
    -Dsurefire.failIfNoSpecifiedTests=false

echo "==> ✅ biz 分片基础设施验证通过(两片真实 PG,路由按 placement 落到选定实例)"
