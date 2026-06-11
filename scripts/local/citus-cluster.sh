#!/usr/bin/env bash
# citus-cluster.sh — 本地 Citus 3 节点集群管理(1 coordinator + 2 worker)。
# 固化 2026-06-11 W8/最后一公里实测步骤;全部 GUC 为实测必需:
#   - citus.propagate_set_commands='local' :RLS SET LOCAL 透传硬前提(POC gates 文档)
#   - citus.max_shared_pool_size          :coordinator→worker 连接扇出上限(防 too many clients)
#   - citus.enable_unsafe_triggers        :workflow_run 租户一致性触发器(colocate 后语义安全)
#
# 用法:
#   bash scripts/local/citus-cluster.sh up       # 起 3 容器 + 注册 worker + GUC
#   bash scripts/local/citus-cluster.sh initdb   # 建 batch_platform(扩展/schema/per-db 节点注册)
#   bash scripts/local/citus-cluster.sh status   # 节点与 GUC 状态
#   bash scripts/local/citus-cluster.sh down     # 销毁(--rm 容器,数据不保留)
#
# 之后接平台库部署:
#   起任一分支服务(-Dspring.datasource.url=jdbc:postgresql://localhost:25432/batch_platform
#                  -Dspring.datasource.username=postgres -Dspring.datasource.password=poc)
#   让 Flyway 跑完 → 停服务 → psql < scripts/db/citus/01-distribute.sql → 重启服务
set -euo pipefail

IMG=citusdata/citus:13.0
NET=citus-poc
COORD_PORT=25432
PASS=poc

coord() { docker exec ${1:--i} citus-coord psql -U postgres "${@:2}"; }

case "${1:-status}" in
  up)
    docker network create $NET 2>/dev/null || true
    docker run -d --rm --name citus-coord --network $NET -p ${COORD_PORT}:5432 -e POSTGRES_PASSWORD=$PASS $IMG >/dev/null
    docker run -d --rm --name citus-w1 --network $NET -e POSTGRES_PASSWORD=$PASS $IMG >/dev/null
    docker run -d --rm --name citus-w2 --network $NET -e POSTGRES_PASSWORD=$PASS $IMG >/dev/null
    # citus 镜像 entrypoint 初始化后会重启 postgres——pg_isready 会过早放行,
    # 必须用真 SQL 探活且连续 2 次成功(跨重启稳定窗口)。
    for c in citus-coord citus-w1 citus-w2; do
      ok=0
      until [ $ok -ge 2 ]; do
        if docker exec $c psql -U postgres -tAc "SELECT 1" >/dev/null 2>&1; then ok=$((ok+1)); else ok=0; fi
        sleep 1
      done
      echo "$c ready"
    done
    docker exec citus-coord psql -U postgres -c "ALTER SYSTEM SET citus.propagate_set_commands = 'local';"
    docker exec citus-coord psql -U postgres -c "ALTER SYSTEM SET citus.max_shared_pool_size = 25;"
    docker exec citus-coord psql -U postgres -c "ALTER SYSTEM SET citus.enable_unsafe_triggers = on;"
    docker exec citus-coord psql -U postgres -c "SELECT pg_reload_conf();" >/dev/null
    docker exec -i citus-coord psql -U postgres -v ON_ERROR_STOP=1 <<'EOF'
SELECT citus_add_node('citus-w1', 5432);
SELECT citus_add_node('citus-w2', 5432);
EOF
    echo "OK: 集群就绪(coordinator localhost:${COORD_PORT})"
    ;;
  initdb)
    docker exec citus-coord psql -U postgres -c "CREATE DATABASE batch_platform;" || true
    docker exec citus-w1 psql -U postgres -c "CREATE DATABASE batch_platform;" || true
    docker exec citus-w2 psql -U postgres -c "CREATE DATABASE batch_platform;" || true
    docker exec -i citus-coord psql -U postgres -d batch_platform -v ON_ERROR_STOP=1 <<'EOF'
CREATE EXTENSION IF NOT EXISTS citus;
CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS quartz;
EOF
    docker exec citus-w1 psql -U postgres -d batch_platform -c "CREATE EXTENSION IF NOT EXISTS citus;"
    docker exec citus-w2 psql -U postgres -d batch_platform -c "CREATE EXTENSION IF NOT EXISTS citus;"
    docker exec -i citus-coord psql -U postgres -d batch_platform -v ON_ERROR_STOP=1 <<'EOF'
SELECT citus_add_node('citus-w1', 5432);
SELECT citus_add_node('citus-w2', 5432);
SELECT count(*) AS workers FROM citus_get_active_worker_nodes();
EOF
    echo "OK: batch_platform 就绪(citus 元数据 per-database,节点已在库内注册)"
    ;;
  status)
    docker ps --format '{{.Names}} {{.Status}}' | grep -E "citus-" || { echo "集群未运行"; exit 1; }
    docker exec citus-coord psql -U postgres -tAc "SHOW citus.propagate_set_commands; SHOW citus.max_shared_pool_size; SHOW citus.enable_unsafe_triggers;"
    docker exec citus-coord psql -U postgres -d batch_platform -tAc "SELECT 'workers='||count(*) FROM citus_get_active_worker_nodes();" 2>/dev/null || echo "(batch_platform 未 initdb)"
    ;;
  down)
    docker stop citus-coord citus-w1 citus-w2 2>/dev/null || true
    docker network rm $NET 2>/dev/null || true
    echo "OK: 集群已销毁"
    ;;
  *) echo "用法: $0 {up|initdb|status|down}" >&2; exit 2 ;;
esac
