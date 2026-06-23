#!/usr/bin/env bash
# =============================================================================
# sdk-e2e-common.sh — 可复用的 SDK × 真 orchestrator 端到端测试函数库
#
# 被两个入口 source(DRY):
#   - scripts/local/sdk-e2e-local.sh        本地(栈已起,开发机快速验证)
#   - scripts/ci/run-sdk-orchestrator-e2e.sh CI(自己 boot 栈后调同一套断言)
#
# 设计:SDK 自托管 worker 的 wire 路径与任务类型无关(BYO),所以这里用**一个 echo
# 任务类型**驱动整条链路,逐阶段断言 register→dispatch→claim→execute→report→terminal。
# 任务类型特定逻辑由内建 worker 的 *E2eIT / batteries 单测 / conformance fixture 覆盖,
# 不在本库职责内(见 docs/sdk/local-e2e-coverage.md 覆盖矩阵)。
#
# 入口需先 export 这些(均有默认):
#   PGHOST PGPORT PGUSER PGDATABASE BATCH_PLATFORM_DB_PASSWORD
#   ORCH_URL TRIGGER_URL KAFKA_HOST_PORT KAFKA_CONTAINER TENANT GOROOT_HINT
# =============================================================================

# ── 默认值(入口可覆盖)──────────────────────────────────────────────────────
: "${PGHOST:=localhost}"; : "${PGPORT:=15432}"; : "${PGUSER:=batch_user}"
: "${PGDATABASE:=batch_platform}"; : "${BATCH_PLATFORM_DB_PASSWORD:=batch_pass_123}"
: "${ORCH_URL:=http://localhost:18082}"; : "${TRIGGER_URL:=http://localhost:18081}"
: "${KAFKA_HOST_PORT:=19092}"; : "${KAFKA_CONTAINER:=batch-kafka}"; : "${TENANT:=default-tenant}"
: "${GOROOT_HINT:=/usr/local/opt/go/libexec}"
: "${SDK_E2E_JOB_CODE:=sdk_echo_demo_e2e}"
export PGPASSWORD="$BATCH_PLATFORM_DB_PASSWORD"

# repo root (库在 scripts/lib/)
SDK_E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

sdk_e2e_q()   { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tA -c "$1" 2>/dev/null; }
sdk_e2e_say() { printf '\n=== %s ===\n' "$*"; }
sdk_e2e_pass(){ printf '✅ %s\n' "$*"; }
sdk_e2e_fail(){ printf '❌ %s\n' "$*"; }
sdk_e2e_sha256(){ printf %s "$1" | { command -v sha256sum >/dev/null && sha256sum || shasum -a 256; } | cut -d' ' -f1; }

# 检查本地/CI 栈可达。返回非 0 让入口决定是否自己 boot。
sdk_e2e_check_stack() {
  curl -fsS "${ORCH_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"' || { sdk_e2e_fail "orchestrator not UP at ${ORCH_URL}"; return 1; }
  curl -fsS "${TRIGGER_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"' || { sdk_e2e_fail "trigger not UP at ${TRIGGER_URL}"; return 1; }
  sdk_e2e_q "SELECT 1" >/dev/null || { sdk_e2e_fail "postgres not reachable ${PGHOST}:${PGPORT}"; return 1; }
  docker exec "$KAFKA_CONTAINER" true 2>/dev/null || { sdk_e2e_fail "kafka container ${KAFKA_CONTAINER} not exec-able"; return 1; }
  sdk_e2e_pass "stack reachable"
}

# seed 一把真 API key(legacy sha256 路径)。echo 出明文 key。
# 用法:RAW=$(sdk_e2e_seed_api_key <keyName>)
sdk_e2e_seed_api_key() {
  local name="$1" raw pfx hsh
  raw="cikey$(openssl rand -hex 20)"; pfx="${raw:0:8}"; hsh="$(sdk_e2e_sha256 "$raw")"
  sdk_e2e_q "INSERT INTO batch.api_key(tenant_id,key_name,key_prefix,key_hash,key_hash_algo,scopes,enabled,created_at)
             VALUES('${TENANT}','${name}','${pfx}','${hsh}','sha256','*',true,now()) ON CONFLICT DO NOTHING" >/dev/null
  printf '%s' "$raw"
}

