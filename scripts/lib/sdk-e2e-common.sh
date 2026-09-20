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
#   ORCH_URL TRIGGER_URL KAFKA_HOST_PORT KAFKA_CONTAINER KAFKA_BIN_DIR BATCH_SCRIPT_RUNTIME
#   TENANT GOROOT_HINT
# =============================================================================

# repo root (library lives under scripts/lib/)
SDK_E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=runtime-defaults.sh
source "$SDK_E2E_ROOT/scripts/lib/runtime-defaults.sh"

# ── 默认值(入口可覆盖)──────────────────────────────────────────────────────
: "${PGHOST:=localhost}"; : "${PGPORT:=$BATCH_DEFAULT_POSTGRES_PORT}"
: "${PGUSER:=$BATCH_DEFAULT_POSTGRES_USERNAME}"
: "${PGDATABASE:=$BATCH_DEFAULT_POSTGRES_DATABASE}"
: "${BATCH_PLATFORM_DB_PASSWORD:=$BATCH_DEFAULT_POSTGRES_PASSWORD}"
: "${POSTGRES_CONTAINER:=$BATCH_DEFAULT_POSTGRES_CONTAINER}"
: "${ORCH_URL:=http://localhost:$BATCH_DEFAULT_ORCHESTRATOR_PORT}"
: "${TRIGGER_URL:=http://localhost:$BATCH_DEFAULT_TRIGGER_PORT}"
: "${KAFKA_HOST_PORT:=$BATCH_DEFAULT_KAFKA_HOST_PORT}"
: "${KAFKA_CONTAINER:=$BATCH_DEFAULT_KAFKA_CONTAINER}"; : "${TENANT:=default-tenant}"
: "${KAFKA_CONTAINER_BIN_DIR:=$BATCH_DEFAULT_KAFKA_CONTAINER_BIN_DIR}"
if [[ -z "${GOROOT_HINT:-}" ]]; then
  if command -v go >/dev/null 2>&1; then
    GOROOT_HINT="$(go env GOROOT)"
  else
    GOROOT_HINT="/usr/local/go"
  fi
fi
: "${SDK_E2E_JOB_CODE:=sdk_echo_e2e_$$}"
: "${SDK_E2E_QUEUE_CODE:=sdk_e2e_queue}"
: "${SDK_E2E_REGISTER_WAIT_SECONDS:=120}"
: "${SDK_E2E_INSTANCE_WAIT_SECONDS:=120}"
: "${SDK_E2E_TERMINAL_WAIT_SECONDS:=180}"
: "${SDK_E2E_CLEANUP_WAIT_SECONDS:=30}"
: "${BATCH_SCRIPT_RUNTIME:=auto}"
SDK_E2E_IDEMPOTENCY_KEY=""
SDK_E2E_LAUNCH_SETTLED=0
export PGPASSWORD="$BATCH_PLATFORM_DB_PASSWORD"

SDK_E2E_SQL_DIR="$SDK_E2E_ROOT/scripts/lib/sql"

# 只引入公共地址格式化和 PostgreSQL 客户端入口，不重读环境文件；入口脚本
# 已经定义的环境变量必须保持优先级。
export BATCH_ENV_COMMON_HELPERS_ONLY=1
# shellcheck source=env-common.sh
# shellcheck disable=SC1091 # 运行时从仓库绝对路径加载。
source "$SDK_E2E_ROOT/scripts/lib/env-common.sh"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-$(batch_format_host_port "${KAFKA_HOST:-localhost}" "$KAFKA_HOST_PORT")}"

sdk_e2e_q_file() {
  local sql_file="$1"
  shift
  if command -v psql >/dev/null 2>&1; then
    psql -X -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
      -v ON_ERROR_STOP=1 -tA "$@" -f "$SDK_E2E_SQL_DIR/$sql_file" 2>/dev/null
  else
    docker exec -i -e PGPASSWORD="$PGPASSWORD" "$POSTGRES_CONTAINER" \
      psql -X -h localhost -p 5432 -U "$PGUSER" -d "$PGDATABASE" \
      -v ON_ERROR_STOP=1 -tA "$@" -f /dev/stdin 2>/dev/null \
      < "$SDK_E2E_SQL_DIR/$sql_file"
  fi
}
sdk_e2e_say() { printf '\n=== %s ===\n' "$*"; }
sdk_e2e_pass(){ printf '✅ %s\n' "$*"; }
sdk_e2e_fail(){ printf '❌ %s\n' "$*"; }
sdk_e2e_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf %s "$1" | sha256sum | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    printf %s "$1" | shasum -a 256 | cut -d' ' -f1
  else
    sdk_e2e_fail "sha256sum or shasum is required"
    return 2
  fi
}

