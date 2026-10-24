#!/usr/bin/env bash
# =========================================================
# 29-tenant-config-scenarios.sh:租户场景模板真实 API / Excel 预览矩阵
#
# 验证 IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC / Workflow definitions / SCHEDULE、
# 组合场景及 ALL 模板的 Sheet/列投影，确保租户包不暴露平台资源队列和运行阈值。
# 每个生成的工作簿都会走 upload → preview；不执行 Apply，不写入作业配置。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export SIM_STAGE_NAME="tenant-config-scenarios"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python

RATE_LIMIT_RETRY_DELAY_SECONDS=65
RATE_LIMIT_MAX_RETRIES=2

TENANT_ID="${SIM_TENANT_CONFIG_TENANT_ID:-ta}"
COOKIE_JAR=""
TMP_DIR="$(mktemp -d)"
trap '[[ -z "$COOKIE_JAR" ]] || rm -f "$COOKIE_JAR"; rm -rf "$TMP_DIR"' EXIT

echo "==> 租户场景模板 API / Excel / upload-preview 矩阵 (tenant=$TENANT_ID)"

# 每次模板下载、上传和预览都会计入 Console 的昂贵操作限流；9 个场景共 27 次请求，
# 超过默认 10 次/分钟。429 是限流保护生效，不应让完整契约矩阵偶发失败。
curl_expensive_with_retry() {
  local attempt=0 code
  while true; do
    attempt=$((attempt + 1))
    code="$(curl "$@" || true)"
    if [[ "$code" != 429 || "$attempt" -gt "$RATE_LIMIT_MAX_RETRIES" ]]; then
      printf '%s' "$code"
      return 0
    fi
    echo "  Console rate limit returned 429; retrying in ${RATE_LIMIT_RETRY_DELAY_SECONDS}s (${attempt}/${RATE_LIMIT_MAX_RETRIES})" >&2
    sleep "$RATE_LIMIT_RETRY_DELAY_SECONDS"
  done
}

if [[ -n "${CONSOLE_TOKEN:-}" ]]; then
  AUTH_ARGS=(-H "Cookie: batch_console_token=$CONSOLE_TOKEN")
else
  probe_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
    -H "X-Tenant-Id: $TENANT_ID" "$CONSOLE_BASE/api/console/auth/check" || true)"
  if [[ "$probe_code" == 204 || "$probe_code" == 200 ]]; then
    AUTH_ARGS=()
  else
    COOKIE_JAR="$(mktemp -t sim-tenant-config-cookie.XXXXXX)"
    login_payload="$("$PYTHON_BIN" -c 'import json,os; print(json.dumps({"username":os.environ.get("CONSOLE_USER","admin"),"password":os.environ.get("CONSOLE_PASS","admin123")}))')"
    login_code="$(curl -sS -o "$TMP_DIR/login.json" -w '%{http_code}' -c "$COOKIE_JAR" \
      -H 'Content-Type: application/json' -X POST \
      "$CONSOLE_BASE/api/console/auth/login" --data-raw "$login_payload" || true)"
    if [[ "$login_code" != 200 ]]; then
      echo "✗ Console 登录失败 HTTP=$login_code: $(head -c 300 "$TMP_DIR/login.json" 2>/dev/null)" >&2
      exit 1
    fi
    AUTH_ARGS=(-b "$COOKIE_JAR")
  fi
fi

