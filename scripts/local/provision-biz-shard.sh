#!/usr/bin/env bash
# 幂等「加一片 biz 分片」:起 PG → 建库建表 → rls-phase-a(角色+授权+RLS)→ 只读排故角色 → 写 secrets。
# 重复跑安全(容器复用、SQL 全幂等、secret 存在不覆盖)。
#
#   用法: scripts/local/provision-biz-shard.sh <placement-key> <host-port>
#   例:   scripts/local/provision-biz-shard.sh shard-1 15442
#          scripts/local/provision-biz-shard.sh silo-big 15443
#
# 设计同 verify:用 docker run 把分片挂到运行栈网络(避免 compose external-network/env 问题)。
# compose 里的 postgres-biz-shard-1 是 shard-1 的「生产形态」声明;本脚本是通用「就地开片」路径。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=../lib/env-common.sh
# shellcheck disable=SC1091 # 运行时从仓库绝对路径加载。
source "$ROOT/scripts/lib/env-common.sh"
LOCAL_SQL_DIR="$ROOT/scripts/local/sql"

KEY="${1:-}"
PORT="${2:-}"
if [ -z "$KEY" ] || [ -z "$PORT" ]; then
  echo "用法: $0 <placement-key> <host-port>   例: $0 shard-1 15442" >&2
  exit 2
fi
if ! [[ "$BUSINESS_DB" =~ ^[A-Za-z0-9_]{1,63}$ && "$POSTGRES_DB" =~ ^[A-Za-z0-9_]{1,63}$ ]]; then
  echo "✗ BUSINESS_DB / POSTGRES_DB 必须是 1-63 位字母、数字或下划线" >&2
  exit 2
fi

SECRETS_DIR="${BIZ_SHARD_SECRETS_DIR:-secrets/biz-shards}"
CONTAINER="batch-postgres-biz-$KEY"
if [[ "$KEY" == "shard-1" ]]; then
  CONTAINER="${BIZ_SHARD_1_CONTAINER:-$CONTAINER}"
fi
PRIMARY_NAME="${PG_CONTAINER:-$BATCH_DEFAULT_POSTGRES_CONTAINER}"

# biz 角色密码:必须显式注入(SQL 脚本不再含默认密码)。本地默认复用本地 DB 密码;
# prod 请 export BIZ_WRITER_PASSWORD / BIZ_ADMIN_PASSWORD / BIZ_READONLY_PASSWORD /
# BIZ_READONLY_ALL_PASSWORD 覆盖为真实凭据(见 docs/runbook/multi-tenant-rls.md)。
BIZ_WRITER_PASSWORD="${BIZ_WRITER_PASSWORD:-$POSTGRES_PASSWORD}"
BIZ_ADMIN_PASSWORD="${BIZ_ADMIN_PASSWORD:-$POSTGRES_PASSWORD}"
BIZ_READONLY_PASSWORD="${BIZ_READONLY_PASSWORD:-$POSTGRES_PASSWORD}"
BIZ_READONLY_ALL_PASSWORD="${BIZ_READONLY_ALL_PASSWORD:-$POSTGRES_PASSWORD}"

echo "==> [${KEY}] 1/5 起 PG 容器(挂运行栈网络)"
if ! docker ps --format '{{.Names}}' | grep -Fx "$PRIMARY_NAME" >/dev/null; then
  echo "    ✗ 基线栈 $PRIMARY_NAME 未运行(取网络/镜像/凭据参照)" >&2
  exit 1
fi
NETWORK="$(docker inspect "$PRIMARY_NAME" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')"
IMAGE="$(docker inspect "$PRIMARY_NAME" --format '{{.Config.Image}}')"
echo "    network=$NETWORK image=$IMAGE container=$CONTAINER host=:$PORT"

