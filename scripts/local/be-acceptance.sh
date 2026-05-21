#!/usr/bin/env bash
# =========================================================
# be-acceptance.sh
#
# BE 全链路验收 entry — 与 ~/.claude/skills/be-acceptance 同步。
# 支持选定步骤跑(--steps=1,3,5)或从某步续跑(--from-step=3)。
#
# 用法:
#   bash scripts/local/be-acceptance.sh                  # 全 10 步
#   bash scripts/local/be-acceptance.sh --from-step=5    # 从 5 开始(跳过 build / 测试)
#   bash scripts/local/be-acceptance.sh --steps=5,6,10   # 只跑选定
#   bash scripts/local/be-acceptance.sh --skip=3,4       # 全跑但跳过 IT 和 E2E
#   bash scripts/local/be-acceptance.sh --list           # 列出所有 step
#   bash scripts/local/be-acceptance.sh --parallel       # 单测/IT/E2E 并发跑(总耗时 25-35 min)
#   bash scripts/local/be-acceptance.sh --resume         # 续跑上次失败处(读 .be-acceptance-state)
#   bash scripts/local/be-acceptance.sh --auto-issue     # Step 10 自动调 gh issue create
#
# 步骤定义:
#   0  前置条件检查(docker / 端口 / 磁盘)
#   1  build + 重启 BE + 3 min 日志
#   2  单测
#   3  集成测试
#   4  E2E
#   5  strict-verify.sh
#   6  近 3 天违约扫描
#   7  FE rebuild + preview
#   8  验收汇总
#   9  恢复 mvnd
#   10 backlog 自动归档
# =========================================================

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

FE_DIR="${FE_DIR:-/Users/dengchao/Downloads/batch-console}"
CONSOLE_PORT="${CONSOLE_PORT:-18080}"
FE_PORT="${FE_PORT:-5173}"
LOG_DIR="$ROOT_DIR/logs/be-acceptance"
mkdir -p "$LOG_DIR" "$ROOT_DIR/docs/backlog"

GREEN='\033[32m' RED='\033[31m' YELLOW='\033[33m' BLUE='\033[34m' DIM='\033[2m' RST='\033[0m'
RUN_STEPS=()   # 实际要跑的步骤号(1-based)
SKIP_STEPS=()
FROM_STEP=0
PARALLEL=0
RESUME=0
AUTO_ISSUE=0
STATE_FILE="$ROOT_DIR/.be-acceptance-state"

step_name() {
  case "$1" in
    0)  echo "前置条件检查" ;;
    1)  echo "build + 重启 BE" ;;
    2)  echo "跑单元测试" ;;
    3)  echo "跑集成测试" ;;
    4)  echo "跑 E2E" ;;
    5)  echo "strict-verify" ;;
    6)  echo "近 3 天违约扫描" ;;
    7)  echo "FE 重启" ;;
    8)  echo "验收汇总" ;;
    9)  echo "mvnd 恢复" ;;
    10) echo "backlog 归档" ;;
    *)  echo "?" ;;
  esac
}
ALL_STEPS=(0 1 2 3 4 5 6 7 8 9 10)

# ── 参数解析 ────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from-step=*)
      FROM_STEP="${1#*=}"
      ;;
    --steps=*)
      IFS=',' read -ra RUN_STEPS <<< "${1#*=}"
      ;;
    --skip=*)
      IFS=',' read -ra SKIP_STEPS <<< "${1#*=}"
      ;;
    --parallel)
      PARALLEL=1
      ;;
    --resume)
      RESUME=1
      ;;
    --auto-issue)
      AUTO_ISSUE=1
      ;;
    --auto-issue-dry-run)
      AUTO_ISSUE=2  # 2 = dry-run,只打印命令不真执行
      ;;
    --list)
      printf "${BLUE}可用步骤:${RST}\n"
      for n in "${ALL_STEPS[@]}"; do
        printf "  %2d  %s\n" "$n" "$(step_name $n)"
      done
      exit 0
      ;;
    --help|-h)
      head -30 "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *)
      echo "未知参数: $1(--help)";
      exit 2
      ;;
  esac
  shift
done