sdk_e2e_kafka_topics_bin() {
  if [[ -n "${KAFKA_TOPICS_BIN:-}" && -x "${KAFKA_TOPICS_BIN}" ]]; then
    printf '%s' "${KAFKA_TOPICS_BIN}"
    return 0
  fi
  if [[ -n "${KAFKA_BIN_DIR:-}" && -x "${KAFKA_BIN_DIR%/}/kafka-topics.sh" ]]; then
    printf '%s' "${KAFKA_BIN_DIR%/}/kafka-topics.sh"
    return 0
  fi
  command -v kafka-topics.sh 2>/dev/null || return 1
}

sdk_e2e_kafka_topics() {
  case "${BATCH_SCRIPT_RUNTIME}" in
    host)
      local bin
      bin="$(sdk_e2e_kafka_topics_bin)" || {
        sdk_e2e_fail "kafka-topics.sh not found; set KAFKA_BIN_DIR or KAFKA_TOPICS_BIN"
        return 1
      }
      "$bin" --bootstrap-server "$KAFKA_BOOTSTRAP" "$@"
      ;;
    docker)
      docker exec "$KAFKA_CONTAINER" "$KAFKA_CONTAINER_BIN_DIR/kafka-topics.sh" --bootstrap-server "$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP" "$@"
      ;;
    auto)
      local bin
      if bin="$(sdk_e2e_kafka_topics_bin)"; then
        "$bin" --bootstrap-server "$KAFKA_BOOTSTRAP" "$@"
      else
        docker exec "$KAFKA_CONTAINER" "$KAFKA_CONTAINER_BIN_DIR/kafka-topics.sh" --bootstrap-server "$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP" "$@"
      fi
      ;;
    *) sdk_e2e_fail "BATCH_SCRIPT_RUNTIME must be one of: auto, host, docker"; return 2 ;;
  esac
}

# 检查本地/CI 栈可达。返回非 0 让入口决定是否自己 boot。
sdk_e2e_check_stack() {
  curl -fsS "${ORCH_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"' || { sdk_e2e_fail "orchestrator not UP at ${ORCH_URL}"; return 1; }
  curl -fsS "${TRIGGER_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"' || { sdk_e2e_fail "trigger not UP at ${TRIGGER_URL}"; return 1; }
  sdk_e2e_q_file check-database-ready.sql >/dev/null \
    || { sdk_e2e_fail "postgres not reachable ${PGHOST}:${PGPORT}"; return 1; }
  sdk_e2e_kafka_topics --list >/dev/null 2>&1 || { sdk_e2e_fail "kafka not reachable"; return 1; }
  sdk_e2e_pass "stack reachable"
}

# seed 一把真 API key(legacy sha256 路径)。echo 出明文 key。
# 用法:RAW=$(sdk_e2e_seed_api_key <keyName>)
sdk_e2e_seed_api_key() {
  local name="$1" raw pfx hsh
  raw="cikey$(openssl rand -hex 20)"; pfx="${raw:0:8}"; hsh="$(sdk_e2e_sha256 "$raw")"
  sdk_e2e_q_file upsert-api-key.sql \
    -v tenant_id="$TENANT" -v key_name="$name" -v key_prefix="$pfx" -v key_hash="$hsh" >/dev/null
  printf '%s' "$raw"
}

# 建立自包含的 SDK echo queue + job，不依赖系统测试 seed。
sdk_e2e_ensure_echo_job() {
  sdk_e2e_q_file upsert-echo-job.sql \
    -v tenant_id="$TENANT" -v job_code="$SDK_E2E_JOB_CODE" \
    -v queue_code="$SDK_E2E_QUEUE_CODE" >/dev/null
  [[ "$(sdk_e2e_q_file count-echo-job.sql \
    -v tenant_id="$TENANT" -v job_code="$SDK_E2E_JOB_CODE" \
    -v queue_code="$SDK_E2E_QUEUE_CODE")" == "2" ]]
}

