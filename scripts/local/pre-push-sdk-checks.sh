#!/usr/bin/env bash
# =========================================================
# pre-push-sdk-checks.sh
#
# SDK 路线图 PR 推送前自查 — 拦截两类高频被 CI 拦截的事:
#   1. Java 编码反例(CLAUDE.md 「Java 编码细则」10 条)
#   2. API 文档对齐(controller 改了但 OpenAPI / protocol.md 没改)
#
# 设计原则:
#   - 只检查"本 PR 改过的文件",不扫全仓(快,< 30s)
#   - fast-fail:第一类 fail 立刻退出,不跑后面的
#   - 可通过 SKIP_SDK_CHECKS=1 跳过(应急用,正常 PR 必跑)
#   - CI=1 时关闭颜色 + 简化输出
#
# 用法:
#   bash scripts/local/pre-push-sdk-checks.sh                    # 默认对比 origin/main
#   bash scripts/local/pre-push-sdk-checks.sh --base origin/dev  # 自定义 base
#   bash scripts/local/pre-push-sdk-checks.sh --skip-build       # 跳过 clean compile(快速预检)
#   SKIP_SDK_CHECKS=1 git push                                   # 紧急绕过
#
# 集成方式:
#   .git/hooks/pre-push 末尾 source 此脚本(见 README)
# =========================================================

set -uo pipefail

# ── 参数解析 ─────────────────────────────────────────────
BASE_REF="origin/main"
SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --base)        BASE_REF="$2"; shift 2 ;;
    --base=*)      BASE_REF="${arg#--base=}" ;;
    --skip-build)  SKIP_BUILD=1 ;;
    --help|-h)
      sed -n '2,30p' "$0"
      exit 0
      ;;
  esac
done

# ── 紧急绕过开关 ─────────────────────────────────────────
if [[ "${SKIP_SDK_CHECKS:-0}" = "1" ]]; then
  echo "⚠️  SKIP_SDK_CHECKS=1 设置,跳过 SDK 路线图自查(应急用,正常 PR 必跑)"
  exit 0
fi

# ── 颜色 ─────────────────────────────────────────────────
if [[ "${CI:-0}" = "1" ]] || [[ ! -t 1 ]]; then
  RED=""; YELLOW=""; GREEN=""; BLUE=""; RESET=""
