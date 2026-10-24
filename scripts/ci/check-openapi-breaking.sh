#!/usr/bin/env bash
# 用途：OpenAPI 破坏性变更。
# 用法：在仓库根目录执行 bash scripts/ci/check-openapi-breaking.sh；参数与前置环境见下方说明及所属 README。
# 守护:OpenAPI 破坏性变更(oasdiff)。
#
# 背景:已有 check-console-openapi-paths.py 守「controller ↔ openapi 路由存在性同步」,
# 但不查语义破坏 —— 删端点 / 删必填字段 / 收窄枚举 / 改类型,FE 等独立消费方会运行时崩。
# 本守护对每个受管 openapi,比 base 分支版本,命中 ERR 级 breaking 即 fail(additive/deprecation 仅 warning,放行)。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BASE_REF="${1:-${OASDIFF_BASE_REF:-origin/main}}"
GATE_CODE="OPENAPI_BREAKING"
GATE_NAME="OpenAPI 破坏性变更"

SPECS=(
  "docs/api/console-api.openapi.yaml"
  "docs/api/orchestrator-internal.openapi.yaml"
)

is_pure_merge_revert() {
  local base_revision candidate candidate_parents subject
  base_revision="$(git rev-parse --verify "$BASE_REF" 2>/dev/null || true)"
  [[ -n "$base_revision" ]] || return 1

  while read -r candidate; do
    candidate_parents="$(git rev-list --parents -n1 "$candidate" 2>/dev/null || true)"
    subject="$(git log -1 --format=%s "$candidate" 2>/dev/null || true)"
    if [[ "$candidate_parents" == "$candidate $base_revision" ]] \
      && [[ "$subject" == 'Revert "Merge '* ]]; then
      return 0
    fi
  done < <(
    git rev-list --parents -n1 HEAD \
      | awk '{for (i = 2; i <= NF; i++) print $i}'
  )
  return 1
}

if ! command -v oasdiff >/dev/null 2>&1; then
  echo "❌ 不通过 | code=${GATE_CODE} | gate=${GATE_NAME} | exit_code=1"
  echo "  - 未找到 oasdiff(CI 应经 action 安装)"
  exit 1
fi

if is_pure_merge_revert; then
  echo "✅ 通过 | code=${GATE_CODE} | gate=${GATE_NAME} | reason=pure merge revert restores the base API"
  exit 0
fi