# 建一个 worker_group=sdk-self-hosted 的 echo job(clone atomic_shell_demo)。
sdk_e2e_ensure_echo_job() {
  sdk_e2e_q "DELETE FROM batch.job_definition WHERE job_code='${SDK_E2E_JOB_CODE}';
    INSERT INTO batch.job_definition (id,tenant_id,job_code,job_name,job_type,biz_type,schedule_type,schedule_expr,timezone,priority,queue_code,worker_group,calendar_code,window_code,trigger_mode,dag_enabled,shard_strategy,retry_policy,retry_max_count,timeout_seconds,execution_handler,param_schema,default_params,version,enabled,description,created_by,updated_by,created_at,updated_at,execution_mode)
    SELECT 990009,tenant_id,'${SDK_E2E_JOB_CODE}','SDK e2e echo',job_type,biz_type,schedule_type,schedule_expr,timezone,priority,queue_code,'sdk-self-hosted',calendar_code,window_code,trigger_mode,dag_enabled,shard_strategy,retry_policy,retry_max_count,timeout_seconds,execution_handler,param_schema,jsonb_build_object('taskType','echo'),version,true,'sdk-e2e','system','system',now(),now(),execution_mode
    FROM batch.job_definition WHERE job_code='atomic_shell_demo'" >/dev/null
  [[ "$(sdk_e2e_q "SELECT count(*) FROM batch.job_definition WHERE job_code='${SDK_E2E_JOB_CODE}'")" == "1" ]]
}

# pre-create worker 的 node-direct 派单 topic(SDK 消费 *.node.<workerCode>)。
sdk_e2e_precreate_topic() {
  local wc="$1"
  docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 \
    --create --if-not-exists --topic "batch.task.dispatch.atomic.node.${wc}" --partitions 3 --replication-factor 1 >/dev/null 2>&1
}

