#!/usr/bin/env bash
# 幂等「加一片 biz 分片」:起 PG → 建库建表 → rls-phase-a(角色+授权+RLS)→ 只读排故角色 → 写 secrets。
# 重复跑安全(容器复用、SQL 全幂等、secret 存在不覆盖)。
#
#   用法: scripts/local/provision-biz-shard.sh <placement-key> <host-port>
#   例:   scripts/local/provision-biz-shard.sh shard-1 15442
#          scripts/local/provision-biz-shard.sh silo-big 15443
#
# 设计同 verify:用 docker run 把分片挂到运行栈网络(避免 compose external-network/env 坑)。
# compose 里的 postgres-biz-shard-1 是 shard-1 的「生产形态」声明;本脚本是通用「就地开片」路径。
set -euo pipefail
cd "$(dirname "$0")/../.."

KEY="${1:-}"
PORT="${2:-}"
if [ -z "$KEY" ] || [ -z "$PORT" ]; then
  echo "用法: $0 <placement-key> <host-port>   例: $0 shard-1 15442" >&2
  exit 2
fi

SECRETS_DIR="secrets/biz-shards"
CONTAINER="batch-postgres-biz-$KEY"
PRIMARY_NAME="batch-postgres-primary"

echo "==> [${KEY}] 1/5 起 PG 容器(挂运行栈网络)"
if ! docker ps --format '{{.Names}}' | grep -qx "$PRIMARY_NAME"; then
  echo "    ✗ 基线栈 $PRIMARY_NAME 未运行(取网络/镜像/凭据参照)" >&2
  exit 1
fi
NETWORK="$(docker inspect "$PRIMARY_NAME" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
IMAGE="$(docker inspect "$PRIMARY_NAME" --format '{{.Config.Image}}')"
echo "    network=$NETWORK image=$IMAGE container=$CONTAINER host=:$PORT"

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "    复用已存在容器"
  docker start "$CONTAINER" >/dev/null
else
  docker run -d --name "$CONTAINER" --network "$NETWORK" \
    -p "$PORT:5432" \
    -e POSTGRES_DB=batch_platform \
    -e POSTGRES_USER=batch_user \
    -e POSTGRES_PASSWORD=batch_pass_123 \
    -e POSTGRES_INITDB_ARGS="--encoding=UTF8" \
    -v "$(pwd)/docker/biz-shard-init:/docker-entrypoint-initdb.d:ro" \
    -v "$(pwd)/scripts/db/business/create_biz_tables.sql:/biz-sql/create_biz_tables.sql:ro" \
    "$IMAGE" >/dev/null
  echo "    已创建容器"
fi

echo "==> [${KEY}] 2/5 等待就绪(含 init 建库建表)"
for i in $(seq 1 40); do
  if docker exec "$CONTAINER" pg_isready -U batch_user -d batch_business >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 2
done
[ "${ready:-0}" = 1 ] || { echo "    ✗ 未就绪"; docker logs --tail 30 "$CONTAINER"; exit 1; }
n_tables="$(docker exec "$CONTAINER" psql -U batch_user -d batch_business -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='biz'")"
echo "    就绪;biz 表数=$n_tables"

echo "==> [${KEY}] 3/5 角色+授权+RLS(rls-phase-a,幂等)"
docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U batch_user -d batch_business \
  < scripts/db/business/rls-phase-a.sql >/dev/null
echo "    writer/admin + grants + RLS 已施加"

echo "==> [${KEY}] 4/5 只读排故角色(diagnostic-readonly-role,幂等)"
docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U batch_user -d batch_business \
  < scripts/db/business/diagnostic-readonly-role.sql >/dev/null
echo "    readonly / readonly_all 已建"

echo "==> [${KEY}] 5/5 写 secrets(存在不覆盖)"
SECRET="$SECRETS_DIR/$KEY.env"
if [ -f "$SECRET" ]; then
  echo "    $SECRET 已存在,跳过"
else
  {
    echo "# biz $KEY 凭据(provision-biz-shard.sh 生成)。prod 把 username 换 batch_business_writer。"
    echo "BIZ_SHARD_URL=\"jdbc:postgresql://localhost:$PORT/batch_business?currentSchema=biz&reWriteBatchedInserts=true\""
    echo "BIZ_SHARD_USERNAME=batch_user"
    echo "BIZ_SHARD_PASSWORD=batch_pass_123"
  } > "$SECRET"
  echo "    生成 $SECRET"
fi

echo "==> ✅ [${KEY}] 就绪:PG@localhost:$PORT,biz schema+表,4 角色(writer/admin/readonly/readonly_all)"
echo "    路由启用该片:在 routing.shards 增一项 key=$KEY,凭据从 $SECRET 注入(见 secrets/biz-shards/README.md)"