fail=0
for spec in "${SPECS[@]}"; do
  if ! git cat-file -e "${BASE_REF}:${spec}" 2>/dev/null; then
    echo "ℹ️  $spec 在 base 不存在(新增 spec),跳过 breaking 比对"
    continue
  fi
  base_tmp="$(mktemp)"
  clarification_ignore_tmp="$(mktemp)"
  raw_diff_tmp="$(mktemp)"
  git show "${BASE_REF}:${spec}" > "$base_tmp"
  echo "===== $spec(vs $BASE_REF)====="
  oasdiff breaking "$base_tmp" "$spec" --format singleline 2>/dev/null > "$raw_diff_tmp" || true
  # 旧 schema 完全未声明 data 类型时,补成实际 object/array 只是契约澄清,不会改变 wire。
  # oasdiff 会把 blank -> object/array 误判为 response-property-type-changed ERR。
  # 仅动态忽略这一种精确形态;已有明确类型之间的变化仍由 breaking gate 拦截。
  #
  # 批次2(job 域 Map 收敛)另修正了 12 条历史误声明为 CommonResponseString 的 path
  # (运行时一直返对象,`string` -> object 是 spec 修正非 wire 变化)。该忽略**收窄为
  # path 白名单**,避免未来别处真实 string -> object 破坏被全局掩盖;白名单外的
  # string -> object/array 仍按 ERR 拦截。
  # 批次4(notification/workflow/file/rbac Map 收敛)另修正 workflow-runs 生命周期动作
  # 5 条历史误声明为 CommonResponseString 的 path(运行时一直返对象),同属 spec 修正非 wire 变化。
  string_fix_paths='/api/console/(instances/\{id\}/(cancel|terminate|pause|resume)|instances/partitions/\{id\}/(cancel|retry)|ops/triggers/\{jobCode\}/(register|unregister|pause|resume)|scheduler/(pause-all|resume-all)|workflow-runs/\{id\}/(cancel|terminate|pause|resume|skip-node))'
  grep -E 'response.s property type/format changed from ``/`` to `(object|array)`/``.*\[response-property-type-changed\]' "$raw_diff_tmp" \
      > "$clarification_ignore_tmp" || true
  grep -E "in API (GET|POST|PUT|DELETE|PATCH) ${string_fix_paths} .*response.s property type/format changed from \`string\`/\`\` to \`object\`/\`\`.*\[response-property-type-changed\]" "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
  # trigger 的 dryRun 从首次实现即返回校验对象，普通触发始终返回字符串。
  # 仅允许补录该既有分支，不放行其他端点、其他 data 子结构或新增 union 成员。
  grep -E 'in API POST /api/console/jobs/trigger .*added `#/components/schemas/DryRunTriggerResult, subschema #1` to the `allOf\[subschema #2\]/data` response property `oneOf` list.*\[response-property-one-of-added\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
  grep -E 'in API POST /api/console/jobs/trigger .*the `allOf\[subschema #2\]/data` response.s property type/format changed from `string`/`` to ``/``.*\[response-property-type-changed\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
  # configPayloadJson 自该端点落地起就在 Java DTO 上使用 @NotBlank，缺失请求始终返回 400；
  # 此处只把既有运行时契约补录进 spec。精确限定 path + property，其他新增 required 仍会失败。
  grep -E 'in API POST /api/console/config/releases .*request property `configPayloadJson` became required.*\[request-property-became-required\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
  # 配置包行内修正 DTO 在 base 代码中已用 @NotBlank 强制 previewToken；这里只补录漏写的既有运行时契约。
  # 仅允许精确端点与字段，其他请求新增必填属性仍由 breaking gate 拦截。
  grep -E 'in API POST /api/console/config/tenant-package/excel/preview/\{uploadToken\}/patch .*added the new required request property `previewToken`.*\[new-required-request-property\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
  # PR #1066（2026-09-27）经用户批准移除六个旧 toggle 路由，统一改用
  # PATCH /{id}/enabled。仅忽略下列精确路径，其他端点移除仍须阻断。
  # AI 对话已由调用方切换到 SSE，用户明确要求不保留旧 JSON 路由。
  if [[ "$spec" == "docs/api/console-api.openapi.yaml" ]]; then
    printf '%s\n' \
      'POST /api/console/ai/chat api path removed without deprecation' \
      'POST /api/console/alert-routings/{id}/toggle api path removed without deprecation' \
      'POST /api/console/batch-windows/{id}/toggle api path removed without deprecation' \
      'POST /api/console/calendars/{id}/toggle api path removed without deprecation' \
      'POST /api/console/pipeline-definitions/{id}/toggle api path removed without deprecation' \
      'POST /api/console/queues/{id}/toggle api path removed without deprecation' \
      'POST /api/console/quota-policies/{id}/toggle api path removed without deprecation' \
      >> "$clarification_ignore_tmp"

    # ADR-048 批准一次全新数据库、全组件同步切换：不提供旧 Job 根触发、租户 Pipeline
    # 定义或 relatedPipelineCode 的兼容面。这里只忽略此次模型切换实际移除的字段/路由，
    # 以及新模型必须的 Workflow 身份、版本和补偿目标字段；新增的其它 breaking 仍由门禁拦截。
    grep -E 'in API (POST|GET) /api/console/(config/sync/(export|import|preview)|jobs/bundle/(create|export|import)) .*pipelineDefinitions.*\[response-required-property-removed\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    grep -E 'in API POST /api/console/config/(tenant-copy(/preview)?|tenant-overlay/preview) .*removed the enum value `PIPELINE_DEFINITION`.*\[request-property-enum-value-removed\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    grep -E 'in API POST /api/console/config/tenant-package/excel/apply/\{uploadToken\} .*`data/pipeline(Inserted|Updated)`.*\[response-required-property-removed\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    printf '%s\n' \
      'POST /api/console/jobs/batch-trigger api path removed without deprecation' \
      'POST /api/console/jobs/trigger api path removed without deprecation' \
      'GET /api/console/queries/pipeline-definitions/codes api path removed without deprecation' \
      'PUT /api/console/workflow-definitions/{id} api removed without deprecation' \
      >> "$clarification_ignore_tmp"
    grep -E 'in API (POST /api/console/pipeline-definitions|PUT /api/console/pipeline-definitions/\{id\}|PATCH /api/console/pipeline-definitions/\{id\}/enabled) .*response with the status `200`.*\[response-success-status-removed\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    grep -E 'in API POST /api/console/ops/cache/evict-job-definition .*new required `query` request parameter `workflowCode`.*\[new-required-request-parameter\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    grep -E 'in API POST /api/console/ops/triggers/\{jobCode\}/(pause|register|resume|unregister) .*jobCode.*\[response-required-property-removed\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    grep -E 'in API (GET /api/console/queries/workflow-topology|POST /api/console/workflow-definitions|GET /api/console/workflow-definitions/\{id\}(/versions/\{version\})?|PUT /api/console/workflow-definitions/\{id\}/full) .*relatedPipelineCode.*\[response-required-property-removed\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    # 整合计划 I6/R35 已批准人工 skip 的原因审计；前后端与真实认证验收 B31/B32 同步完成。
    # 仅限 ADR-048 本次同步切换的 POST + 此路径 + query reason，其他参数/方法仍阻断。
    grep -E 'in API POST /api/console/workflow-runs/\{id\}/skip-node .*new required `query` request parameter `reason`.*\[new-required-request-parameter\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    grep -E 'in API PUT /api/console/workflow-definitions/\{id\}/full .*expectedVersion.*\[request-property-became-required\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
    grep -E 'in API POST /api/console/self-service/jobs/compensation-request .*compensationType.*(request-property-became-required|request-property-became-enum)|in API POST /api/console/self-service/jobs/compensation-request .*targetInstanceNo.*\[request-property-became-required\]' "$raw_diff_tmp" \
      >> "$clarification_ignore_tmp" || true
  fi
  # 仅 ERR 级 breaking 才 fail;成功时静默(该 spec 有重复参数定义,oasdiff 会刷大量
  # request-parameter-removed 的 WARN 噪音,失败时才打全量便于定位)。
  out="$(oasdiff breaking "$base_tmp" "$spec" --fail-on ERR \
    --err-ignore "$clarification_ignore_tmp" 2>&1)" && rc=0 || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "✅ 无 ERR 级破坏性变更"
  else
    echo "$out"
    echo "❌ $spec 存在 ERR 级破坏性变更(见上)。如确为有意 breaking,需走版本化端点 + 弃用流程。"
    fail=1
  fi
  rm -f "$base_tmp" "$clarification_ignore_tmp" "$raw_diff_tmp"
  echo
done

if [[ "$fail" -ne 0 ]]; then
  echo "❌ 不通过 | code=${GATE_CODE} | gate=${GATE_NAME} | exit_code=1"
  echo "  - OpenAPI 破坏性变更门禁未通过。"
  exit 1
fi
echo "✅ 通过 | code=${GATE_CODE} | gate=${GATE_NAME}"
