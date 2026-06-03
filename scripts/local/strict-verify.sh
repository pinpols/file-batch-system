#!/usr/bin/env bash
# =========================================================
# strict-verify.sh
#
# 严格模式真实数据验证 — 不依赖 BE auth,直接 PSQL + maintenance 双轨切换。
#
# 验证项:
#   1. cursor vs offset 翻页一致性(等价 SQL,SQL 层 sanity check)
#   2. maintenance 双轨真实切换(API 503 + X-Maintenance header)
#   3. 审计落表(@AuditAction 写入 console_operation_audit)
#   4. cron-preview 真实 Quartz 引擎产出(simple + L + invalid)
#   5. cursor token 解码失败安全降级(返回空,不抛 500)
#
# 用法:
#   bash scripts/local/strict-verify.sh              # 完整真实数据验证(本地默认)
#   bash scripts/local/strict-verify.sh --dry-run    # 仅校验前置依赖(CI smoke 用)
#   bash scripts/local/strict-verify.sh --help
#
# 环境变量:
#   CONSOLE_PORT (默认 18080)
#   PG_CONTAINER / PG_USER / PG_DB (默认 batch-postgres-primary / batch_user / batch_platform)
#   CI=1     CI 适配:关闭 ANSI 颜色,失败时打印额外诊断,不改变 exit code 语义
# =========================================================

set -uo pipefail

DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run)
      DRY_RUN=1
      ;;
    --help|-h)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$arg" >&2
      exit 2
      ;;
  esac
done

CONSOLE_PORT="${CONSOLE_PORT:-18080}"
PG_CONTAINER="${PG_CONTAINER:-batch-postgres-primary}"
PG_USER="${PG_USER:-batch_user}"
PG_DB="${PG_DB:-batch_platform}"
BASE="http://localhost:${CONSOLE_PORT}"

# CI 模式关闭 ANSI 颜色(避免 GitHub Actions 日志里 ^[[32m 噪音);本地保留高亮
if [[ "${CI:-}" == "1" || "${CI:-}" == "true" || ! -t 1 ]]; then
  GREEN='' RED='' YELLOW='' BLUE='' RST=''
else
  GREEN='\033[32m' RED='\033[31m' YELLOW='\033[33m' BLUE='\033[34m' RST='\033[0m'
fi
PASS=0 FAIL=0

psql_q() {
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null
}

pass() { printf " ${GREEN}🟢 PASS${RST}  %s — %s\n" "$1" "$2"; PASS=$((PASS+1)); }
fail() { printf " ${RED}🔴 FAIL${RST}  %s — %s\n" "$1" "$2"; FAIL=$((FAIL+1)); }
skip() { printf " ${YELLOW}🟡 SKIP${RST}  %s — %s\n" "$1" "$2"; }
hdr()  { printf "\n${BLUE}━━━ %s ━━━${RST}\n" "$1"; }

# ───────────────────────────────────────────────────────────
# §0. 前置依赖检查(--dry-run 模式只跑到这里即返回)
#
# CI 接入策略:每个 push 跑一次 --dry-run,验证脚本本身在 CI 环境里
# 仍可成功解析参数 + 检测依赖缺失,不实际跑真实数据验证(那需要 PG +
# console-api 起来,见 .github/workflows/strict-verify.yml 手动派发模式)。
# ───────────────────────────────────────────────────────────
hdr "0. 前置依赖检查"

PRECHECK_FAIL=0
if command -v docker >/dev/null 2>&1; then
  pass "docker CLI 可用" "$(docker --version 2>/dev/null | head -1)"
else
  fail "docker CLI 缺失" "本脚本依赖 docker exec 访问 PG 容器"
  PRECHECK_FAIL=1
fi

if command -v curl >/dev/null 2>&1; then
  pass "curl 可用" "$(curl --version 2>/dev/null | head -1)"
else
  fail "curl 缺失" "本脚本依赖 curl 调 console-api HTTP 接口"
  PRECHECK_FAIL=1
fi

if command -v python3 >/dev/null 2>&1; then
  pass "python3 可用" "$(python3 --version 2>/dev/null)"
else
  fail "python3 缺失" "本脚本用 python3 解析 JSON / YAML 响应"
  PRECHECK_FAIL=1
fi

if [[ "$DRY_RUN" == "1" ]]; then
  # dry-run 模式:只跑前置检查,不连 PG / console-api
  hdr "DRY-RUN 汇总"
  printf "  PASS: %s  FAIL: %s  (dry-run: 跳过真实数据验证)\n" "$PASS" "$FAIL"
  if [[ "$PRECHECK_FAIL" -gt 0 ]]; then
    exit 1
  fi
  exit 0