if docker ps -a --format '{{.Names}}' | grep -Fx "$CONTAINER" >/dev/null; then
  if [ "${BATCH_SIM_RESIDUE_CONTAINER:-false}" = true ]; then
    existing_owner="$(docker inspect --format '{{ index .Config.Labels "io.github.pinpols.batch.testcontainers.owner" }}' "$CONTAINER" 2>/dev/null)" || existing_owner=""
    existing_sim_project="$(docker inspect --format '{{ index .Config.Labels "io.github.pinpols.batch.sim.project" }}' "$CONTAINER" 2>/dev/null)" || existing_sim_project=""
    existing_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$CONTAINER" 2>/dev/null)" || existing_project=""
    if [[ ! ( "$existing_owner" == file-batch-system \
        && "$existing_sim_project" == "${COMPOSE_PROJECT_NAME:-batch-platform}" ) \
        && "$existing_project" != "${COMPOSE_PROJECT_NAME:-batch-platform}" ]]; then
      echo "    ✗ $CONTAINER 已存在但无 SIM 或当前 Compose 所有权标记；拒绝把未知容器当作临时分片复用" >&2
      exit 2
    fi
  fi
  echo "    复用已存在容器"
  docker start "$CONTAINER" >/dev/null
else
  ownership_label_args=()
  if [ "${BATCH_SIM_RESIDUE_CONTAINER:-false}" = true ]; then
    ownership_label_args=(
      --label io.github.pinpols.batch.testcontainers.owner=file-batch-system
      --label "io.github.pinpols.batch.sim.project=${COMPOSE_PROJECT_NAME:-batch-platform}"
    )
  fi
  docker run -d --name "$CONTAINER" "${ownership_label_args[@]}" --network "$NETWORK" \
    -p "$PORT:5432" \
    -e POSTGRES_DB="$PLATFORM_DB" \
    -e POSTGRES_USER="$POSTGRES_USER" \
    -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -e POSTGRES_INITDB_ARGS="--encoding=UTF8" \
    -e LANG="$BATCH_LOCALE" \
    -e LC_ALL="$BATCH_LOCALE" \
    -v "$(pwd)/deploy/docker/biz-shard-init:/docker-entrypoint-initdb.d:ro" \
    -v "$(pwd)/scripts/db/business/create_biz_tables.sql:/biz-sql/create_biz_tables.sql:ro" \
    "$IMAGE" >/dev/null
  echo "    已创建容器"
fi

echo "==> [${KEY}] 2/5 等待 PostgreSQL 基准库就绪"
for _ in $(seq 1 40); do
  # BUSINESS_DB / POSTGRES_DB 可能是尚未创建的隔离库；postgres 维护库始终存在。
  if docker exec "$CONTAINER" pg_isready -U "$POSTGRES_USER" -d postgres >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
[ "${ready:-0}" = 1 ] || { echo "    ✗ 未就绪"; docker logs --tail 30 "$CONTAINER"; exit 1; }
echo "==> [${KEY}] 确认所选业务库和 biz schema($BUSINESS_DB)"
database_exists=$(docker exec -i "$CONTAINER" psql -X -U "$POSTGRES_USER" -d postgres \
  -v ON_ERROR_STOP=1 -v database_name="$BUSINESS_DB" -tA -f /dev/stdin \
  < scripts/db/backup/sql/database-exists.sql | tr -d '[:space:]')
if [[ "$database_exists" != 1 ]]; then
  docker exec -i "$CONTAINER" psql -X -U "$POSTGRES_USER" -d postgres \
    -v ON_ERROR_STOP=1 -v database_name="$BUSINESS_DB" -v database_owner="$POSTGRES_USER" \
    -f /dev/stdin < "$LOCAL_SQL_DIR/ensure-business-database.sql" >/dev/null
fi