# pre-create worker 的 node-direct 派单 topic(SDK 消费 *.node.<workerCode>)。
sdk_e2e_precreate_topic() {
  local wc="$1"
  sdk_e2e_kafka_topics --create --if-not-exists \
    --topic "batch.task.dispatch.atomic.node.${wc}" --partitions 3 --replication-factor 1 >/dev/null 2>&1
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
           BATCH_WORKER_CODE="$wc" KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" \
           go run . ) >"$logf" 2>&1 & echo $! ;;
    python)
      local python_bin="${SDK_E2E_PYTHON_BIN:-}"
      if [[ -z "$python_bin" && -x "$root/sdk/python/.venv/bin/python" ]]; then
        python_bin="$root/sdk/python/.venv/bin/python"
      elif [[ -z "$python_bin" ]]; then
        python_bin="$(command -v python)"
      fi
      if "$python_bin" -m pip --version >/dev/null 2>&1; then
        "$python_bin" -m pip install -q -e "$root/sdk/python" >>"$logf" 2>&1
        "$python_bin" -m pip install -q -e \
          "$root/examples/self-hosted-sdk/sample-tenant-worker-python" >>"$logf" 2>&1
      fi
      ( cd "$root/examples/self-hosted-sdk/sample-tenant-worker-python" \
        && PYTHONPATH="$root/sdk/python/src:$root/examples/self-hosted-sdk/sample-tenant-worker-python/src${PYTHONPATH:+:$PYTHONPATH}" \
           BATCH_SDK_BASE_URL="$ORCH_URL" BATCH_SDK_API_KEY="$raw" BATCH_SDK_TENANT_ID="$TENANT" \
           BATCH_SDK_WORKER_CODE="$wc" BATCH_SDK_KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" \
           "$python_bin" -m sample_tenant_worker ) >>"$logf" 2>&1 & echo $! ;;
    typescript)
      # SDK 的 kafka adapter(sdk/typescript/kafka)import 'kafkajs',它从 SDK 自身的
      # node_modules 解析(样例经相对路径引 SDK,样例的 node_modules 不在 SDK 解析树上),
      # 故须先在 sdk/typescript 装 devDeps(含 kafkajs)。
      npm --prefix "$root/sdk/typescript" install --silent >/dev/null 2>&1
      ( cd "$root/examples/self-hosted-sdk/sample-tenant-worker-typescript" && npm install --silent >/dev/null 2>&1 \
        && BATCH_BASE_URL="$ORCH_URL" BATCH_API_KEY="$raw" BATCH_TENANT_ID="$TENANT" \
           BATCH_WORKER_CODE="$wc" KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" \
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
           BATCH_WORKER_CODE="$wc" BATCH_KAFKA="$KAFKA_BOOTSTRAP" \
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
           BATCH_WORKER_CODE="$wc" KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" \
           ./target/debug/sample-tenant-worker-rust ) >>"$logf" 2>&1 & echo $! ;;
    *) sdk_e2e_fail "unsupported lang '$lang'"; return 2 ;;
  esac
}