# 起样例 worker(后台)。echo 出 PID;日志写 $2。
# 用法:pid=$(sdk_e2e_start_worker <lang> <workerCode> <apiKey> <logFile>)
sdk_e2e_start_worker() {
  local lang="$1" wc="$2" raw="$3" logf="$4" root="$SDK_E2E_ROOT"
  case "$lang" in
    go)
      ( cd "$root/examples/self-hosted-sdk/sample-tenant-worker-go" \
        && GOROOT="$GOROOT_HINT" PATH="$GOROOT_HINT/bin:$PATH" \
           BATCH_BASE_URL="$ORCH_URL" BATCH_API_KEY="$raw" BATCH_TENANT_ID="$TENANT" \
           BATCH_WORKER_CODE="$wc" KAFKA_BOOTSTRAP="localhost:${KAFKA_HOST_PORT}" \
           go run . ) >"$logf" 2>&1 & echo $! ;;
    python)
      python -m pip install -q -e "$root/sdk/python" >/dev/null 2>&1
      ( cd "$root/examples/self-hosted-sdk/sample-tenant-worker-python" \
        && python -m pip install -q -e . >/dev/null 2>&1 \
        && BATCH_SDK_BASE_URL="$ORCH_URL" BATCH_SDK_API_KEY="$raw" BATCH_SDK_TENANT_ID="$TENANT" \
           BATCH_SDK_WORKER_CODE="$wc" BATCH_SDK_KAFKA_BOOTSTRAP="localhost:${KAFKA_HOST_PORT}" \
           python -m sample_tenant_worker ) >"$logf" 2>&1 & echo $! ;;
    typescript)
      # SDK 的 kafka adapter(sdk/typescript/kafka)import 'kafkajs',它从 SDK 自身的
      # node_modules 解析(样例经相对路径引 SDK,样例的 node_modules 不在 SDK 解析树上),
      # 故须先在 sdk/typescript 装 devDeps(含 kafkajs)。
      npm --prefix "$root/sdk/typescript" install --silent >/dev/null 2>&1
      ( cd "$root/examples/self-hosted-sdk/sample-tenant-worker-typescript" && npm install --silent >/dev/null 2>&1 \
        && BATCH_BASE_URL="$ORCH_URL" BATCH_API_KEY="$raw" BATCH_TENANT_ID="$TENANT" \
           BATCH_WORKER_CODE="$wc" KAFKA_BOOTSTRAP="localhost:${KAFKA_HOST_PORT}" \
           node --experimental-strip-types src/main.ts ) >"$logf" 2>&1 & echo $! ;;
    java)
      # 先 install SDK 到本地 m2(样例硬依赖 batch-worker-sdk:1.1.0),再 package 样例。
      # 样例用 maven-jar-plugin + copy-dependencies(lib/ classpath),非 Spring Boot 嵌套 fat-jar,
      # 启动不走嵌套 jar loader,本机可靠。Java 样例环境变量名是 BATCH_KAFKA(非 KAFKA_BOOTSTRAP)。
      mvn -q -f "$root/pom.xml" -pl sdk/java/core -am install -DskipTests -Dspotless.check.skip=true >>"$logf" 2>&1
      local jdir="$root/examples/self-hosted-sdk/sample-tenant-worker-java"
      mvn -q -f "$jdir/pom.xml" package -DskipTests -Dspotless.check.skip=true >>"$logf" 2>&1
      ( cd "$jdir" \
        && BATCH_BASE_URL="$ORCH_URL" BATCH_API_KEY="$raw" BATCH_TENANT_ID="$TENANT" \
           BATCH_WORKER_CODE="$wc" BATCH_KAFKA="localhost:${KAFKA_HOST_PORT}" \
           java -jar target/sample-tenant-worker-1.0.0-SNAPSHOT.jar ) >>"$logf" 2>&1 & echo $! ;;
    rust)
      # cargo at ~/.cargo/bin (not always on PATH); cmake on PATH for rdkafka build.
      # 先 build(冷编 rdkafka 经 cmake 较慢,避免吃掉 register 超时),再跑编好的二进制。
      # Rust 样例环境变量:KAFKA_BOOTSTRAP(同 Go/TS)。
      local cargo_path="$HOME/.cargo/bin"
      local rdir="$root/examples/self-hosted-sdk/sample-tenant-worker-rust"
      PATH="$cargo_path:/usr/local/bin:$PATH" cargo build --manifest-path "$rdir/Cargo.toml" >>"$logf" 2>&1
      ( cd "$rdir" \
        && PATH="$cargo_path:/usr/local/bin:$PATH" \
           BATCH_BASE_URL="$ORCH_URL" BATCH_API_KEY="$raw" BATCH_TENANT_ID="$TENANT" \
           BATCH_WORKER_CODE="$wc" KAFKA_BOOTSTRAP="localhost:${KAFKA_HOST_PORT}" \
           ./target/debug/sample-tenant-worker-rust ) >>"$logf" 2>&1 & echo $! ;;
    *) sdk_e2e_fail "unsupported lang '$lang'"; return 2 ;;
  esac
}

# 断言 register(真 API-key auth → worker_registry 落行)。
sdk_e2e_assert_register() {
  local wc="$1" pid="$2" logf="$3"
  for _ in $(seq 1 40); do
    kill -0 "$pid" 2>/dev/null || { sdk_e2e_fail "worker exited early"; tail -15 "$logf"; return 1; }
    [[ "$(sdk_e2e_q "SELECT count(*) FROM batch.worker_registry WHERE tenant_id='${TENANT}' AND worker_code='${wc}'")" == "1" ]] && return 0
    sleep 3
  done
  return 1
}