run_case() {
  local case_name="$1" scenarios="$2" expected_sheets="$3" index="$4"
  local workbook="$TMP_DIR/scenario-$index.xlsx"
  local query=""
  local scenario
  IFS='+' read -r -a scenario_items <<< "$scenarios"
  for scenario in "${scenario_items[@]}"; do
    query+="&scenarios=$scenario"
  done
  query="${query#&}"

  local download_code
  download_code="$(curl_expensive_with_retry -sS --max-time 60 --connect-timeout 5 -o "$workbook" -w '%{http_code}' \
    -H "X-Tenant-Id: $TENANT_ID" "${AUTH_ARGS[@]}" \
    "$CONSOLE_BASE/api/console/config/tenant-package/excel/scenario-template?$query" || true)"
  if [[ ! "$download_code" =~ ^2 ]]; then
    echo "✗ $case_name 模板下载失败 HTTP=$download_code" >&2
    head -c 500 "$workbook" 2>/dev/null >&2 || true
    return 1
  fi

  "$PYTHON_BIN" - "$workbook" "$case_name" "$expected_sheets" <<'PY'
import sys
from openpyxl import load_workbook

path, case_name, expected = sys.argv[1:]
expected_business_sheets = expected.split(",")
support_sheets = ["填写说明", "填写顺序", "字典", "字段说明", "校验"]
expected_sheets = expected_business_sheets + support_sheets
workbook = load_workbook(path, read_only=True, data_only=True)
try:
    actual_sheets = workbook.sheetnames
    assert actual_sheets == expected_sheets, (
        f"{case_name}: sheet mismatch: expected business/support sheets="
        f"{expected_sheets}, actual={actual_sheets}"
    )
    assert "resource_queue" not in actual_sheets, f"{case_name}: platform resource sheet leaked"
    for sheet_name in expected_business_sheets:
        sheet = workbook[sheet_name]
        headers = [cell.value for cell in next(sheet.iter_rows(min_row=1, max_row=1))]
        assert headers and all(isinstance(value, str) for value in headers), (
            f"{case_name}/{sheet_name}: missing header row"
        )
        if sheet_name == "job_definition":
            forbidden = {"queue_code", "worker_group", "shard_strategy"}.intersection(headers)
            assert not forbidden, f"{case_name}: platform routing headers leaked: {sorted(forbidden)}"
        if sheet_name == "job_monitoring_policy":
            forbidden = {
                "soft_runtime_seconds", "start_grace_seconds",
                "soft_runtime_severity", "start_grace_severity",
            }.intersection(headers)
            assert not forbidden, f"{case_name}: platform monitoring headers leaked: {sorted(forbidden)}"
    print(f"  ✓ {case_name}: sheets={','.join(actual_sheets)}")
finally:
    workbook.close()
PY

  local key
  key="sim-tenant-scenario-${index}-$(date +%s)"
  local upload_body="$TMP_DIR/upload-$index.json"
  local upload_code
  upload_code="$(curl_expensive_with_retry -sS --max-time 90 --connect-timeout 5 -o "$upload_body" -w '%{http_code}' -X POST \
    -H "X-Tenant-Id: $TENANT_ID" "${AUTH_ARGS[@]}" \
    -H "Idempotency-Key: $key" -H "X-Request-Id: $key" \
    -F "file=@$workbook;type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
    "$CONSOLE_BASE/api/console/config/tenant-package/excel/upload?tenantId=$TENANT_ID" || true)"
  if [[ ! "$upload_code" =~ ^2 ]]; then
    echo "✗ $case_name 工作簿上传失败 HTTP=$upload_code: $(head -c 500 "$upload_body")" >&2
    return 1
  fi

  local upload_token
  upload_token="$("$PYTHON_BIN" - "$upload_body" <<'PY'
import json
import sys
from pathlib import Path
response = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if response.get("code") != "SUCCESS":
    raise SystemExit(f"upload rejected: {response}")
print(response.get("data", {}).get("uploadToken", ""))
PY
)"
  [[ -n "$upload_token" ]] || { echo "✗ $case_name 上传未返回 uploadToken" >&2; return 1; }

  local preview_body="$TMP_DIR/preview-$index.json"
  local preview_code
  preview_code="$(curl_expensive_with_retry -sS --max-time 60 --connect-timeout 5 -o "$preview_body" -w '%{http_code}' \
    -H "X-Tenant-Id: $TENANT_ID" "${AUTH_ARGS[@]}" \
    "$CONSOLE_BASE/api/console/config/tenant-package/excel/preview/$upload_token" || true)"
  if [[ ! "$preview_code" =~ ^2 ]]; then
    echo "✗ $case_name 预览失败 HTTP=$preview_code: $(head -c 500 "$preview_body")" >&2
    return 1
  fi
  "$PYTHON_BIN" - "$preview_body" "$case_name" <<'PY'
import json
import sys
from pathlib import Path
case_name = sys.argv[2]
response = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
data = response.get("data", {})
assert response.get("code") == "SUCCESS", f"{case_name}: preview rejected: {response}"
assert data.get("invalidRows") == 0, f"{case_name}: invalidRows={data.get('invalidRows')} issues={data.get('issues')}"
assert data.get("issues") == [], f"{case_name}: preview issues={data.get('issues')}"
assert data.get("previewToken"), f"{case_name}: previewToken missing"
print(f"  ✓ {case_name}: upload + preview valid; Apply intentionally not called")
PY
}

run_case IMPORT IMPORT 'job_definition,file_template_config' 1
run_case EXPORT EXPORT 'job_definition,file_template_config' 2
run_case PROCESS PROCESS 'job_definition' 3
run_case DISPATCH DISPATCH 'job_definition,file_channel_config' 4
run_case ATOMIC ATOMIC 'job_definition' 5
run_case WORKFLOW WORKFLOW 'job_definition,file_channel_config,file_template_config,workflow_definition,workflow_node,workflow_edge' 6
run_case SCHEDULE SCHEDULE 'business_calendar,batch_window,job_definition,job_monitoring_policy' 7
run_case IMPORT+DISPATCH IMPORT+DISPATCH 'job_definition,file_channel_config,file_template_config' 8
run_case ALL ALL 'business_calendar,batch_window,job_definition,job_monitoring_policy,file_channel_config,file_template_config,workflow_definition,workflow_node,workflow_edge' 9

echo "==> ✅ 租户场景模板 SIM 完成:9 个生成/校验/预览用例;未 Apply 配置"
