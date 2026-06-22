#!/usr/bin/env bash
# =============================================================================
# run-sdk-orchestrator-e2e.sh —— BYO SDK 样例 worker × 真 orchestrator 端到端
#
# 定位:补 SDK 测试的最大缺口。per-PR 的 sdk-contract-parity / live-transport 只打
# **fake HTTP stub** + 真 Kafka;真实 orchestrator 的鉴权 / worker 注册 / 心跳
# directive 往返从未被任何测试覆盖——SDK 能全绿却在首次真部署崩。本脚本让一个
# 真实样例 worker(examples/self-hosted-sdk/sample-tenant-worker-<lang>)用**真
# API-key** 连**真 orchestrator**,验证整条控制面链路。
#
# 阶段:
#   A(硬断言,路由无关):样例 worker 用真 API-key 注册 + 心跳到真 orchestrator
#      → batch.worker_registry 落行。这条覆盖审计点名的"真鉴权/注册/心跳从未测"。
#   B(best-effort,首跑摸排):pre-create 派单 topic → 触发 launch → 轮询 job_instance。
#      SDK 消费是 tenant 通配(batch.task.dispatch.<tenant>.*)+ 与内建 worker 竞争
#      claim,首跑可能需调;此阶段只**记录 + 诊断**,不硬挂(v1 非阻塞 lane)。
#
# 前置:基础设施 + orchestrator + trigger 已起(scripts/docker/up-apps.sh),
#   health UP;psql / docker / openssl 可用。
#
# 用法:bash scripts/ci/run-sdk-orchestrator-e2e.sh <go|python>
# 环境(均有默认,CI 可覆盖):
#   BATCH_PLATFORM_DB_PASSWORD(必填)、POSTGRES_PORT(15432)、KAFKA_HOST_PORT(19092)、
#   ORCH_PORT(18082)、TRIGGER_PORT(18081)、KAFKA_CONTAINER(kafka)、TENANT(default-tenant)
# =============================================================================
set -euo pipefail

LANG_ID="${1:?usage: run-sdk-orchestrator-e2e.sh <go|python>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# ── 连接参数(宿主映射端口,与 .env.local 对齐)────────────────────────────
PGHOST="${BATCH_PLATFORM_DB_HOST:-localhost}"
PGPORT="${POSTGRES_PORT:-15432}"
PGUSER="${BATCH_PLATFORM_DB_USERNAME:-batch_user}"
PGDATABASE="${BATCH_PLATFORM_DB_NAME:-batch_platform}"
PGPASSWORD="${BATCH_PLATFORM_DB_PASSWORD:?BATCH_PLATFORM_DB_PASSWORD required}"
export PGPASSWORD
ORCH_PORT="${ORCH_PORT:-18082}"
TRIGGER_PORT="${TRIGGER_PORT:-18081}"
KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-19092}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
TENANT="${TENANT:-default-tenant}"
ORCH_URL="http://localhost:${ORCH_PORT}"
KAFKA_BOOTSTRAP="localhost:${KAFKA_HOST_PORT}"
WORKER_CODE="ci-e2e-${LANG_ID}-$$"
WORKER_LOG="/tmp/sdk-e2e-worker-${LANG_ID}.log"

psqlp() { psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -tA "$@"; }