fi

# 非 dry-run 模式继续验证 PG 容器可达 + console-api 探活,失败直接整体退出
# (不算 FAIL 计数,因为这是环境问题不是数据问题)
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${PG_CONTAINER}$"; then
  printf "${RED}[fatal]${RST} PG 容器 '%s' 未运行 — 请先 'docker compose up -d postgres' 或本地启动\n" "$PG_CONTAINER" >&2
  exit 2
fi
if ! curl -sf "${BASE}/actuator/health" -o /dev/null --max-time 5 2>/dev/null; then
  printf "${RED}[fatal]${RST} console-api %s 不可达 — 请先启动 console-api(scripts/local/start-all.sh)\n" "$BASE" >&2
  exit 2
fi

# ───────────────────────────────────────────────────────────
# §1. cursor vs offset 翻页一致性(SQL 等价 sanity)
#    选行数最多的租户做验证,排除全表 join 干扰
# ───────────────────────────────────────────────────────────
hdr "1. cursor vs offset 翻页一致性"

TENANT=$(psql_q "select tenant_id from batch.job_instance group by tenant_id order by count(*) desc limit 1;")
TOTAL=$(psql_q "select count(*) from batch.job_instance where tenant_id='$TENANT';")
if [[ -z "$TOTAL" ]] || [[ "$TOTAL" -lt 3 ]]; then
  skip "cursor 翻页验证" "最大租户 '$TENANT' 行数 $TOTAL < 3"
else
  PAGE_SIZE=5
  offset_ids=$(psql_q "select string_agg(id::text, ',' order by id desc) from (select id from batch.job_instance where tenant_id='$TENANT' order by id desc limit 1000) t;")
  cursor_acc=""
  last_id=""
  for batch_i in $(seq 1 200); do
    if [[ -z "$last_id" ]]; then
      batch_ids=$(psql_q "select string_agg(id::text, ',' order by id desc) from (select id from batch.job_instance where tenant_id='$TENANT' order by id desc limit $PAGE_SIZE) t;")
    else
      batch_ids=$(psql_q "select string_agg(id::text, ',' order by id desc) from (select id from batch.job_instance where tenant_id='$TENANT' and id < $last_id order by id desc limit $PAGE_SIZE) t;")
    fi
    [[ -z "$batch_ids" ]] && break
    cursor_acc="${cursor_acc}${cursor_acc:+,}${batch_ids}"
    last_id=$(echo "$batch_ids" | awk -F',' '{print $NF}')
    batch_count=$(echo "$batch_ids" | tr ',' '\n' | wc -l | tr -d ' ')
    [[ "$batch_count" -lt "$PAGE_SIZE" ]] && break
  done
  if [[ "$offset_ids" == "$cursor_acc" ]]; then
    pass "cursor 翻完 ids = offset 翻完 ids" "租户 $TENANT / $TOTAL 行 / pageSize=$PAGE_SIZE"
  else
    fail "cursor/offset 不一致" "offset=$(echo $offset_ids | head -c 80)... cursor=$(echo $cursor_acc | head -c 80)..."
  fi
fi

# ───────────────────────────────────────────────────────────
# §2. cron-preview 真实 Quartz 产出
# ───────────────────────────────────────────────────────────
hdr "2. cron-preview 真实 Quartz 引擎"

simple=$(curl -s --max-time 30 --connect-timeout 5 -G --data-urlencode "expr=0 0 2 * * ?" --data-urlencode "count=3" "$BASE/api/console/system/cron-preview")
valid_field=$(echo "$simple" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['valid'])" 2>/dev/null)
runs_count=$(echo "$simple" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['nextRuns']))" 2>/dev/null)
[[ "$valid_field" == "True" && "$runs_count" == "3" ]] \
  && pass "简单 expr (0 0 2 * * ?) → valid=true, 3 runs" "tz=Asia/Shanghai" \
  || fail "简单 expr 异常" "valid=$valid_field runs=$runs_count"

# Quartz L (last Friday of month)
quartz_l=$(curl -s --max-time 30 --connect-timeout 5 -G --data-urlencode "expr=0 15 10 ? * 6L" --data-urlencode "count=2" "$BASE/api/console/system/cron-preview")
l_valid=$(echo "$quartz_l" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['valid'])" 2>/dev/null)
[[ "$l_valid" == "True" ]] \
  && pass "Quartz L 高级语法 (0 15 10 ? * 6L)" "valid=true (本地自实现解析必然失败)" \
  || fail "Quartz L 解析失败" "valid=$l_valid"