# --resume 优先:读 state 文件,从上次失败 step 续跑
if (( RESUME == 1 )); then
  if [[ -f "$STATE_FILE" ]]; then
    FROM_STEP=$(grep -E "^last_failed=" "$STATE_FILE" | tail -1 | cut -d= -f2)
    if [[ -z "$FROM_STEP" || "$FROM_STEP" == "0" ]]; then
      printf "${YELLOW}--resume:state 无失败记录,跑完整流程${RST}\n"
      FROM_STEP=0
    else
      printf "${YELLOW}--resume:从 step %d 续跑(上次失败位置)${RST}\n" "$FROM_STEP"
    fi
  else
    printf "${YELLOW}--resume:无 state 文件,跑完整流程${RST}\n"
  fi
fi

# 决定本轮跑哪些
if (( ${#RUN_STEPS[@]} == 0 )); then
  RUN_STEPS=("${ALL_STEPS[@]}")
fi
if (( FROM_STEP > 0 )); then
  RUN_STEPS=()
  for n in "${ALL_STEPS[@]}"; do
    [[ "$n" -ge "$FROM_STEP" ]] && RUN_STEPS+=("$n")
  done
fi
# 减去 skip
if (( ${#SKIP_STEPS[@]} > 0 )); then
  TMP=()
  for n in "${RUN_STEPS[@]}"; do
    skipit=0
    for s in "${SKIP_STEPS[@]}"; do
      [[ "$n" == "$s" ]] && skipit=1 && break
    done
    [[ "$skipit" == "0" ]] && TMP+=("$n")
  done
  RUN_STEPS=("${TMP[@]}")
fi

# ── 工具函数 ────────────────────────────────────────────────
SEQ_PASS=0 SEQ_FAIL=0
hdr() { printf "\n${BLUE}═════ Step %d / %s ═════${RST}\n" "$1" "$2"; }
ok()  { printf "${GREEN}✅${RST} %s\n" "$1"; SEQ_PASS=$((SEQ_PASS+1)); }
ng()  { printf "${RED}❌${RST} %s\n" "$1"; SEQ_FAIL=$((SEQ_FAIL+1)); }
note(){ printf "${DIM}   %s${RST}\n" "$1"; }

should_run() {
  for n in "${RUN_STEPS[@]}"; do
    [[ "$n" == "$1" ]] && return 0
  done
  return 1
}

# ── Step 实现 ───────────────────────────────────────────────
step_0_precheck() {
  hdr 0 "$(step_name 0)"
  local docker_cnt=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E "batch-postgres|batch-kafka|batch-redis|batch-minio" | wc -l | tr -d ' ')
  [[ "$docker_cnt" -ge 4 ]] && ok "docker 容器 $docker_cnt 个运行" || ng "docker 容器 $docker_cnt < 4(可能影响 IT/E2E)"
  local free_gb=$(df -g "$ROOT_DIR" 2>/dev/null | tail -1 | awk '{print $4}')
  [[ -z "$free_gb" ]] && free_gb="?"
  [[ "$free_gb" != "?" && "$free_gb" -ge 3 ]] && ok "磁盘空间 ${free_gb}GB" || ng "磁盘 ${free_gb}GB < 3,可能不够"
  pgrep -fl cloudflared >/dev/null 2>&1 && ok "tunnel 运行中" || note "tunnel 未启动(隧道验证段跳过)"
}

step_1_build_restart() {
  hdr 1 "$(step_name 1)"
  # 临时禁用 mvnd(避免 fork crash)
  if [[ -x ~/.local/bin/mvnd ]]; then
    mv ~/.local/bin/mvnd ~/.local/bin/mvnd.bak
    note "mvnd 临时禁用(Step 9 会恢复)"
  fi
  mvn package -DskipTests -pl batch-console-api -am -q > "$LOG_DIR/step1-mvn.log" 2>&1
  local jar=$(find batch-console-api/target -name "*-exec.jar" -mmin -10 | head -1)
  if [[ -z "$jar" ]]; then
    ng "未找到新 build 的 jar(看 $LOG_DIR/step1-mvn.log)"
    return 1
  fi
  cp "$jar" build/runtime-jars/console.jar
  bash scripts/local/restart.sh console > "$LOG_DIR/step1-restart.log" 2>&1
  until curl -sf "http://localhost:$CONSOLE_PORT/actuator/health" -o /dev/null; do sleep 2; done
  ok "BE UP"
  note "等 3 min 看日志稳态..."
  sleep 180
  local err=$(grep -E "ERROR|FATAL" logs/app/console.log 2>/dev/null | grep -v "SwallowedExceptionLogger\|catch:" | wc -l | tr -d ' ')
  [[ "$err" == "0" ]] && ok "3 min 日志 0 ERROR" || ng "$err 条 ERROR(看 logs/app/console.log)"
}

step_2_unit() {
  hdr 2 "$(step_name 2)"
  bash scripts/local/run-tests.sh --unit --skip-build > "$LOG_DIR/step2-unit.log" 2>&1
  local p=$(grep -oE "PASSED: [0-9]+" "$LOG_DIR/step2-unit.log" | tail -1)
  local f=$(grep -oE "FAILED: [0-9]+" "$LOG_DIR/step2-unit.log" | tail -1)
  [[ "$f" == "FAILED: 0" ]] && ok "$p / $f" || ng "$p / $f(看 logs/test/test-unit-failed.log)"
}

step_3_it() {
  hdr 3 "$(step_name 3)"
  bash scripts/local/run-tests.sh --it --skip-build > "$LOG_DIR/step3-it.log" 2>&1
  local p=$(grep -oE "PASSED: [0-9]+" "$LOG_DIR/step3-it.log" | tail -1)
  local f=$(grep -oE "FAILED: [0-9]+" "$LOG_DIR/step3-it.log" | tail -1)
  [[ "$f" == "FAILED: 0" ]] && ok "$p / $f" || ng "$p / $f(看 logs/test/test-integration-failed.log)"
}

step_4_e2e() {
  hdr 4 "$(step_name 4)"
  bash scripts/local/run-tests.sh --e2e --skip-build > "$LOG_DIR/step4-e2e.log" 2>&1
  local p=$(grep -oE "PASSED: [0-9]+" "$LOG_DIR/step4-e2e.log" | tail -1)
  local f=$(grep -oE "FAILED: [0-9]+" "$LOG_DIR/step4-e2e.log" | tail -1)
  [[ "$f" == "FAILED: 0" ]] && ok "$p / $f" || ng "$p / $f(看 logs/test/test-e2e-failed.log)"
}

step_5_strict() {
  hdr 5 "$(step_name 5)"
  bash scripts/local/strict-verify.sh > "$LOG_DIR/step5-strict.log" 2>&1
  local rc=$?
  local last=$(tail -1 "$LOG_DIR/step5-strict.log")
  [[ "$rc" == "0" ]] && ok "$last" || ng "$last(看 $LOG_DIR/step5-strict.log)"
}

step_6_scan() {
  hdr 6 "$(step_name 6)"
  # 简单扫(只看 .java,避免 docs/CLAUDE.md 引用规则文本误报)
  local fqn=$(git log --since='3 days ago' -p -- '*.java' 2>/dev/null \
    | grep '^+' | grep -v '^+++' | grep -v "import\|@link\|//\|^\\+ *\\*" \
    | grep -cE 'com\.example\.batch\.[a-z][a-z0-9_]+(\.[a-z][a-z0-9_]+)+\.[A-Z]' || true)
  local jpa=$(git log --since='3 days ago' -p -- '*.java' 2>/dev/null \
    | grep '^+' | grep -v '^+++' \
    | grep -cE 'import (javax|jakarta)\.persistence|@Entity\b' || true)
  [[ "$jpa" == "0" ]] && ok "JPA / @Entity 引入: 0" || ng "JPA 引入 $jpa 处(看 git log -p)"
  [[ "$fqn" -lt 5 ]] && ok "FQN 引入: $fqn 处(<5 容忍)" || note "FQN 引入 $fqn 处(细看 git log -p '*.java')"
}

step_7_fe() {
  hdr 7 "$(step_name 7)"
  if [[ ! -d "$FE_DIR" ]]; then
    ng "$FE_DIR 不存在"
    return 1
  fi
  cd "$FE_DIR"
  npm run build:fast > "$LOG_DIR/step7-fe-build.log" 2>&1
  lsof -i :$FE_PORT -sTCP:LISTEN 2>/dev/null | tail -n +2 | awk '{print $2}' | xargs -r kill 2>/dev/null
  sleep 1
  nohup npx vite preview > /tmp/vite-preview.log 2>&1 & disown
  sleep 3
  curl -sf "http://localhost:$FE_PORT/" -o /dev/null && ok "FE UP local :${FE_PORT}" || ng "FE 起不来"

  # 自动探测 cloudflared tunnel URL(从最近 cloudflared 进程 stdout 找 trycloudflare URL),
  # 没起 tunnel 跳过;有则探活
  local tunnel_url=""
  local cf_pid=$(pgrep -f "cloudflared.*tunnel" | head -1)
  if [[ -n "$cf_pid" ]]; then
    # 找 cloudflared 进程的 stdout log(可能在 /tmp 或用户指定)
    for log_path in /tmp/claude-501/*/tasks/*.output /tmp/cloudflared-*.log /tmp/vite-preview.log; do
      [[ -f "$log_path" ]] || continue
      tunnel_url=$(grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" "$log_path" 2>/dev/null | tail -1)
      [[ -n "$tunnel_url" ]] && break
    done
  fi
  if [[ -n "$tunnel_url" ]]; then
    local tcode=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$tunnel_url/" 2>/dev/null || echo "000")
    [[ "$tcode" == "200" ]] && ok "tunnel UP $tunnel_url" || note "tunnel $tunnel_url HTTP=$tcode"
  else
    note "tunnel 未启动(cloudflared 进程不存在,跳过)"
  fi
  cd "$ROOT_DIR"
}

step_8_summary() {
  hdr 8 "$(step_name 8)"
  local health=$(curl -s "http://localhost:$CONSOLE_PORT/actuator/health" | python3 -c "import sys,json;print(json.load(sys.stdin).get('status','?'))" 2>/dev/null)
  [[ "$health" == "UP" ]] && ok "actuator/health: $health" || ng "actuator/health: $health"
  printf "${GREEN}本轮 PASS: %d${RST}  ${RED}FAIL: %d${RST}\n" "$SEQ_PASS" "$SEQ_FAIL"
}

step_9_restore_mvnd() {
  hdr 9 "$(step_name 9)"
  if [[ -f ~/.local/bin/mvnd.bak ]]; then
    mv ~/.local/bin/mvnd.bak ~/.local/bin/mvnd
    ok "mvnd 已恢复"
  else
    note "mvnd 本来就没禁用,无需恢复"
  fi
}

step_10_backlog() {
  hdr 10 "$(step_name 10)"
  local f="docs/backlog/be-acceptance-$(date +%Y-%m-%d).md"
  if [[ -f "$f" ]]; then
    note "已存在 $f,不覆盖"
    return 0
  fi
  cat > "$f" <<EOF
# BE 验收 backlog — $(date +%Y-%m-%d)

> 由 \`scripts/local/be-acceptance.sh\` 自动生成。请补充失败明细后归档。

## 工具链 / 环境(不修主代码)
- [ ] (e.g. Mockito 5.20 + JDK 25 mock interface 失败)
- [ ] (e.g. ReadReplicaIT 缺 docker compose replica)

## 他人 commit 引入违约(交给作者)
- [ ] (e.g. WorkerLeaseProperties test FQN 6 处)

## 我自己未修完
- [ ] (本次 PR 范围外的违约/技术债)

## 性能 / 健壮性(独立 PR)
- [ ] (e.g. Compensation stale-RUNNING reconciler 兜底)

---

## 关联日志(本次跑)
$(ls -1 "$LOG_DIR" | sed 's|^|- '"$LOG_DIR"'/|')

## gh CLI dry-run(确认后自己 push)
\`\`\`bash
# 把本文件作为 issue body
gh issue create --title "BE 验收 backlog $(date +%Y-%m-%d)" --body-file "$f" --label "backlog,be-acceptance"
\`\`\`
EOF
  ok "归档 → $f"

  if (( AUTO_ISSUE >= 1 )); then
    if ! command -v gh >/dev/null 2>&1; then
      ng "gh CLI 不存在(brew install gh)"
      return 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
      ng "gh 未认证(gh auth login)"
      return 1
    fi
    local cmd="gh issue create --title \"BE 验收 backlog $(date +%Y-%m-%d)\" --body-file \"$f\" --label backlog,be-acceptance"
    if (( AUTO_ISSUE == 2 )); then
      ok "gh 已就位(--auto-issue-dry-run,不真执行)"
      note "命令: $cmd"
    else
      local url=$(eval "$cmd" 2>&1 | tail -1)
      if [[ "$url" == https://* ]]; then
        ok "issue 已创建 → $url"
      else
        ng "gh issue create 失败: $url"
      fi
    fi
  else
    note "确认后执行 gh issue create --body-file '$f' --label backlog,be-acceptance"
    note "或加 --auto-issue 自动调(--auto-issue-dry-run 打印命令不真执行)"
  fi
}

# 写 state 文件:记录上次跑到哪步 + 是否失败
write_state() {
  local last_step="$1"
  local last_status="$2"  # ok / fail
  local last_failed_step=""
  [[ "$last_status" == "fail" ]] && last_failed_step="$last_step"
  cat > "$STATE_FILE" <<EOF
# be-acceptance state — $(date -u +%Y-%m-%dT%H:%M:%SZ)
last_step=$last_step
last_status=$last_status
last_failed=$last_failed_step
EOF
}

run_step() {
  local n="$1"
  local prev_fail=$SEQ_FAIL
  case "$n" in
    0)  step_0_precheck ;;
    1)  step_1_build_restart ;;
    2)  step_2_unit ;;
    3)  step_3_it ;;
    4)  step_4_e2e ;;
    5)  step_5_strict ;;
    6)  step_6_scan ;;
    7)  step_7_fe ;;
    8)  step_8_summary ;;
    9)  step_9_restore_mvnd ;;
    10) step_10_backlog ;;
    *)  echo "未知 step: $n" ; return 2 ;;
  esac
  if (( SEQ_FAIL > prev_fail )); then
    write_state "$n" "fail"
  else
    write_state "$n" "ok"
  fi
}

# ── 执行 ────────────────────────────────────────────────────
START_TS=$(date +%s)
printf "${BLUE}═════ BE Acceptance — 跑步骤: %s%s ═════${RST}\n" "${RUN_STEPS[*]}" \
  "$( (( PARALLEL == 1 )) && echo ' (并行: 2/3/4)' )"

if (( PARALLEL == 1 )); then
  # 2/3/4(单测 / IT / E2E)并发跑;其它步骤照常串行
  for n in "${RUN_STEPS[@]}"; do
    should_run "$n" || continue
    case "$n" in
      2|3|4) ;;  # 留到并行段
      *)  run_step "$n" ;;
    esac
  done
  # 并行启 2/3/4
  PIDS=()
  for n in 2 3 4; do
    should_run "$n" || continue
    (run_step "$n" > "$LOG_DIR/step${n}-parallel.log" 2>&1) &
    PIDS+=($!)
    printf "${DIM}   并行启 step %d(pid=$!)${RST}\n" "$n"
  done
  # 等所有并行结束
  if (( ${#PIDS[@]} > 0 )); then
    for pid in "${PIDS[@]}"; do
      wait "$pid" || SEQ_FAIL=$((SEQ_FAIL+1))
    done
  fi
  # 汇报并行段输出
  for n in 2 3 4; do
    [[ -f "$LOG_DIR/step${n}-parallel.log" ]] || continue
    printf "\n${BLUE}── step %d 输出 ──${RST}\n" "$n"
    cat "$LOG_DIR/step${n}-parallel.log"
  done
else
  for n in "${RUN_STEPS[@]}"; do
    should_run "$n" || continue
    run_step "$n"
  done
fi

END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))
printf "\n${BLUE}═════ 完成 — 耗时 %d min %d s — PASS %d / FAIL %d ═════${RST}\n" \
  $((ELAPSED/60)) $((ELAPSED%60)) "$SEQ_PASS" "$SEQ_FAIL"
[[ "$SEQ_FAIL" -gt 0 ]] && {
  printf "${YELLOW}失败位置已记入 %s,下次 --resume 续跑${RST}\n" "$STATE_FILE"
  exit 1
}
exit 0
