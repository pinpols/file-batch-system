#!/usr/bin/env bash
# 06-sdk-worker-verify.sh — 自托管 SDK worker 端到端真实调用验证(可重复执行)
#
# 对【本地运行栈】验证 examples/sample-tenant-worker(batch-worker-sdk)的真实接入:
#   register(SDK↔orchestrator 鉴权 + /internal/workers/register)
#   → 5 类基类 handler 的 taskType()/descriptor() 上报
#   → worker_registry 出现 ONLINE 行。
# 覆盖 5 个 SDK 基类(Import/Export/Process/Dispatch/Atomic)+ echo/sleep 的注册腿。
#
# 幂等可重复:每次跑会清掉上轮的 sdk-verify API key 与 worker 注册,重新建。
# 退出 0=全部通过,非 0=有断言失败。结束时自动停 worker(deactivate + kill)。
#
# 依赖:本地 docker 栈在跑(console 18080 / orchestrator 18082 / kafka 19092 / pg);
#       admin/admin123 种子账号;mvn + java(构建 sample jar,缺则自动构建)。
#
# 用法:
#   bash scripts/sim/06-sdk-worker-verify.sh
#   TENANT=tb ADMIN_PASS=xxx bash scripts/sim/06-sdk-worker-verify.sh
#   PHASE2_DISPATCH=1 ...   # 额外尝试 dispatch-execute 腿(需 pipeline fixtures,best-effort)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/lib/env-common.sh
source "$REPO_ROOT/scripts/lib/env-common.sh"

