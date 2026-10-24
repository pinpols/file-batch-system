#!/usr/bin/env bash
# P2 tenant-routing 基础设施验证:起两片真实 PG,跑活体路由测试证明租户落到选定实例。
#
#   shard-0 = 已在跑的 primary 容器的 batch_business(基线库)
#   shard-1 = 本脚本通过 BIZ_SHARD_1_CONTAINER / BIZ_SHARD_1_PORT 指向的验证实例
#
# 因 compose network 为 external 且本仓多 worktree 共用同一运行栈(见记忆:worktree env 污染),
# 这里用 docker run 把 shard-1 直接挂到运行中栈的网络上,避免 compose env/project 解析问题——
# compose 里的 postgres-biz-shard-1 service 是「生产形态」声明,本脚本是「就地验证」路径。
#
# 凭据从 secrets/biz-shards/<key>.env 读(不在仓库 / 不在 placement 表),注入活体测试。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"

SECRETS_DIR="${BIZ_SHARD_SECRETS_DIR:-secrets/biz-shards}"
SHARD1_PORT="${BIZ_SHARD_1_PORT:-15442}"

echo "==> 1/4 确保 shard-0 secret 就绪(缺则从 .example 生成;shard-0=primary,无需 provision)"
mkdir -p "$SECRETS_DIR"
expected_s0_url="jdbc:postgresql://$(batch_format_host_port "${PGHOST:-localhost}" "${POSTGRES_PORT:-15432}")/$BUSINESS_DB?currentSchema=biz&reWriteBatchedInserts=true"
expected_s0_user="${BIZ_SHARD_0_USERNAME:-$POSTGRES_USER}"
expected_s0_pass="${BIZ_SHARD_0_PASSWORD:-$POSTGRES_PASSWORD}"
if [ -f "$SECRETS_DIR/shard-0.env" ]; then
  # shellcheck disable=SC1090 # 本地 SIM 临时凭据文件。
  source "$SECRETS_DIR/shard-0.env"
  if [[ "${BIZ_SHARD_URL:-}" != "$expected_s0_url" \
      || "${BIZ_SHARD_USERNAME:-}" != "$expected_s0_user" \
      || "${BIZ_SHARD_PASSWORD:-}" != "$expected_s0_pass" ]]; then
    echo "✗ shard-0 secret 与当前 BUSINESS_DB / 凭据不匹配，拒绝验证旧目标" >&2
    exit 1
  fi
else
  umask 077
  {
    printf 'BIZ_SHARD_URL="%s"\n' "$expected_s0_url"
    printf 'BIZ_SHARD_USERNAME=%s\n' "$expected_s0_user"
    printf 'BIZ_SHARD_PASSWORD=%s\n' "$expected_s0_pass"
  } > "$SECRETS_DIR/shard-0.env"
  chmod 600 "$SECRETS_DIR/shard-0.env"
  echo "    根据当前环境生成 $SECRETS_DIR/shard-0.env"
fi

echo "==> 2/4 provision shard-1(复用 provision-biz-shard.sh:起 PG+建表+角色+写 secret)"
scripts/local/provision-biz-shard.sh shard-1 "$SHARD1_PORT"

echo "==> 3/4 读取两片凭据(secrets)"
# shellcheck disable=SC1090
set -a; . "$SECRETS_DIR/shard-0.env"; S0_URL="$BIZ_SHARD_URL"; S0_USER="$BIZ_SHARD_USERNAME"; S0_PASS="$BIZ_SHARD_PASSWORD"; set +a
set -a; . "$SECRETS_DIR/shard-1.env"; S1_URL="$BIZ_SHARD_URL"; S1_USER="$BIZ_SHARD_USERNAME"; S1_PASS="$BIZ_SHARD_PASSWORD"; set +a
echo "    shard-0 $S0_URL"
echo "    shard-1 $S1_URL"

echo "==> 4/4 跑活体路由测试(真实双实例 + platform placement 表;config 路由 + 表驱动覆盖两证明)"
# platform 库 = primary 的 batch_platform(表驱动 placement 表所在),dev 默认账密
PLATFORM_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-15432}/${PLATFORM_DB}"
BIZ_SHARD_0_URL="$S0_URL" BIZ_SHARD_0_USERNAME="$S0_USER" BIZ_SHARD_0_PASSWORD="$S0_PASS" \
BIZ_SHARD_1_URL="$S1_URL" BIZ_SHARD_1_USERNAME="$S1_USER" BIZ_SHARD_1_PASSWORD="$S1_PASS" \
BIZ_PLATFORM_URL="$PLATFORM_URL" BIZ_PLATFORM_USERNAME="$S0_USER" BIZ_PLATFORM_PASSWORD="$S0_PASS" \
  mvn -q -pl batch-common test \
    -Dtest='BusinessMultiShardRoutingLiveTest,DbTablePlacementResolverTest' \
    -Dsurefire.failIfNoSpecifiedTests=false

echo "==> ✅ biz 分片基础设施验证通过(两片真实 PG,路由按 placement 落到选定实例)"
