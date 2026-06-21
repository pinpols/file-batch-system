#!/usr/bin/env bash
# 守护:scripts/db/** 手工 DDL 脚本的危险写法 lint(squawk 守 db/migration 的盲区补全)。
#
# 背景:check-migration-safety.sh 只 squawk 扫 Flyway 的 db/migration/*.sql。但 batch_business
# 库不走 Flyway,partition-migration / business 下的可执行脚本(改 UNIQUE/PK 列集、DROP+重建表)
# 同样能对 prod 跑危险 DDL,却完全不在任何 CI 守护内。CLAUDE.md 红线:**任何改 UNIQUE 列集的
# 动作(分区/分片/重建表/迁移)都是语义变更而非运维操作**(56 处 ON CONFLICT 把幂等承重在全局
# UNIQUE 上)。2026-06-10 分区脚本实跑致 orchestrator outbox 全写失败回滚就是这类盲区被命中。
#
# 策略(两档):
#   ① WARN(永远只提示,不 fail):脚本里出现 `on conflict` —— 提醒核对幂等契约是否被改约束影响。
#   ② FAIL(危险 DDL 缺禁令标记):脚本含「改 UNIQUE/PK 列集 / DROP TABLE / DROP CONSTRAINT」
#      这类关键约束级变更,**且**文件头部 N 行内没有「禁令/危险标记」(🔴 / DANGER / 禁止执行 /
#      执行前必须 / DESTRUCTIVE 等)→ fail。要求:危险手工脚本必须在头部显式声明风险 + 前置条件,
#      不能裸放任人误跑(对齐现有 partition-migration/01 的头注释范式)。
#
# 这不是要拦住危险脚本被写出来(它们有正当运维用途),而是逼「危险脚本头部必带醒目禁令标记」
# 这条已存在但无机器守护的约定落地。新增危险脚本忘了写标记 → CI 红。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# 扫描范围:scripts/db 下所有 .sql(含 business / partition-migration / cleanup 等)。
# 排除 test-seed / load-test-seed(纯造数,不动 schema 约束)。
mapfile -t files < <(
  find scripts/db -name '*.sql' -type f \
    -not -path '*/test-seed/*' \
    -not -path '*/load-test-seed/*' \
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

# 关键约束级危险 DDL:改 UNIQUE/PK 列集、DROP 表 / 约束。
# (DROP COLUMN / ALTER TYPE 也危险,但那类主要走 Flyway,已由 squawk 覆盖;这里聚焦
#  手工脚本最易绕过守护、且 CLAUDE.md 点名的 UNIQUE/PK + DROP TABLE。)
DANGEROUS_DDL_RE='(DROP[[:space:]]+TABLE|DROP[[:space:]]+CONSTRAINT|ADD[[:space:]]+CONSTRAINT.*(UNIQUE|PRIMARY[[:space:]]+KEY)|CREATE[[:space:]]+UNIQUE[[:space:]]+INDEX|ALTER[[:space:]]+TABLE.*(ADD|DROP).*(UNIQUE|PRIMARY[[:space:]]+KEY))'

warn=0
fail=0

echo "ℹ️  扫描 ${#files[@]} 个 scripts/db/*.sql(排除 *-seed)"
echo

for f in "${files[@]}"; do
  # ① on conflict → 永远 WARN(幂等契约提醒)。
  if grep -qiE 'on[[:space:]]+conflict' "$f"; then
    echo "⚠️  [WARN] $f 含 ON CONFLICT —— 若同 PR 改了相关表 UNIQUE/PK 列集,必须核对幂等语义(CLAUDE.md 红线)。"
    warn=$((warn+1))
  fi

  # ② 关键约束级危险 DDL → 要求头部有禁令标记,否则 FAIL。
  if grep -qiE "$DANGEROUS_DDL_RE" "$f"; then
    if head -n "$HEADER_LINES" "$f" | grep -qiE "$DANGER_MARKER_RE"; then
      echo "✅ [OK]   $f 含危险 DDL(改约束/DROP),头部已有禁令标记。"
    else
      echo "❌ [FAIL] $f 含危险 DDL(改 UNIQUE/PK 列集 / DROP TABLE / DROP CONSTRAINT),"
      echo "         但文件前 ${HEADER_LINES} 行没有禁令/危险标记(🔴/⚠/DANGER/禁止执行/执行前必须/破坏性…)。"
      echo "         → 请在头注释里显式声明:风险、前置条件、对 ON CONFLICT 幂等契约的影响"
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
echo "✅ scripts/db 危险 DDL 脚本均带头部禁令标记(WARN 仅提示,不阻断)。"