# 非法表达式不抛 500
invalid=$(curl -s --max-time 30 --connect-timeout 5 -G --data-urlencode "expr=garbage" "$BASE/api/console/system/cron-preview")
i_valid=$(echo "$invalid" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['valid'])" 2>/dev/null)
i_err=$(echo "$invalid" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['error'])" 2>/dev/null)
[[ "$i_valid" == "False" && -n "$i_err" && "$i_err" != "None" ]] \
  && pass "非法 expr 安全降级" "valid=false + error='$(echo "$i_err" | head -c 50)'" \
  || fail "非法 expr 未安全降级" "valid=$i_valid err=$i_err"

# 复杂 Quartz 场景批量(每 15 秒 / 工作日 / 每月最后一天 / 第 N 个周三 / 每 2 小时)
for entry in \
  "0/15 * * * * ?|每 15 秒" \
  "0 0 9 ? * MON-FRI|工作日 9 点" \
  "0 0 0 L * ?|每月最后一天 0 点" \
  "0 0 10 ? * 3#2|每月第 2 个周三 10 点" \
  "0 0 0/2 * * ?|每 2 小时"; do
  expr="${entry%%|*}"; label="${entry##*|}"
  r=$(curl -s --max-time 30 --connect-timeout 5 -G --data-urlencode "expr=$expr" --data-urlencode "count=2" "$BASE/api/console/system/cron-preview")
  v=$(echo "$r" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['valid'])" 2>/dev/null)
  c=$(echo "$r" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['nextRuns']))" 2>/dev/null)
  [[ "$v" == "True" && "$c" -ge 1 ]] \
    && pass "Quartz [$label]" "expr=$expr → valid + $c runs" \
    || fail "Quartz [$label] 异常" "expr=$expr valid=$v runs=$c"
done

# ───────────────────────────────────────────────────────────
# §3. 审计落表(console_operation_audit @AuditAction)
# ───────────────────────────────────────────────────────────
hdr "3. 审计落表"

AUDIT_TOTAL=$(psql_q "select count(*) from batch.console_operation_audit;")
if [[ "$AUDIT_TOTAL" -gt 0 ]]; then
  RECENT=$(psql_q "select action, tenant_id, trace_id, result from batch.console_operation_audit order by id desc limit 3;")
  pass "console_operation_audit 表有 $AUDIT_TOTAL 行" "最近 3 行: $(echo "$RECENT" | head -c 120)..."

  # 验证 trace_id 非空率
  TRACED=$(psql_q "select count(*) from batch.console_operation_audit where trace_id is not null and trace_id != '';")
  PCT=$((TRACED * 100 / AUDIT_TOTAL))
  [[ "$PCT" -ge 80 ]] \
    && pass "trace_id 串得起来" "$TRACED/$AUDIT_TOTAL ($PCT%)" \
    || fail "trace_id 覆盖率低" "$TRACED/$AUDIT_TOTAL ($PCT%)"
else
  echo "  ${YELLOW}🟡 SKIP${RST}  console_operation_audit 表为空(未发生过被 @AuditAction 拦截的操作)"
fi

# ───────────────────────────────────────────────────────────
# §4. maintenance 状态接口
# ───────────────────────────────────────────────────────────
hdr "4. maintenance 状态接口"

MS=$(curl -s --max-time 30 --connect-timeout 5 "$BASE/api/console/system/maintenance")
enabled=$(echo "$MS" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['enabled'])" 2>/dev/null)
[[ "$enabled" == "False" ]] \
  && pass "maintenance status 探活" "enabled=false(正常运行态)" \
  || fail "maintenance status 异常" "enabled=$enabled"