else
  RED='\033[0;31m'; YELLOW='\033[0;33m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; RESET='\033[0m'
fi

fail()  { printf "${RED}❌ %s${RESET}\n" "$*" >&2; }
warn()  { printf "${YELLOW}⚠️  %s${RESET}\n" "$*"; }
ok()    { printf "${GREEN}✅ %s${RESET}\n" "$*"; }
info()  { printf "${BLUE}→  %s${RESET}\n" "$*"; }

# ── 前置:有没有可比对的 base ─────────────────────────────
if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  warn "找不到 $BASE_REF,尝试 git fetch ..."
  git fetch origin main --quiet 2>/dev/null || true
  if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
    warn "无法对比 base,本地仓库可能从未跟 origin 同步;跳过 diff 类检查"
    BASE_REF=""
  fi
fi

# 当前 PR 改过的文件(只检查改动,不扫全仓)
if [[ -n "$BASE_REF" ]]; then
  CHANGED_FILES=$(git diff --name-only "$BASE_REF"...HEAD 2>/dev/null || true)
else
  CHANGED_FILES=$(git diff --name-only HEAD~5..HEAD 2>/dev/null || true)
fi

if [[ -z "$CHANGED_FILES" ]]; then
  info "本次 push 无文件变更,跳过自查"
  exit 0
fi

CHANGED_JAVA=$(echo "$CHANGED_FILES" | grep -E "\.java$" || true)
CHANGED_CTL=$(echo "$CHANGED_FILES" | grep -E "Controller\.java$" || true)
CHANGED_YAML=$(echo "$CHANGED_FILES" | grep -E "openapi\.yaml$" || true)
CHANGED_PROTOCOL=$(echo "$CHANGED_FILES" | grep -E "console-api-protocol\.md$" || true)
CHANGED_FLYWAY=$(echo "$CHANGED_FILES" | grep -E "db/migration/V[0-9]+__" || true)

info "本次 push 涉及:"
[[ -n "$CHANGED_JAVA" ]]     && info "  Java 文件 $(echo "$CHANGED_JAVA" | wc -l | tr -d ' ') 个"
[[ -n "$CHANGED_CTL" ]]      && info "  Controller $(echo "$CHANGED_CTL" | wc -l | tr -d ' ') 个"
[[ -n "$CHANGED_YAML" ]]     && info "  OpenAPI yaml $(echo "$CHANGED_YAML" | wc -l | tr -d ' ') 个"
[[ -n "$CHANGED_FLYWAY" ]]   && info "  Flyway migration $(echo "$CHANGED_FLYWAY" | wc -l | tr -d ' ') 个"
echo ""

errors=0

# ═════════════════════════════════════════════════════════
# 检查 1:Java 编码反例(CLAUDE.md 「Java 编码细则」10 条节选)
#   关键:只扫"本 PR 真新增的行"(+ 开头的 diff 行),不扫全文件历史代码
# ═════════════════════════════════════════════════════════
if [[ -n "$CHANGED_JAVA" ]] && [[ -n "$BASE_REF" ]]; then
  info "──────────────────────────────────────"
  info "检查 1:Java 编码反例自查(仅扫本 PR 新增行)"
  info "──────────────────────────────────────"

  # 生成只含"本 PR 新增行"的临时 diff 文件
  TMP_DIFF=$(mktemp)
  trap 'rm -f "$TMP_DIFF"' EXIT
  # 用 git diff -U0 + 解析 hunk header 得到"文件:行号:内容"
  # 只保留 + 行(新增),过滤掉 +++ 头部
  for f in $CHANGED_JAVA; do
    [[ -f "$f" ]] || continue
    # 跳过 test 文件的某些检查会单独标记
    git diff -U0 "$BASE_REF...HEAD" -- "$f" 2>/dev/null | \
      awk -v file="$f" '
        /^@@/ {
          # @@ -a,b +c,d @@ 格式
          match($0, /\+[0-9]+/)
          line = substr($0, RSTART+1, RLENGTH-1) + 0
          line--
          next
        }
        /^\+\+\+/ { next }
        /^\+/ {
          line++
          content = substr($0, 2)
          # 过滤纯注释行(*、//、空)
          if (content ~ /^[[:space:]]*(\*|\/\/|\/\*|$)/) next
          print file ":" line ":" content
        }
        /^-/ { next }
        /^ / { line++ }
      '
  done > "$TMP_DIFF"

  ADDED_LINES_COUNT=$(wc -l < "$TMP_DIFF" | tr -d ' ')
  info "  本 PR 新增 $ADDED_LINES_COUNT 行有效代码(去注释)"

  if [[ "$ADDED_LINES_COUNT" -eq 0 ]]; then
    info "  无新增代码,跳过反例扫描"
    echo ""
  else
    # 用辅助函数扫,避免 src/test/ 的某些检查
    scan() {
      local pattern="$1"
      local desc="$2"
      local exclude_test="${3:-no}"
      local hits
      if [[ "$exclude_test" = "yes" ]]; then
        hits=$(grep -E "$pattern" "$TMP_DIFF" | grep -vE "/src/test/" | head -10 || true)
      else
        hits=$(grep -E "$pattern" "$TMP_DIFF" | head -10 || true)
      fi
      if [[ -n "$hits" ]]; then
        fail "$desc"
        echo "$hits" | sed 's/^/    /'
        errors=$((errors+1))
        return 1
      fi
      return 0
    }

    # 规约 #1:FQN(只看新增行的真实代码,排除 import/package 行)
    fqn_hits=$(grep -E ":[0-9]+:[[:space:]]*[^[:space:]\"].*java\.(util|net|io|time|nio|lang)\.[A-Z][a-zA-Z0-9_]+" "$TMP_DIFF" \
      | grep -vE ":[0-9]+:[[:space:]]*(import|package)[[:space:]]" \
      | head -10 || true)
    if [[ -n "$fqn_hits" ]]; then
      fail "规约 #1 违反 — FQN(必走 import)"
      echo "$fqn_hits" | sed 's/^/    /'
      errors=$((errors+1))
    else
      ok "规约 #1 FQN 通过"
    fi

    # 规约 #3:@Autowired field 注入(test 也守)
    scan ":[[:space:]]*@Autowired[[:space:]]*$" \
         "规约 #3 违反 — @Autowired field 注入(只允许构造器)" "no" \
      && ok "规约 #3 @Autowired 通过"

    # 规约 #4:@Transactional 在 Controller/Mapper(只看真注解,非 javadoc)
    trans_hits=$(grep -E "(Controller|Mapper)\.java:[0-9]+:[[:space:]]*@Transactional" "$TMP_DIFF" | head -5 || true)
    if [[ -n "$trans_hits" ]]; then
      fail "规约 #4 违反 — @Transactional 出现在 Controller / Mapper:"
      echo "$trans_hits" | sed 's/^/    /'
      errors=$((errors+1))
    else
      ok "规约 #4 @Transactional 位置通过"
    fi

    # 规约 #5:禁 throw new RuntimeException(test 跳过 — fixture 故意抛)
    scan ":[[:space:]]*throw[[:space:]]+new[[:space:]]+RuntimeException[[:space:]]*\(" \
         "规约 #5 违反 — 禁 throw new RuntimeException(用 BizException.of)" "yes" \
      && ok "规约 #5 RuntimeException 通过"

    # 规约 #7:日志拼接
    scan ":[[:space:]]*log\.(trace|debug|info|warn|error)\([^\"]*\"[^\"]*\"[[:space:]]*\+" \
         "规约 #7 违反 — 日志用占位符,禁字符串拼接" "no" \
      && ok "规约 #7 日志拼接通过"

    # 红线:ZoneId.systemDefault()(test 也守)
    scan "ZoneId\.systemDefault[[:space:]]*\(" \
         "红线违反 — ZoneId.systemDefault() 禁用(用 BatchTimezoneProvider)" "no" \
      && ok "红线 ZoneId.systemDefault 通过"

    # 红线:Charset.forName("UTF-8")
    scan "Charset\.forName[[:space:]]*\([[:space:]]*\"UTF-?8\"" \
         "红线违反 — Charset.forName(\"UTF-8\") 禁用(用 StandardCharsets.UTF_8)" "no" \
      && ok "红线 Charset.forName 通过"

    echo ""
  fi
fi

# ═════════════════════════════════════════════════════════
# 检查 2:API 文档对齐(CLAUDE.md 「API 文档同步」)
# ═════════════════════════════════════════════════════════
if [[ -n "$CHANGED_CTL" ]]; then
  info "──────────────────────────────────────"
  info "检查 2:API 文档对齐"
  info "──────────────────────────────────────"

  # 只对 batch-console-api 的 controller 强制要求(/internal/* 不入主 OpenAPI)
  CONSOLE_CTL=$(echo "$CHANGED_CTL" | grep "batch-console-api/" || true)

  if [[ -n "$CONSOLE_CTL" ]]; then
    info "Console-api controller 变更:"
    echo "$CONSOLE_CTL" | sed 's/^/    /'

    if [[ -z "$CHANGED_YAML" ]] || ! echo "$CHANGED_YAML" | grep -q "console-api.openapi.yaml"; then
      fail "controller 改了但 docs/api/console-api.openapi.yaml 没改 — CI pr-gate 会拦"
      info "  修复:同步更新 docs/api/console-api.openapi.yaml(补 path + schema)"
      errors=$((errors+1))
    else
      ok "OpenAPI yaml 同步更新了"
    fi

    if [[ -z "$CHANGED_PROTOCOL" ]]; then
      fail "controller 改了但 docs/api/console-api-protocol.md Changelog 没追加"
      info "  修复:在 protocol.md 的 Changelog 表追加 日期 + 摘要(日期倒序)"
      errors=$((errors+1))
    else
      ok "protocol.md Changelog 同步更新了"
    fi
  fi

  # /internal/* controller 提示(不强制,但建议)
  INTERNAL_CTL=$(echo "$CHANGED_CTL" | grep -E "batch-orchestrator/.*Controller" || true)
  if [[ -n "$INTERNAL_CTL" ]]; then
    warn "orchestrator /internal/* controller 变更(SDK 准客户):"
    echo "$INTERNAL_CTL" | sed 's/^/    /'
    info "  建议同步 docs/api/orchestrator-internal.openapi.yaml(若已立 — Phase 0 项)"
  fi

  echo ""
fi

# ═════════════════════════════════════════════════════════
# 检查 3:Flyway 版本号唯一(防止双人同 V160 冲突)
# ═════════════════════════════════════════════════════════
if [[ -n "$CHANGED_FLYWAY" ]]; then
  info "──────────────────────────────────────"
  info "检查 3:Flyway 版本号"
  info "──────────────────────────────────────"

  for f in $CHANGED_FLYWAY; do
    [[ -f "$f" ]] || continue
    version=$(basename "$f" | grep -oE "V[0-9]+" | head -1)
    info "  新 migration: $f (版本 $version)"

    # 找同版本号的其他 migration(本次 PR 没改但已存在)
    dup=$(find . -path "*db/migration/${version}__*" -not -path "*/target/*" 2>/dev/null | grep -v "$f" | head -3)
    if [[ -n "$dup" ]]; then
      fail "Flyway $version 已被占用:"
      echo "$dup" | sed 's/^/    /'
      info "  修复:本 PR 改用下一个版本号(V$(echo "$version" | tr -d V | awk '{print $1+1}'))"
      errors=$((errors+1))
    fi
  done

  # archive 镜像守护(批表必须有 archive 镜像)
  for f in $CHANGED_FLYWAY; do
    [[ -f "$f" ]] || continue
    # 检测是否包含批表 DDL(CREATE / ALTER TABLE batch.* 或不带 schema 前缀的)
    if grep -qiE "CREATE TABLE\s+(batch\.)?[a-z_]+|ALTER TABLE\s+(batch\.)?[a-z_]+" "$f" 2>/dev/null; then
      version=$(basename "$f" | grep -oE "V[0-9]+" | head -1)
      # 找对应 archive migration
      arch_mig=$(find . -path "*archive*migration*${version}__*" -not -path "*/target/*" 2>/dev/null | head -1)
      if [[ -z "$arch_mig" ]]; then
        warn "  $version 含批表 DDL,但未找到 archive 镜像 migration(CLAUDE.md「archive 冷表对齐」红线)"
        warn "  请人工确认是否需要补 archive 镜像(`ArchiveSchemaDriftCheck` 启动期会拦截)"
      fi
    fi
  done

  echo ""
fi

# ═════════════════════════════════════════════════════════
# 检查 4:clean compile(避免 stale cache 漏报真错)
# ═════════════════════════════════════════════════════════
if [[ $errors -eq 0 ]] && [[ $SKIP_BUILD -eq 0 ]] && [[ -n "$CHANGED_JAVA" ]]; then
  info "──────────────────────────────────────"
  info "检查 4:clean compile(memory: feedback_clean_before_push)"
  info "──────────────────────────────────────"

  # 识别改动涉及的模块,只 build 这些(快)
  MODULES=$(echo "$CHANGED_JAVA" | grep -oE "(batch-[a-z-]+|e2e-tests)" | sort -u | head -5)
  if [[ -z "$MODULES" ]]; then
    MODULES="batch-orchestrator,batch-worker-sdk,batch-console-api"
  else
    MODULES=$(echo "$MODULES" | tr '\n' ',' | sed 's/,$//')
  fi

  info "  clean compile -pl $MODULES"
  if ! mvn clean compile -pl "$MODULES" -am -q -DskipTests 2>/tmp/sdk-precheck-build.log; then
    fail "clean compile 失败:"
    tail -30 /tmp/sdk-precheck-build.log | sed 's/^/    /'
    info "  完整日志: /tmp/sdk-precheck-build.log"
    errors=$((errors+1))
  else
    ok "clean compile 通过"
  fi

  echo ""
fi

# ═════════════════════════════════════════════════════════
# 总结
# ═════════════════════════════════════════════════════════
if [[ $errors -eq 0 ]]; then
  ok "全部自查通过,可以 push"
  exit 0
else
  echo ""
  fail "自查发现 $errors 个问题,请修复后再 push"
  warn "应急绕过:SKIP_SDK_CHECKS=1 git push(仅限明确知道问题但需要紧急 push 时)"
  exit 1
fi
