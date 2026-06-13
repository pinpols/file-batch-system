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

# ---------------------------------------------------------------------------
# Citus worker 进程启动连接(供 sim 脚本重启 worker 用,如 25-checkpoint-crash)。
# 配置集中在此、全部可被环境变量覆盖,各 stage 脚本不再内联硬编码 jdbc/密码/JVM 参数。
# 注:platform→25432 Citus 协调器(user/pass 与集群一致);business→15432 分区库,
#     business url 必须保留 stringtype=unspecified(否则 numeric/date 列写入报错)。
# ---------------------------------------------------------------------------
# preferQueryMode=simple:Citus 不能正确处理 JDBC 扩展协议(参数化)下的
# partial-index(带 WHERE 谓词)ON CONFLICT —— deparse 到 shard 时 arbiter 谓词丢失,
# 报 "no unique or exclusion constraint matching"。pipeline_instance 的
# uk_pipeline_instance_job_instance 即 partial 唯一索引,worker-core insertPipelineInstance
# 用它做 upsert(checkpoint 续跑路径)。simple 协议把参数内联为字面量(等同 psql 直跑,
# 实测通过),绕开该限制;双栈安全(单机 PG 亦可用,仅略损 server-side prepared 复用)。
# 产线级根治方向:partial 唯一索引改全列唯一索引 + mapper ON CONFLICT 去掉 WHERE(语义等价,
# NULL 行本就不冲突),需 rebuild worker-core,见 docs/backlog/citus-full-sim-suite。
export CITUS_PLATFORM_JDBC_URL="${CITUS_PLATFORM_JDBC_URL:-jdbc:postgresql://localhost:25432/batch_platform?preferQueryMode=simple}"
export CITUS_PLATFORM_JDBC_USER="${CITUS_PLATFORM_JDBC_USER:-postgres}"
export CITUS_PLATFORM_JDBC_PASS="${CITUS_PLATFORM_JDBC_PASS:-poc}"
export CITUS_BUSINESS_JDBC_URL="${CITUS_BUSINESS_JDBC_URL:-jdbc:postgresql://localhost:15432/batch_business_part?stringtype=unspecified&reWriteBatchedInserts=true}"
export CITUS_WORKER_JVM_OPTS="${CITUS_WORKER_JVM_OPTS:---enable-native-access=ALL-UNNAMED -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off}"
export CITUS_WORKER_POOL="${CITUS_WORKER_POOL:-6}"

# 本地-sim 专用:放行 DnsResolveGuard 对 loopback/私网地址的 SSRF 拦截。
# dispatch SFTP/NAS 等 channel 目标是 127.0.0.1:12222 这类本机容器,生产严禁开此开关
# (DnsResolveGuard 默认拦截私网,防 SSRF)。仅 sim 拓扑下经 citus_restart_worker 启动的
# worker 继承此 env;正常 go.sh 由 .env.local 注入。20-dispatch-stage5c 的 SFTP 投递依赖它。
export BATCH_SECURITY_SSRF_GUARD_ALLOW_PRIVATE="${BATCH_SECURITY_SSRF_GUARD_ALLOW_PRIVATE:-true}"

# 重启单个服务(Citus 连接);$1=jar 前缀(如 worker-import),余下参数透传为额外 -D(如 checkpoint)。
# 走 build/runtime-jars/<svc>.jar,日志写 logs/app/<svc>.log;须在项目根目录调用。secret 由调用方 env 继承。
citus_restart_worker() {
  local svc="$1"; shift
  pgrep -f "${svc}.jar" | xargs kill -9 2>/dev/null || true
  sleep 2
  nohup java ${CITUS_WORKER_JVM_OPTS} \
    -Dspring.datasource.url="${CITUS_PLATFORM_JDBC_URL}" \
    -Dspring.datasource.username="${CITUS_PLATFORM_JDBC_USER}" \
    -Dspring.datasource.password="${CITUS_PLATFORM_JDBC_PASS}" \
    -Dbatch.datasource.business.url="${CITUS_BUSINESS_JDBC_URL}" \
    -Dspring.datasource.hikari.maximum-pool-size="${CITUS_WORKER_POOL}" \
    -Dspring.datasource.hikari.connection-timeout=60000 \
    -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
    "$@" \
    -jar "build/runtime-jars/${svc}.jar" --spring.profiles.active=local \
    > "logs/app/${svc}.log" 2>&1 &
  disown
}
export -f citus_restart_worker 2>/dev/null || true

cat <<EOF
══════ Citus sim 路由已 export ══════
  platform  → ${PG_PLATFORM_CONTAINER} / ${PG_PLATFORM_DB} / ${PG_PLATFORM_USER}
  business  → ${PG_BUSINESS_CONTAINER} / ${PG_BUSINESS_DB} / ${PG_BUSINESS_USER}

sim 脚本内统一用 pg_platform / pg_business helper;旧内联 batch-postgres-primary
已逐脚本迁移。worker / trigger 进程的 DB 连接由各自 spring 配置管(已指向 Citus)。
EOF

# Citus:orchestrator outbox relay 按租户路由(消除 fan-out 全分片扫描),见 OutboxProperties.tenantRoutedPoll
export BATCH_OUTBOX_TENANT_ROUTED_POLL="${BATCH_OUTBOX_TENANT_ROUTED_POLL:-true}"