# ---- 配置(均可 env 覆盖)----
CONSOLE="${CONSOLE_BASE_URL}"
ORCH="${ORCHESTRATOR_BASE_URL}"
TRIGGER="${TRIGGER_BASE_URL}"
KAFKA="${KAFKA_BOOTSTRAP:-$KAFKA_HOST_BOOTSTRAP}"
# Phase 2(dispatch-execute 腿)默认走 default-tenant —— 它 seed 了 atomic 基础设施(atomic_queue /
# default-calendar / always_open),fixture 只需加一行 job_definition。Phase 1 注册断言与租户无关。
TENANT="${TENANT:-${BATCH_DEFAULT_TENANT_ID:-default-tenant}}"
INTERNAL_SECRET="${INTERNAL_SECRET:-internal-secret}"
# Phase 2 worker 须消费平台 ATOMIC base 派单 topic(SINGLE 路由 = batch.task.dispatch.atomic);默认
# topic pattern 是 batch.task.dispatch.<tenant>.* 不匹配 base topic,这里显式覆盖成 atomic base。
DISPATCH_TOPIC_PATTERN="${DISPATCH_TOPIC_PATTERN:-batch\\.task\\.dispatch\\.atomic.*}"
DISPATCH_JOB_CODE="${DISPATCH_JOB_CODE:-SDK_VERIFY_DISPATCH_ECHO}"
DISPATCH_FIXTURE="$REPO_ROOT/docs/test-data/sim06-sdk-dispatch-echo-fixtures.sql"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-batch-kafka}"
KAFKA_INTERNAL_BOOTSTRAP="${KAFKA_INTERNAL_BOOTSTRAP:-kafka:29092}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin123}"
WORKER_CODE="${WORKER_CODE:-sdk-verify-worker}"
# 每轮唯一 key 名(避免软删后名仍占用导致 CONFLICT);旧 sdk-verify* key 开头尽力清理
KEY_NAME="${KEY_NAME:-sdk-verify-$(date +%s)}"
KEY_PREFIX_PURGE="sdk-verify"
JAR="$REPO_ROOT/examples/self-hosted-sdk/sample-tenant-worker-java/target/sample-tenant-worker-1.0.0-SNAPSHOT.jar"
PG_CONTAINER="${PG_CONTAINER:-batch-postgres-primary}"
PSQL=(docker exec "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc)
REG_TIMEOUT="${REG_TIMEOUT:-45}"
# sample-tenant-worker 注册的 7 个 taskType(对应 5 基类 + echo/sleep)
EXPECTED_TASKTYPES="echo sleep sample_import_echo sample_export_echo sample_process_echo sample_dispatch_echo sample_atomic_echo"

CK="$(mktemp -t sdk-verify-ck.XXXXXX)"
WORKER_LOG="$(mktemp -t sdk-verify-worker.XXXXXX)"
WORKER_PID=""
FAILS=0
note()  { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
ok()    { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()   { printf '  \033[31m✗\033[0m %s\n' "$*"; FAILS=$((FAILS+1)); }
info()  { printf '  · %s\n' "$*"; }

cleanup() {
  if [[ -n "$WORKER_PID" ]] && kill -0 "$WORKER_PID" 2>/dev/null; then
    note "清理:停 worker(deactivate + kill $WORKER_PID)"
    curl -s -m 5 -X POST "$ORCH/internal/workers/$WORKER_CODE/deactivate" \
      -H "X-Batch-Api-Key: ${RAW_KEY:-}" -H "X-Batch-Tenant-Id: $TENANT" -o /dev/null 2>/dev/null || true
    kill "$WORKER_PID" 2>/dev/null || true
    wait "$WORKER_PID" 2>/dev/null || true
  fi
  rm -f "$CK"
}
trap cleanup EXIT

# ---- 0. 预检 ----
note "0. 预检:运行栈"
curl -sf -m 5 "$CONSOLE/actuator/health" -o /dev/null && ok "console-api $CONSOLE UP" || { bad "console-api DOWN"; exit 1; }
curl -sf -m 5 "$ORCH/actuator/health" -o /dev/null && ok "orchestrator $ORCH UP" || { bad "orchestrator DOWN"; exit 1; }
"${PSQL[@]}" "select 1" >/dev/null 2>&1 && ok "postgres($PG_CONTAINER) 可达" || { bad "postgres 不可达"; exit 1; }
(exec 3<>"/dev/tcp/${KAFKA%%:*}/${KAFKA##*:}") 2>/dev/null && ok "kafka $KAFKA 端口开" || info "kafka $KAFKA 端口探测失败(worker 起后看日志)"

# ---- 1. admin 登录 ----
note "1. 登录 admin"
code=$(curl -s -m 8 -c "$CK" -o /dev/null -w '%{http_code}' -X POST "$CONSOLE/api/console/auth/login" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
[[ "$code" == "200" ]] && ok "登录成功" || { bad "登录失败 HTTP $code"; exit 1; }

# ---- 2. 幂等 API key:清旧同名 + 建新(scopes='*' 通配过 worker.execute)----
note "2. API key(幂等:清旧同名后重建)"
idem() { uuidgen 2>/dev/null || echo "sdk-verify-$$-$RANDOM-$(date +%s)"; }
existing=$(curl -s -m 8 -b "$CK" "$CONSOLE/api/console/api-keys?tenantId=$TENANT" -H "X-Tenant-Id: $TENANT")
# 尽力清理历史 sdk-verify* key(幂等,不阻断):逐对象抽 id+keyName,名以 prefix 开头则删
echo "$existing" | tr '}' '\n' | grep -oE '"id":[0-9]+,"keyPrefix":"[^"]*","keyName":"[^"]*"|"keyName":"[^"]*"[^}]*"id":[0-9]+|"id":[0-9]+[^}]*"keyName":"'"$KEY_PREFIX_PURGE"'[^"]*"' >/dev/null 2>&1
for id in $(echo "$existing" | tr '}' '\n' | grep "\"keyName\":\"$KEY_PREFIX_PURGE" | grep -oE '"id":[0-9]+' | grep -oE '[0-9]+'); do
  curl -s -m 8 -b "$CK" -X DELETE "$CONSOLE/api/console/api-keys/$id?tenantId=$TENANT" \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: $(idem)" -o /dev/null && info "清理旧 key id=$id"
done
created=$(curl -s -m 8 -b "$CK" -X POST "$CONSOLE/api/console/api-keys?tenantId=$TENANT" \
  -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -H "Idempotency-Key: $(idem)" \
  -d "{\"keyName\":\"$KEY_NAME\",\"scopes\":\"*\"}")
RAW_KEY=$(echo "$created" | grep -oE '"rawKey":"[^"]+"' | head -1 | sed 's/"rawKey":"//;s/"//')
[[ -z "$RAW_KEY" ]] && RAW_KEY=$(echo "$created" | grep -oE '"apiKey":"[^"]+"' | head -1 | sed 's/.*"//;s/"//')
[[ -n "$RAW_KEY" ]] && ok "API key 已建(scopes=*)" || { bad "未取到 rawKey: $(echo "$created" | head -c 200)"; exit 1; }

# ---- 3. 构建 sample worker(缺 jar 才建)----
note "3. sample-tenant-worker jar"
if [[ -f "$JAR" ]]; then ok "jar 已存在($(basename "$JAR"))"; else
  info "jar 缺失,构建中(mvn install)..."
  (cd "$REPO_ROOT" && mvn -q -pl sdk/java/core -am install -DskipTests && \
     mvn -q install -f examples/self-hosted-sdk/sample-tenant-worker-java/pom.xml -DskipTests) \
    && ok "构建完成" || { bad "构建失败"; exit 1; }
fi

# ---- 4. 起 worker(后台,指向本地栈)----
note "4. 启动 sample worker → 本地栈"
# 预建 direct-dispatch topic:平台 selector 选中本 worker 后,派单走 direct topic
# batch.task.dispatch.atomic.node.<worker>。SDK 消费用 pattern 订阅 + auto.offset.reset=latest,
# 若该 topic 在 worker 订阅之后才由首次派单创建,首条消息会落在订阅前 → 被漏读。预建保证 worker
# 启动即把它纳入 pattern 订阅、从 offset 0 跟踪,后续派单必达。(幂等 --if-not-exists)
DISPATCH_NODE_TOPIC="batch.task.dispatch.atomic.node.${WORKER_CODE//[^a-zA-Z0-9._-]/_}"
if docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$KAFKA_INTERNAL_BOOTSTRAP" --create --if-not-exists \
    --topic "$DISPATCH_NODE_TOPIC" --partitions 1 --replication-factor 1 >/dev/null 2>&1; then
  info "预建 direct-dispatch topic: $DISPATCH_NODE_TOPIC"
else
  info "direct-dispatch topic 预建跳过(已存在或 kafka-topics 不可用)"
fi
BATCH_BASE_URL="$ORCH" BATCH_KAFKA="$KAFKA" BATCH_TENANT_ID="$TENANT" \
  BATCH_WORKER_CODE="$WORKER_CODE" BATCH_API_KEY="$RAW_KEY" \
  BATCH_TOPIC_PATTERN="$DISPATCH_TOPIC_PATTERN" \
  java -jar "$JAR" >"$WORKER_LOG" 2>&1 &
WORKER_PID=$!
info "worker pid=$WORKER_PID log=$WORKER_LOG"

# ---- 5. 等注册 → worker_registry ONLINE ----
note "5. 等注册(≤${REG_TIMEOUT}s)"
registered=0
for ((i=0;i<REG_TIMEOUT;i++)); do
  if ! kill -0 "$WORKER_PID" 2>/dev/null; then bad "worker 进程提前退出,日志尾部:"; tail -15 "$WORKER_LOG"; exit 1; fi
  st=$("${PSQL[@]}" "select status from batch.worker_registry where tenant_id='$TENANT' and worker_code='$WORKER_CODE' limit 1;" 2>/dev/null)
  if [[ "$st" == "ONLINE" ]]; then registered=1; ok "worker_registry: $WORKER_CODE = ONLINE"; break; fi
  sleep 1
done
[[ "$registered" == "1" ]] || { bad "注册超时(worker_registry 无 ONLINE)。日志尾部:"; tail -20 "$WORKER_LOG"; }

# ---- 6. 断言:5 类 taskType 上报 ----
note "6. taskType 上报(5 基类 + echo/sleep)"
# 6a. descriptor 上报(import 重写了 descriptor() → custom_task_type_registry)
desc=$("${PSQL[@]}" "select code from batch.custom_task_type_registry where tenant_id='$TENANT' and code like 'sample_%';" 2>/dev/null)
# 6b. worker 注册的 capability_tags(全部 taskType)
caps=$("${PSQL[@]}" "select capability_tags from batch.worker_registry where tenant_id='$TENANT' and worker_code='$WORKER_CODE' limit 1;" 2>/dev/null)
info "custom_task_type_registry(descriptor): $(echo "$desc" | tr '\n' ' ')"
info "worker capability_tags: $caps"
for tt in $EXPECTED_TASKTYPES; do
  if echo "$caps $desc" | grep -qw "$tt"; then ok "taskType 上报: $tt"; else bad "taskType 缺失: $tt"; fi
done

# ---- 7. Phase 2:dispatch-execute 腿(真链路:launch ATOMIC → Kafka → 自托管 worker 执行 → REPORT → SUCCESS)----
# 把任务真派给自托管 SDK worker 跑通,作为 #544 workerType→handler 路由键反序列化 P0 的运行期对照
# (CI 单测对照 = SdkWireContractTest)。ATOMIC 是唯一单任务无文件管线的 base workerType;worker_group
# 门禁保证只有我方 worker(sdk-self-hosted)能 CLAIM,平台自带 atomic-node-1(组 ATOMIC)抢不到。
if [[ "${PHASE2_DISPATCH:-1}" == "1" ]]; then
  note "7. Phase 2: dispatch-execute(真链路 launch → Kafka → 自托管 worker 执行)"
  if [[ "$registered" != "1" ]]; then
    bad "worker 未注册,跳过 dispatch 腿"
  else
    # 7a. seed job_definition fixture(ATOMIC + worker_group=sdk-self-hosted,幂等)
    if docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
        -v ON_ERROR_STOP=1 -q < "$DISPATCH_FIXTURE" >/dev/null 2>&1; then
      ok "fixture seeded($DISPATCH_JOB_CODE, tenant=$TENANT)"
    else
      bad "fixture seed 失败($DISPATCH_FIXTURE)"
    fi
    # 7b. 触发 launch
    RID="sdk-verify-dispatch-$(date +%s)"
    launch_code=$(curl -s -m 15 -o /tmp/sdk-dispatch-launch.json -w '%{http_code}' \
      -X POST "$TRIGGER/api/triggers/launch" \
      -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" \
      -H "X-Internal-Secret: $INTERNAL_SECRET" -H "Idempotency-Key: $RID" -H "X-Request-Id: $RID" \
      -d "{\"tenantId\":\"$TENANT\",\"jobCode\":\"$DISPATCH_JOB_CODE\",\"triggerType\":\"API\",\"bizDate\":\"$(date +%Y-%m-%d)\",\"requestId\":\"$RID\"}")
    if [[ "$launch_code" == "200" ]]; then
      ok "launch 受理(rid=$RID)"
    else
      bad "launch HTTP $launch_code: $(head -c 200 /tmp/sdk-dispatch-launch.json)"
    fi
    # 7c. 轮询 job_instance 终态(≤60s)
    final=""
    for ((i=0;i<60;i++)); do
      final=$("${PSQL[@]}" "select i.instance_status from batch.trigger_request tr join batch.job_instance i on i.id=tr.related_job_instance_id where tr.tenant_id='$TENANT' and tr.request_id='$RID' order by tr.created_at desc limit 1;" 2>/dev/null)
      [[ "$final" =~ ^(SUCCESS|FAILED|PARTIAL_FAILED|CANCELLED|TERMINATED)$ ]] && break
      sleep 1
    done
    if [[ "$final" == "SUCCESS" ]]; then
      ok "job_instance 终态 SUCCESS(dispatch→execute→report 真链路闭环)"
    else
      bad "job_instance 终态=${final:-<timeout>}(期望 SUCCESS)"
    fi
    # 7d. 断言 worker 真的执行了 ATOMIC handler(运行期对照 #544:workerType 正确绑定到 handler 路由键)
    if grep -q "ATOMIC base handler taskId=" "$WORKER_LOG"; then
      ok "worker 日志确认 ATOMIC handler 执行(平台 workerType=ATOMIC 已正确路由到 handler)"
    else
      bad "worker 日志无 'ATOMIC base handler taskId='(派单未到达 / 未路由到 handler —— #544 类回归信号)"
    fi
    # 7e. 断言 job_task 落 SUCCESS 且由我方 worker claim(组门禁定向投递生效)
    task_row=$("${PSQL[@]}" "select t.task_status||'|'||coalesce(t.assigned_worker_code,'') from batch.job_instance i join batch.job_task t on t.job_instance_id=i.id join batch.trigger_request tr on tr.related_job_instance_id=i.id where tr.tenant_id='$TENANT' and tr.request_id='$RID' limit 1;" 2>/dev/null)
    if [[ "$task_row" == "SUCCESS|$WORKER_CODE" ]]; then
      ok "job_task SUCCESS 且 assigned_worker=$WORKER_CODE(worker_group 门禁定向投递)"
    elif [[ "$task_row" == SUCCESS\|* ]]; then
      ok "job_task SUCCESS(assigned=${task_row#SUCCESS|})"
    else
      bad "job_task 非 SUCCESS: ${task_row:-<none>}"
    fi
  fi
fi

note "结果"
if [[ "$FAILS" -eq 0 ]]; then printf '\033[32m全部通过\033[0m — SDK 自托管 worker 注册 + 5 类 taskType 上报已验证(真实本地栈)\n'; else
  printf '\033[31m%d 项失败\033[0m\n' "$FAILS"; fi
exit "$FAILS"