# 触发 launch + 轮询全链路,设全局 STAGE_* + 打印阶段结果。
sdk_e2e_run_chain() {
  local raw="$1" logf="$2" idemp st inst
  STAGE_DISPATCH=0 STAGE_EXECUTE=0 STAGE_REPORT=0 STAGE_TERMINAL=0
  idemp="sdk-e2e-$$-$(date +%s 2>/dev/null || echo 0)"
  curl -fsS -X POST "${TRIGGER_URL}/api/triggers/launch" \
    -H "Authorization: Bearer ${raw}" -H "Idempotency-Key: ${idemp}" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"${TENANT}\",\"jobCode\":\"${SDK_E2E_JOB_CODE}\",\"bizDate\":\"$(date +%F)\",\"triggerType\":\"API\"}" >/dev/null \
    || { sdk_e2e_fail "launch call failed"; return 1; }
  for _ in $(seq 1 40); do
    inst="$(sdk_e2e_q "SELECT id FROM batch.job_instance WHERE job_code='${SDK_E2E_JOB_CODE}' ORDER BY id DESC LIMIT 1")"
    [[ -n "$inst" ]] && break; sleep 1
  done
  [[ -n "$inst" ]] || { sdk_e2e_fail "no job_instance created"; return 1; }
  for _ in $(seq 1 40); do
    grep -qiE "echo handler|executing|claim" "$logf" && STAGE_DISPATCH=1 && STAGE_EXECUTE=1
    grep -qiE "report" "$logf" && ! grep -qiE "report failed|report.*5[0-9][0-9]|report.*error" "$logf" && STAGE_REPORT=1
    st="$(sdk_e2e_q "SELECT instance_status FROM batch.job_instance WHERE id=${inst}")"
    case "$st" in
      SUCCESS|COMPLETED|SUCCEEDED) STAGE_DISPATCH=1; STAGE_EXECUTE=1; STAGE_REPORT=1; STAGE_TERMINAL=1; break ;;
      FAILED|CANCELLED) break ;;
    esac
    sleep 3
  done
  [[ $STAGE_DISPATCH == 1 ]] && sdk_e2e_pass "dispatch+claim reached worker" || sdk_e2e_fail "task never reached worker"
  [[ $STAGE_EXECUTE  == 1 ]] && sdk_e2e_pass "handler executed" || sdk_e2e_fail "handler did not execute"
  [[ $STAGE_REPORT   == 1 ]] && sdk_e2e_pass "report accepted" || sdk_e2e_fail "report stage failed"
  [[ $STAGE_TERMINAL == 1 ]] && sdk_e2e_pass "job terminal SUCCESS" || sdk_e2e_fail "job not terminal-success (status=${st:-?})"
}

# 清理探针数据(job/worker/key/topic)。
sdk_e2e_cleanup() {
  local wc="$1"
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -q >/dev/null 2>&1 <<SQL
UPDATE batch.job_instance SET instance_status='CANCELLED' WHERE job_code='${SDK_E2E_JOB_CODE}' AND instance_status='RUNNING';
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM batch.job_instance WHERE job_code='${SDK_E2E_JOB_CODE}');
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM batch.job_instance WHERE job_code='${SDK_E2E_JOB_CODE}');
DELETE FROM batch.job_instance WHERE job_code='${SDK_E2E_JOB_CODE}';
DELETE FROM batch.job_definition WHERE job_code='${SDK_E2E_JOB_CODE}';
DELETE FROM batch.worker_registry WHERE worker_code='${wc}';
DELETE FROM batch.api_key WHERE key_name='${wc}';
DELETE FROM batch.trigger_request WHERE job_code='${SDK_E2E_JOB_CODE}';
SQL
  docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 \
    --delete --topic "batch.task.dispatch.atomic.node.${wc}" 2>/dev/null
}
