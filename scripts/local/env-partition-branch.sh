#!/usr/bin/env bash
# env-partition-branch.sh — feature/partition-readiness 分支专用库管理。
#
# 背景:本分支的 Flyway V170+ 会把 outbox_event/job_instance 单向迁移为分区表,
# 严禁触碰共享本地库(batch_platform/batch_business)。全栈真实链路验证走本脚本
# 管理的专用库(全新建,Flyway 从 V1 直跑,零数据迁移——开发期决策,见
# docs/plans/2026-06-10-partition-citus-paving-plan.md 运营红线)。
#
# 用法:
#   bash scripts/local/env-partition-branch.sh init   # 建 *_part 两库 + business 手工脚本初始化
#   bash scripts/local/env-partition-branch.sh reset  # DROP 重建
#   bash scripts/local/env-partition-branch.sh env    # 打印起服务前要 eval 的 JAVA_OPTS(-D 系统属性)
#   bash scripts/local/env-partition-branch.sh guard  # 起服务后立查共享库零污染(必跑)
#
# 全栈验证流程:
#   bash scripts/local/env-partition-branch.sh init
#   bash scripts/local/stop-all.sh                    # 停主分支服务
#   eval "$(bash scripts/local/env-partition-branch.sh env)"
#   bash scripts/local/restart.sh orchestrator trigger console worker-import \
#        worker-export worker-process worker-dispatch worker-atomic
set -euo pipefail
PGC="${PG_CONTAINER:-batch-postgres-primary}"
PGU="${PG_USER:-batch_user}"

case "${1:-env}" in
  reset)
    docker exec "$PGC" psql -U "$PGU" -d batch_platform -c "DROP DATABASE IF EXISTS batch_platform_part WITH (FORCE);"
    docker exec "$PGC" psql -U "$PGU" -d batch_platform -c "DROP DATABASE IF EXISTS batch_business_part WITH (FORCE);"
    "$0" init
    ;;
  init)
    docker exec "$PGC" psql -U "$PGU" -d batch_platform -tAc \
      "SELECT 1 FROM pg_database WHERE datname='batch_platform_part'" | grep -q 1 || \
      docker exec "$PGC" psql -U "$PGU" -d batch_platform -c "CREATE DATABASE batch_platform_part OWNER $PGU;"
    docker exec "$PGC" psql -U "$PGU" -d batch_platform -tAc \
      "SELECT 1 FROM pg_database WHERE datname='batch_business_part'" | grep -q 1 || \
      docker exec "$PGC" psql -U "$PGU" -d batch_platform -c "CREATE DATABASE batch_business_part OWNER $PGU;"
    ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
    docker exec -i "$PGC" psql -U "$PGU" -d batch_business_part -v ON_ERROR_STOP=1 -q \
      < "$ROOT/scripts/db/business/create_biz_tables.sql"
    docker exec -i "$PGC" psql -U "$PGU" -d batch_business_part -v ON_ERROR_STOP=1 -q \
      < "$ROOT/scripts/db/business/rls-phase-a.sql"
    echo "OK: batch_platform_part / batch_business_part 就绪(platform 表由首个服务启动时 Flyway V1..V172 建)"
    ;;
  env)
    # ⚠️ 2026-06-11 事故教训:application-local.yml 硬编码 jdbc URL(profile 配置压过
    # application.yml 的 ${BATCH_*_DB_URL} 占位符),裸 export 这两个变量不生效,
    # 曾导致分支服务连上共享库并把 V170/V171 跑上去(已回滚)。
    # 唯一可靠载体 = -D 系统属性(优先级压过一切 yml),经 restart.sh 的 ${JAVA_OPTS} 透传。
    cat <<'ENV'
export JAVA_OPTS="-Dspring.datasource.url=jdbc:postgresql://localhost:15432/batch_platform_part -Dbatch.datasource.business.url=jdbc:postgresql://localhost:15432/batch_business_part"
ENV
    ;;
  guard)
    # 起服务后立查:共享库 Flyway 顶必须仍 ≤169 且无分区父表;分支库顶必须 ≥170。
    SHARED_TOP=$(docker exec "$PGC" psql -U "$PGU" -d batch_platform -tAc       "SELECT COALESCE(max(version::int),0) FROM batch.flyway_schema_history WHERE version ~ '^[0-9]+$';")
    SHARED_PART=$(docker exec "$PGC" psql -U "$PGU" -d batch_platform -tAc       "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='batch' AND c.relkind='p';")
    PART_TOP=$(docker exec "$PGC" psql -U "$PGU" -d batch_platform_part -tAc       "SELECT COALESCE(max(version::int),0) FROM batch.flyway_schema_history WHERE version ~ '^[0-9]+$';" 2>/dev/null || echo 0)
    echo "共享库 Flyway 顶=$SHARED_TOP(应≤169) 分区父表=$SHARED_PART(应0) | 分支库 Flyway 顶=$PART_TOP(应≥170)"
    [[ "$SHARED_TOP" -le 169 && "$SHARED_PART" == "0" ]] || { echo "🔴 共享库被污染!立即 stop-all 并按 docs/plans 回滚附录处理"; exit 1; }
    ;;
  *) echo "用法: $0 {init|reset|env}" >&2; exit 2 ;;
esac