# 断言 register(真 API-key auth → worker_registry 落行)。
sdk_e2e_assert_register() {
  local wc="$1" pid="$2" logf="$3" deadline
  deadline=$((SECONDS + SDK_E2E_REGISTER_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    kill -0 "$pid" 2>/dev/null || { sdk_e2e_fail "worker exited early"; tail -15 "$logf"; return 1; }
    [[ "$(sdk_e2e_q_file count-worker.sql -v tenant_id="$TENANT" -v worker_code="$wc")" == "1" ]] && return 0
    sleep 3
  done
  return 1
}

# 触发 launch + 轮询全链路,设全局 STAGE_* + 打印阶段结果。
sdk_e2e_run_chain() {
  local raw="$1" logf="$2" st inst="" deadline
  STAGE_DISPATCH=0 STAGE_EXECUTE=0 STAGE_REPORT=0 STAGE_TERMINAL=0
  SDK_E2E_IDEMPOTENCY_KEY="sdk-e2e-$$-$(date +%s 2>/dev/null || echo 0)"
  curl -fsS -X POST "${TRIGGER_URL}/api/triggers/launch" \
    -H "Authorization: Bearer ${raw}" -H "Idempotency-Key: ${SDK_E2E_IDEMPOTENCY_KEY}" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"${TENANT}\",\"jobCode\":\"${SDK_E2E_JOB_CODE}\",\"bizDate\":\"$(date +%F)\",\"triggerType\":\"API\"}" >/dev/null \
    || { sdk_e2e_fail "launch call failed"; return 1; }
  deadline=$((SECONDS + SDK_E2E_INSTANCE_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    inst="$(sdk_e2e_q_file select-latest-job-instance-id.sql \
      -v tenant_id="$TENANT" -v job_code="$SDK_E2E_JOB_CODE")"
    [[ -n "$inst" ]] && break; sleep 1
  done
  [[ -n "$inst" ]] || { sdk_e2e_fail "no job_instance created"; return 1; }
  SDK_E2E_LAUNCH_SETTLED=1
  deadline=$((SECONDS + SDK_E2E_TERMINAL_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    grep -qiE "echo handler|executing|claim" "$logf" && STAGE_DISPATCH=1 && STAGE_EXECUTE=1
    grep -qiE "report" "$logf" && ! grep -qiE "report failed|report.*5[0-9][0-9]|report.*error" "$logf" && STAGE_REPORT=1
    st="$(sdk_e2e_q_file select-job-instance-status.sql \
      -v tenant_id="$TENANT" -v instance_id="$inst")"
    case "$st" in
      SUCCESS|COMPLETED|SUCCEEDED) STAGE_DISPATCH=1; STAGE_EXECUTE=1; STAGE_REPORT=1; STAGE_TERMINAL=1; break ;;
      FAILED|CANCELLED) break ;;
    esac
    sleep 3
  done
  if [[ $STAGE_DISPATCH == 1 ]]; then sdk_e2e_pass "dispatch+claim reached worker"; else sdk_e2e_fail "task never reached worker"; fi
  if [[ $STAGE_EXECUTE == 1 ]]; then sdk_e2e_pass "handler executed"; else sdk_e2e_fail "handler did not execute"; fi
  if [[ $STAGE_REPORT == 1 ]]; then sdk_e2e_pass "report accepted"; else sdk_e2e_fail "report stage failed"; fi
  if [[ $STAGE_TERMINAL == 1 ]]; then
    sdk_e2e_pass "job terminal SUCCESS"
  else
    sdk_e2e_fail "job not terminal-success (status=${st:-?})"
  fi
  [[ $STAGE_TERMINAL == 1 ]]
}

# 等待 Trigger 的异步消息已被消费或确定放弃，避免删除定义后 Kafka 才到达。
sdk_e2e_wait_launch_settled() {
  local deadline
  [[ "$SDK_E2E_LAUNCH_SETTLED" == "1" || -z "$SDK_E2E_IDEMPOTENCY_KEY" ]] && return 0
  deadline=$((SECONDS + SDK_E2E_CLEANUP_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    if [[ "$(sdk_e2e_q_file count-sdk-e2e-unsettled-launch.sql \
      -v tenant_id="$TENANT" -v idempotency_key="$SDK_E2E_IDEMPOTENCY_KEY")" == "0" ]]; then
      SDK_E2E_LAUNCH_SETTLED=1
      return 0
    fi
    sleep 1
  done
  return 1
}

# 清理探针数据(job/worker/key/topic)。异步 launch 未收敛时保留定义，避免外键竞态。
sdk_e2e_cleanup() {
  local wc="$1"
  if ! sdk_e2e_wait_launch_settled; then
    sdk_e2e_fail "launch still in flight; preserving job=${SDK_E2E_JOB_CODE} for safe recovery"
    sdk_e2e_q_file cleanup-sdk-e2e-worker.sql \
      -v tenant_id="$TENANT" -v worker_code="$wc" >/dev/null
    sdk_e2e_kafka_topics --delete --topic "batch.task.dispatch.atomic.node.${wc}" 2>/dev/null
    return 0
  fi
  sdk_e2e_q_file cleanup-sdk-e2e.sql \
    -v tenant_id="$TENANT" -v job_code="$SDK_E2E_JOB_CODE" \
    -v queue_code="$SDK_E2E_QUEUE_CODE" -v worker_code="$wc" >/dev/null
  sdk_e2e_kafka_topics --delete --topic "batch.task.dispatch.atomic.node.${wc}" 2>/dev/null
}