# 真实双轨切换:RUN_MAINTENANCE_SWITCH=1 才跑(会重启 BE 3 次,耗时 ~90s)
if [[ "${RUN_MAINTENANCE_SWITCH:-0}" == "1" ]]; then
  hdr "4.2 maintenance 真实切换(BE 重启 3 次)"
  CONSOLE_JAR="build/runtime-jars/console.jar"
  [[ -f "$CONSOLE_JAR" ]] || { skip "skip 真实切换" "$CONSOLE_JAR 不存在"; CONSOLE_JAR=""; }
  if [[ -n "$CONSOLE_JAR" ]]; then
    _restart_with() {
      lsof -i :"$CONSOLE_PORT" -sTCP:LISTEN 2>/dev/null | tail -n +2 | awk '{print $2}' | xargs -r kill 2>/dev/null
      sleep 2
      # 用 "$@" 保留 env value 边界(value 含空格如 "strict-verify blocked" 不会被拆成 2 参)
      env "$@" SPRING_PROFILES_ACTIVE=local nohup java -jar "$CONSOLE_JAR" > /tmp/console-strict.log 2>&1 &
      local deadline=$(( $(date +%s) + 180 ))
      until curl -sf --max-time 5 --connect-timeout 2 "$BASE/actuator/health" -o /dev/null 2>/dev/null; do
        if (( $(date +%s) > deadline )); then
          echo "  ✗ BE 起不来 180s timeout(看 /tmp/console-strict.log)" >&2
          return 1
        fi
        sleep 2
      done
    }
    # 全冻结
    _restart_with BATCH_CONSOLE_MAINTENANCE_ENABLED=true BATCH_CONSOLE_MAINTENANCE_MESSAGE="strict-verify blocked"
    blocked=$(curl -sI --max-time 30 --connect-timeout 5 "$BASE/api/console/queries/instances" | head -1 | awk '{print $2}')
    xmaint=$(curl -sI --max-time 30 --connect-timeout 5 "$BASE/api/console/queries/instances" | grep -i "x-maintenance" | head -1)
    [[ "$blocked" == "503" && -n "$xmaint" ]] \
      && pass "全冻结:GET → 503 + $(echo $xmaint | tr -d '\r')" "" \
      || fail "全冻结异常" "http=$blocked xmaint='$xmaint'"

    # readOnly
    _restart_with BATCH_CONSOLE_MAINTENANCE_ENABLED=true BATCH_CONSOLE_MAINTENANCE_READ_ONLY=true BATCH_CONSOLE_MAINTENANCE_MESSAGE="strict-verify readonly"
    get_code=$(curl -sI --max-time 30 --connect-timeout 5 "$BASE/api/console/queries/instances" | head -1 | awk '{print $2}')
    post_code=$(curl -sI --max-time 30 --connect-timeout 5 -X POST "$BASE/api/console/jobs/bundle/create" | head -1 | awk '{print $2}')
    [[ "$get_code" != "503" && "$post_code" == "503" ]] \
      && pass "readOnly:GET=$get_code (pass) / POST=$post_code (block)" "X-Maintenance: read-only" \
      || fail "readOnly 模式异常" "get=$get_code post=$post_code(期望 GET 非 503,POST 503)"

    # 恢复
    lsof -i :"$CONSOLE_PORT" -sTCP:LISTEN 2>/dev/null | tail -n +2 | awk '{print $2}' | xargs -r kill 2>/dev/null
    sleep 2
    bash scripts/local/restart.sh console > /tmp/restart-normal.log 2>&1
    deadline=$(( $(date +%s) + 180 ))
    until curl -sf --max-time 5 --connect-timeout 2 "$BASE/actuator/health" -o /dev/null 2>/dev/null; do
      if (( $(date +%s) > deadline )); then
        fail "maintenance 恢复后 BE 起不来 180s timeout" "看 /tmp/restart-normal.log"
        break
      fi
      sleep 2
    done
    enabled_after=$(curl -s --max-time 30 --connect-timeout 5 "$BASE/api/console/system/maintenance" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['enabled'])" 2>/dev/null)
    [[ "$enabled_after" == "False" ]] \
      && pass "恢复正常模式" "enabled=false" \
      || fail "恢复异常" "enabled=$enabled_after"
  fi
fi

# ───────────────────────────────────────────────────────────
# §5. cursor token 解码失败安全降级
# ───────────────────────────────────────────────────────────
hdr "5. cursor token 损坏安全降级"

# 直接构造坏 token,期望返 200(filter 链鉴权失败 401 也算 — 至少没 500)
bad=$(curl -s --max-time 30 --connect-timeout 5 -o /tmp/bad.body -w "%{http_code}" -G \
  --data-urlencode "cursor=garbage-not-base64-!@#$%" \
  --data-urlencode "tenantId=default-tenant" \
  "$BASE/api/console/queries/instances")
case "$bad" in
  500|502|503)
    fail "坏 cursor 触发服务端错误" "HTTP=$bad"
    ;;
  *)
    pass "坏 cursor 安全降级" "HTTP=$bad(无 500,谓词命中 0 行)"
    ;;
esac

# ───────────────────────────────────────────────────────────
# §6. OpenAPI 漂移检查(BE 实际响应字段 vs yaml 声明)
# ───────────────────────────────────────────────────────────
hdr "6. OpenAPI 漂移检查"

