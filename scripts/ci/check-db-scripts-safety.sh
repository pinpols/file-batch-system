#!/usr/bin/env bash
# 守护手工 SQL 中可能破坏数据库状态的操作(squawk 守 db/migration 的盲区补全)。
#
# 背景:check-migration-safety.sh 只 squawk 扫 Flyway 的 db/migration/*.sql。但 batch_business
# 库不走 Flyway,partition-migration / business 下的可执行脚本(改 UNIQUE/PK 列集、DROP+重建表)
# 同样能对 prod 跑危险 DDL,却完全不在任何 CI 守护内。docs/agent-baseline.md 红线:**任何改 UNIQUE 列集的
# 动作(分区/分片/重建表/迁移)都是语义变更而非运维操作**(56 处 ON CONFLICT 把幂等承重在全局
# UNIQUE 上)。2026-06-10 分区脚本实跑致 orchestrator outbox 全写失败回滚就是这类盲区被命中。
#
# 扫描范围覆盖 db / sim / local / load-tests 的 SQL 资产；测试夹具仍纳入检查，
# 因为误连到非测试库时，TRUNCATE 同样会造成数据损失。
# 策略(两档):
#   ① WARN(永远只提示,不 fail):脚本里出现 `on conflict` —— 提醒核对幂等契约是否被改约束影响。
#   ② FAIL(危险 SQL 缺禁令标记):脚本含 DROP / TRUNCATE / DELETE FROM 或关键约束变更
#      这类关键约束级变更,**且**文件头部 N 行内没有「禁令/危险标记」(🔴 / DANGER / 禁止执行 /
#      执行前必须 / DESTRUCTIVE 等)→ fail。要求:危险手工脚本必须在头部显式声明风险 + 前置条件,
#      不能裸放任人误跑(对齐现有 partition-migration/01 的头注释范式)。
#
# 这不是运行时授权系统；它要求每份危险 SQL 自带醒目风险声明。仿真 reset SQL
# 另有 session guard，避免脱离受保护的本地入口直接执行。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# 扫描所有手工/仿真/压测 SQL。
mapfile -t files < <(
  find scripts/db scripts/sim scripts/local/sql scripts/sim-4day load-tests/sql \
    -name '*.sql' -type f \
    | sort
)

if [[ "${#files[@]}" -eq 0 ]]; then
  echo "ℹ️  scripts/db 下无可扫 .sql,跳过"
  exit 0
fi

# 头部禁令标记(大小写不敏感任一命中即认为「已声明风险」)。
DANGER_MARKER_RE='🔴|⚠|DANGER|DESTRUCTIVE|禁止执行|执行前必须|危险|破坏性|不可逆|IRREVERSIBLE'
# 头部扫描行数(禁令标记应在文件最前面,给足注释块空间)。
HEADER_LINES=40

# 数据和结构删除/重建都必须有头部标记，包含 DROP DATABASE/SCHEMA、TRUNCATE、DELETE。
DANGEROUS_DDL_RE='(DROP[[:space:]]+(DATABASE|SCHEMA|TABLE|CONSTRAINT|INDEX|OWNED|TABLESPACE)|TRUNCATE([[:space:]]+TABLE)?|DELETE[[:space:]]+FROM|ADD[[:space:]]+CONSTRAINT.*(UNIQUE|PRIMARY[[:space:]]+KEY)|CREATE[[:space:]]+UNIQUE[[:space:]]+INDEX|ALTER[[:space:]]+TABLE.*(ADD|DROP).*(UNIQUE|PRIMARY[[:space:]]+KEY))'

warn=0
fail=0

  echo "ℹ️  扫描 ${#files[@]} 个维护/仿真/压测 SQL 文件"
echo

for f in "${files[@]}"; do
  # ① on conflict → 永远 WARN(幂等契约提醒)。
  if grep -qiE 'on[[:space:]]+conflict' "$f"; then
    echo "⚠️  [WARN] $f 含 ON CONFLICT —— 若同 PR 改了相关表 UNIQUE/PK 列集,必须核对幂等语义(docs/agent-baseline.md 红线)。"
    warn=$((warn+1))
  fi

  # ② 关键约束级危险 DDL → 要求头部有禁令标记,否则 FAIL。
  if grep -qiE "$DANGEROUS_DDL_RE" "$f"; then
    danger_line="$(awk '{ sub(/--.*/, ""); print }' "$f" | grep -inE "$DANGEROUS_DDL_RE" | head -n 1 | cut -d: -f1)"
    marker_line="$(grep -inE "$DANGER_MARKER_RE" "$f" | head -n 1 | cut -d: -f1 || true)"
    if [[ -n "$marker_line" && "$marker_line" -le "$HEADER_LINES" && "$marker_line" -lt "$danger_line" ]]; then
      echo "✅ [OK]   $f 含破坏性 SQL,头部已有风险标记。"
    else
      echo "❌ [FAIL] $f 含 DROP / TRUNCATE / DELETE 或约束变更，"
      echo "         但文件前 ${HEADER_LINES} 行没有禁令/危险标记(🔴/⚠/DANGER/禁止执行/执行前必须/破坏性…)。"
      echo "         → 请在头注释里显式声明:风险、目标环境、前置条件及恢复方式"
      echo "           (范式参考 scripts/db/partition-migration/01-outbox-event-partitioned.sql 头注释)。"
      fail=$((fail+1))
    fi
  fi
done

echo
echo "── 小结:WARN ${warn} 项,FAIL ${fail} 项 ──"
if [[ "$fail" -ne 0 ]]; then
  echo "💥 有危险手工 DDL 脚本缺头部禁令标记,见上方 [FAIL]。"
  exit 1
fi
echo "✅ 手工 SQL 破坏性操作均带头部风险标记(WARN 仅提示,不阻断)。"