WPID=""
dump_diagnostics() {
  echo "::group::diagnostics (worker log: ${WORKER_LOG})"
  echo "----- sample worker log (tail) -----"; tail -n 80 "$WORKER_LOG" 2>/dev/null || echo "(no worker log)"
  echo "----- worker_registry rows for tenant=${TENANT} -----"
  psqlp -c "SELECT worker_code, worker_group, capability_tags, COALESCE(worker_status::text,'?') FROM batch.worker_registry WHERE tenant_id='${TENANT}' ORDER BY id DESC LIMIT 10;" 2>/dev/null || true
  echo "----- recent job_instance for tenant=${TENANT} -----"
  psqlp -c "SELECT instance_no, job_code, instance_status, worker_group FROM batch.job_instance WHERE tenant_id='${TENANT}' ORDER BY id DESC LIMIT 5;" 2>/dev/null || true
  echo "----- orchestrator container log (tail) -----"; docker logs batch-orchestrator --tail 60 2>/dev/null || true
  echo "----- trigger container log (tail) -----"; docker logs batch-trigger --tail 40 2>/dev/null || true
  echo "::endgroup::"
}
cleanup() {
  if [[ -n "$WPID" ]] && kill -0 "$WPID" 2>/dev/null; then
    kill "$WPID" 2>/dev/null || true
    wait "$WPID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ── 0. orchestrator health ───────────────────────────────────────────────
echo "==> waiting for orchestrator health at ${ORCH_URL}/actuator/health"
for _ in $(seq 1 60); do
  if curl -fsS "${ORCH_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then break; fi
  sleep 3
done
curl -fsS "${ORCH_URL}/actuator/health" | grep -q '"status":"UP"' \
  || { echo "FAIL: orchestrator not UP"; dump_diagnostics; exit 1; }

# ── 1. seed 真 API key(legacy sha256 路径:key_hash_algo='sha256')──────────
RAW_KEY="cikey$(openssl rand -hex 20)"
KEY_PREFIX="${RAW_KEY:0:8}"
KEY_HASH="$(printf %s "$RAW_KEY" | sha256sum | cut -d' ' -f1)"
echo "==> seeding API key prefix=${KEY_PREFIX} (sha256) for tenant=${TENANT}"
psqlp -c "INSERT INTO batch.api_key (tenant_id, key_name, key_prefix, key_hash, key_hash_algo, scopes, enabled, created_at)
          VALUES ('${TENANT}', 'ci-e2e-${LANG_ID}-$$', '${KEY_PREFIX}', '${KEY_HASH}', 'sha256', '*', true, now())
          ON CONFLICT DO NOTHING;"

# ── 2. pre-create 派单 topic(Go 消费器启动即要求 tenant 通配下至少一个 topic)──
echo "==> pre-creating dispatch topic batch.task.dispatch.${TENANT}.echo"
docker exec "${KAFKA_CONTAINER}" /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:29092 --create --if-not-exists \
  --topic "batch.task.dispatch.${TENANT}.echo" --partitions 3 --replication-factor 1 \
  || echo "WARN: topic create returned non-zero (may already exist)"

# ── 3. 起样例 worker(后台)───────────────────────────────────────────────
echo "==> starting ${LANG_ID} sample worker code=${WORKER_CODE}"
case "$LANG_ID" in
  go)
    ( cd examples/self-hosted-sdk/sample-tenant-worker-go \
      && BATCH_BASE_URL="$ORCH_URL" BATCH_API_KEY="$RAW_KEY" BATCH_TENANT_ID="$TENANT" \
         BATCH_WORKER_CODE="$WORKER_CODE" KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" \
         go run . ) > "$WORKER_LOG" 2>&1 &
    WPID=$!
    ;;
  python)
    python -m pip install -q -e "$ROOT/sdk/python" >/dev/null
    ( cd examples/self-hosted-sdk/sample-tenant-worker-python \
      && python -m pip install -q -e . >/dev/null \
      && BATCH_SDK_BASE_URL="$ORCH_URL" BATCH_SDK_API_KEY="$RAW_KEY" BATCH_SDK_TENANT_ID="$TENANT" \
         BATCH_SDK_WORKER_CODE="$WORKER_CODE" BATCH_SDK_KAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" \
         python -m sample_tenant_worker ) > "$WORKER_LOG" 2>&1 &
    WPID=$!
    ;;
  java)
    echo "==> building batch-worker-sdk(+testkit) to local m2"
    # SDK 模块在根 reactor 按路径注册(<module>sdk/java</module>),-pl 用路径不是 artifactId
    ( cd "$ROOT" && mvn -q -pl sdk/java,sdk/java-testkit -am install -DskipTests ) \
      || { echo "FAIL: mvn install batch-worker-sdk failed"; dump_diagnostics; exit 1; }
    echo "==> packaging java sample worker"
    ( cd examples/self-hosted-sdk/sample-tenant-worker-java && mvn -q package -DskipTests ) \
      || { echo "FAIL: mvn package java sample worker failed"; dump_diagnostics; exit 1; }
    JAR="$(ls examples/self-hosted-sdk/sample-tenant-worker-java/target/sample-tenant-worker-*.jar | head -1)"
    # 注:jar manifest 的 Class-Path=lib/ 相对 jar 自身位置解析(maven-dependency-plugin 已拷到 target/lib)
    ( BATCH_BASE_URL="$ORCH_URL" BATCH_API_KEY="$RAW_KEY" BATCH_TENANT_ID="$TENANT" \
      BATCH_WORKER_CODE="$WORKER_CODE" BATCH_KAFKA="$KAFKA_BOOTSTRAP" \
      java -jar "$JAR" ) > "$WORKER_LOG" 2>&1 &
    WPID=$!
    ;;
  *)
    echo "FAIL: unsupported lang '${LANG_ID}' (go|python|java)"; exit 2 ;;