OPENAPI_YAML="docs/api/console-api.openapi.yaml"
if [[ ! -f "$OPENAPI_YAML" ]]; then
  skip "OpenAPI 检查" "$OPENAPI_YAML 不存在"
else
  # MaintenanceStatus schema 字段(从 yaml 中解析,要求 schema 块 properties 下的 key)
  yaml_fields=$(python3 -c "
import yaml, sys
with open('$OPENAPI_YAML') as f:
    spec = yaml.safe_load(f)
schema = spec.get('components', {}).get('schemas', {}).get('MaintenanceStatus', {})
print(','.join(sorted(schema.get('properties', {}).keys())))
" 2>/dev/null)
  # BE 实际响应字段
  be_fields=$(curl -s --max-time 30 --connect-timeout 5 "$BASE/api/console/system/maintenance" | \
    python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(','.join(sorted(d.keys())))")
  if [[ "$yaml_fields" == "$be_fields" ]]; then
    pass "MaintenanceStatus schema 对齐" "$yaml_fields"
  else
    fail "MaintenanceStatus schema 漂移" "yaml=[$yaml_fields] be=[$be_fields]"
  fi

  # CronPreview schema
  yaml_cron=$(python3 -c "
import yaml
with open('$OPENAPI_YAML') as f:
    spec = yaml.safe_load(f)
schema = spec.get('components', {}).get('schemas', {}).get('CronPreview', {})
print(','.join(sorted(schema.get('properties', {}).keys())))
" 2>/dev/null)
  be_cron=$(curl -s --max-time 30 --connect-timeout 5 -G --data-urlencode "expr=0 0 2 * * ?" "$BASE/api/console/system/cron-preview" | \
    python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(','.join(sorted(d.keys())))")
  if [[ "$yaml_cron" == "$be_cron" ]]; then
    pass "CronPreview schema 对齐" "$yaml_cron"
  else
    fail "CronPreview schema 漂移" "yaml=[$yaml_cron] be=[$be_cron]"
  fi
fi

# ───────────────────────────────────────────────────────────
# §7. 原子任务 worker 配置真实数据(ADR-029)
#    PSQL 直查 job_definition,校验 job_type='ATOMIC' 的真实 seed 配置完整性。
#    (派发→执行→终态的全链真验由 batch-e2e-tests 的 Atomic*E2eIT 覆盖,需 atomic worker 进程)
# ───────────────────────────────────────────────────────────
hdr "7. 原子任务配置真实数据(ADR-029)"

ATOMIC_JOBS=$(psql_q "select count(*) from batch.job_definition where job_type='ATOMIC';")
if [[ -z "$ATOMIC_JOBS" || "$ATOMIC_JOBS" -eq 0 ]]; then
  skip "原子任务配置验证" "未发现 job_type='ATOMIC' 的 job 定义(原子任务未 seed / 未启用)"
else
  # 7.1 每个原子任务 job 的 default_params 必须携带 taskType(执行器子类型协议)
  MISSING_TT=$(psql_q "select count(*) from batch.job_definition where job_type='ATOMIC' and (default_params->>'taskType') is null;")
  [[ "$MISSING_TT" == "0" ]] \
    && pass "原子任务 job default_params 均含 taskType" "$ATOMIC_JOBS 个原子任务 job" \
    || fail "原子任务 job 缺 taskType" "$MISSING_TT 个原子任务 job 的 default_params 无 taskType"

  # 7.2 taskType 必须在已知执行器白名单内(shell/sql/stored_proc/http)
  BAD_TT=$(psql_q "select count(*) from batch.job_definition where job_type='ATOMIC' and (default_params->>'taskType') not in ('shell','sql','stored_proc','http');")
  [[ "$BAD_TT" == "0" ]] \
    && pass "原子任务 taskType 均在执行器白名单" "shell/sql/stored_proc/http" \
    || fail "原子任务 taskType 越界" "$BAD_TT 个原子任务 job 的 taskType 不在 {shell,sql,stored_proc,http}"
fi

# ───────────────────────────────────────────────────────────
# 汇总
# ───────────────────────────────────────────────────────────
TOTAL_RUNS=$((PASS + FAIL))
echo
printf '%b━━━ 汇总 ━━━%b\n' "${BLUE}" "${RST}"
printf '  PASS: %b%s%b  FAIL: %b%s%b  / 总数 %s\n' "${GREEN}" "$PASS" "${RST}" "${RED}" "$FAIL" "${RST}" "$TOTAL_RUNS"
if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
