#!/usr/bin/env bash
# =========================================================
# env-citus.sh:把 sim 脚本的 psql 路由切到 Citus 分布式拓扑。
#
# 拓扑(STRICT 库分离,见 docs/backlog/citus-introduction-plan):
#   platform(batch.* 控制面)  → citus-coord / batch_platform   / postgres
#   business(biz.*  业务数据)  → batch-postgres-primary / batch_business_part / batch_user
#
# business 不进 Citus(协调器无 biz schema),仍走原 PG,只是切到 _part 库。
#
# 用法:
#   source scripts/sim/env-citus.sh
#   bash scripts/sim/08-import-stage2.sh
#
# 必须在跑 sim 脚本"之前"source 到当前 shell;子进程继承这些 export,
# env-common.sh 的 `${VAR:-默认}` 会让位给这里已 export 的值。
# 不 source 时,sim 脚本默认仍是单机 batch-postgres-primary(双栈安全)。
# =========================================================

# platform → Citus 协调器
export PG_PLATFORM_CONTAINER="${PG_PLATFORM_CONTAINER:-citus-coord}"
export PG_PLATFORM_DB="${PG_PLATFORM_DB:-batch_platform}"
export PG_PLATFORM_USER="${PG_PLATFORM_USER:-postgres}"

# business → 原 PG 的分区库(不进 Citus)
export PG_BUSINESS_CONTAINER="${PG_BUSINESS_CONTAINER:-batch-postgres-primary}"
export PG_BUSINESS_DB="${PG_BUSINESS_DB:-batch_business_part}"
export PG_BUSINESS_USER="${PG_BUSINESS_USER:-batch_user}"

cat <<EOF
══════ Citus sim 路由已 export ══════
  platform  → ${PG_PLATFORM_CONTAINER} / ${PG_PLATFORM_DB} / ${PG_PLATFORM_USER}
  business  → ${PG_BUSINESS_CONTAINER} / ${PG_BUSINESS_DB} / ${PG_BUSINESS_USER}

sim 脚本内统一用 pg_platform / pg_business helper;旧内联 batch-postgres-primary
已逐脚本迁移。worker / trigger 进程的 DB 连接由各自 spring 配置管(已指向 Citus)。
EOF