n_tables=$(docker exec -i "$CONTAINER" psql -X -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$LOCAL_SQL_DIR/count-biz-schema-tables.sql")
if [[ "$n_tables" == 0 ]]; then
  has_staging=$(docker exec -i "$CONTAINER" psql -X -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
    -v ON_ERROR_STOP=1 -tA -f /dev/stdin < "$LOCAL_SQL_DIR/check-biz-shard-initialization-state.sql")
  if [[ "$has_staging" == t ]]; then
    echo "    ✗ $BUSINESS_DB 缺少 biz 表但已有 process_staging；拒绝在未知状态上重放业务 DDL" >&2
    exit 1
  fi
  echo "    空业务库，初始化 biz 表"
  docker exec -i "$CONTAINER" psql -X -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
    -v ON_ERROR_STOP=1 -f /dev/stdin < scripts/db/business/create_biz_tables.sql >/dev/null
elif ! docker exec -i "$CONTAINER" psql -X -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
    -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$LOCAL_SQL_DIR/check-biz-shard-ready.sql" | grep -Fx 1 >/dev/null; then
  echo "    ✗ $BUSINESS_DB 已有部分 biz 表但缺少必需关系；为避免覆盖部分数据，停止自动修复" >&2
  exit 1
fi
docker exec -i "$CONTAINER" psql -X -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$LOCAL_SQL_DIR/check-biz-shard-ready.sql" | grep -Fx 1 >/dev/null \
  || { echo "    ✗ $BUSINESS_DB 缺少 SIM 必需的业务表" >&2; exit 1; }
n_tables="$(docker exec -i "$CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$LOCAL_SQL_DIR/count-biz-schema-tables.sql")"
echo "    就绪;biz 表数=$n_tables"

echo "==> [${KEY}] 3/5 角色+授权+RLS(rls-phase-a,幂等)"
docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 \
  -v writer_password="$BIZ_WRITER_PASSWORD" -v admin_password="$BIZ_ADMIN_PASSWORD" \
  -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  < scripts/db/business/rls-phase-a.sql >/dev/null
echo "    writer/admin + grants + RLS 已施加"

echo "==> [${KEY}] 4/5 只读排故角色(diagnostic-readonly-role,幂等)"
docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 \
  -v readonly_password="$BIZ_READONLY_PASSWORD" -v readonly_all_password="$BIZ_READONLY_ALL_PASSWORD" \
  -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  < scripts/db/business/diagnostic-readonly-role.sql >/dev/null
echo "    readonly / readonly_all 已建"

echo "==> [${KEY}] 5/5 写/验证本次目标库 secret"
SECRET="$SECRETS_DIR/$KEY.env"
if [ -f "$SECRET" ]; then
  # shellcheck disable=SC1090 # 仓库 gitignore 下的本地运行凭据。
  source "$SECRET"
  expected_url="jdbc:postgresql://localhost:$PORT/$BUSINESS_DB?currentSchema=biz&reWriteBatchedInserts=true"
  if [[ "${BIZ_SHARD_URL:-}" != "$expected_url" \
      || "${BIZ_SHARD_USERNAME:-}" != "$POSTGRES_USER" \
      || "${BIZ_SHARD_PASSWORD:-}" != "$POSTGRES_PASSWORD" ]]; then
    echo "    ✗ $SECRET 指向不同数据库或凭据；拒绝复用过期 secret" >&2
    exit 1
  fi
  chmod 600 "$SECRET"
  echo "    $SECRET 与目标库匹配"
else
  mkdir -p "$SECRETS_DIR"
  umask 077
  {
    echo "# biz $KEY 凭据(provision-biz-shard.sh 生成)。prod 把 username 换 batch_business_writer。"
    echo "BIZ_SHARD_URL=\"jdbc:postgresql://localhost:$PORT/$BUSINESS_DB?currentSchema=biz&reWriteBatchedInserts=true\""
    echo "BIZ_SHARD_USERNAME=$POSTGRES_USER"
    echo "BIZ_SHARD_PASSWORD=$POSTGRES_PASSWORD"
  } > "$SECRET"
  chmod 600 "$SECRET"
  echo "    生成 $SECRET"
fi

echo "==> ✅ [${KEY}] 就绪:PG@localhost:$PORT,biz schema+表,4 角色(writer/admin/readonly/readonly_all)"
echo "    路由启用该片:在 routing.shards 增一项 key=$KEY,凭据从 $SECRET 注入(见 secrets/biz-shards/README.md)"