esac

# ── 4. 阶段 A 硬断言:worker 注册 + 心跳落 worker_registry ─────────────────
echo "==> [stage A] asserting worker registers against real orchestrator (real API-key auth)"
registered=0
for _ in $(seq 1 40); do
  if ! kill -0 "$WPID" 2>/dev/null; then
    echo "FAIL: sample worker exited early"; dump_diagnostics; exit 1
  fi
  cnt="$(psqlp -c "SELECT count(*) FROM batch.worker_registry WHERE tenant_id='${TENANT}' AND worker_code='${WORKER_CODE}';" || echo 0)"
  if [[ "$cnt" == "1" ]]; then registered=1; break; fi
  sleep 3
done
if [[ "$registered" != "1" ]]; then
  echo "FAIL [stage A]: worker did not register within timeout (real auth/register path)"
  dump_diagnostics; exit 1
fi
echo "PASS [stage A]: ${LANG_ID} sample worker registered + heartbeating against REAL orchestrator via REAL API-key auth"

# ── 5. 阶段 B best-effort:触发 launch + 轮询(首跑摸排派单链路,只记录不硬挂)──
echo "==> [stage B best-effort] triggering a launch + observing dispatch (non-fatal in v1)"
IDEMP="ci-e2e-${LANG_ID}-$$-$(date +%s 2>/dev/null || echo 0)"
launch_resp="$(curl -fsS -X POST "http://localhost:${TRIGGER_PORT}/api/triggers/launch" \
  -H "Authorization: Bearer ${RAW_KEY}" -H "Idempotency-Key: ${IDEMP}" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"${TENANT}\",\"jobCode\":\"atomic_shell_demo\",\"bizDate\":\"2026-06-22\",\"triggerType\":\"API\"}" \
  2>&1 || true)"
echo "    launch response: ${launch_resp}"
echo "    polling job_instance terminal state (best-effort, ~60s)..."
for _ in $(seq 1 20); do
  st="$(psqlp -c "SELECT instance_status FROM batch.job_instance WHERE tenant_id='${TENANT}' AND job_code='atomic_shell_demo' ORDER BY id DESC LIMIT 1;" 2>/dev/null || echo '')"
  echo "    job_instance status=${st:-<none>}"
  case "$st" in
    SUCCESS|COMPLETED|SUCCEEDED) echo "PASS [stage B]: launched job reached terminal success (${st})"; break ;;
    FAILED|CANCELLED) echo "WARN [stage B]: job terminal non-success (${st}) — see diagnostics, expected to refine after first run"; break ;;
  esac
  sleep 3
done

echo "==> SDK orchestrator e2e (${LANG_ID}) complete: stage A PASS (core real-auth gap closed); stage B best-effort logged."
