#!/usr/bin/env bash
# scripts/ci/check-cron-quartz-only.sh
#
# QZ-pre-2: 静态扫描 seed / migration SQL 文件,识别 Quartz 专属 cron 表达式(L / W / #),
# 在 CI 阶段防止 wheel 切换后无法解析的表达式被引入。
#
# 这是 scripts/db/quartz-replacement-preflight-scan.sql(PG 端运行时检查)的另一道 CI 防线:
# 该 SQL 跑在 staging / prod 已写入数据库数据上,本脚本扫源代码里准备写入数据库的 seed,提前 fail。
#
# Quartz 扩展字符:
#   L = "last"          (e.g. "0 0 0 L * ?" — 每月最后一天)
#   W = "weekday near"  (e.g. "0 0 0 15W * ?" — 距离 15 号最近的工作日)
#   # = "Nth weekday"   (e.g. "0 0 0 ? * 6#3" — 每月第 3 个星期五)
#
# Wheel 默认走 Quartz CronExpression 解析仍兼容这些字符,但要彻底切换到通用 cron 实现
# (如 spring-context CronExpression 或 cron-utils Quartz dialect)前必须扫干净。
#
# 用法:
#   ./scripts/ci/check-cron-quartz-only.sh                          # 默认扫所有 SQL
#   ./scripts/ci/check-cron-quartz-only.sh path1.sql path2.sql ...  # 只扫指定文件
#   BATCH_CI_SKIP_CRON_GATE=1 ./scripts/ci/check-cron-quartz-only.sh # 应急逃生开关

set -euo pipefail

if [[ "${BATCH_CI_SKIP_CRON_GATE:-0}" == "1" ]]; then
  echo "[cron-gate] BATCH_CI_SKIP_CRON_GATE=1 — 跳过(仅 dev 本地 debug 应使用)"
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# 扫描目标
if [[ $# -gt 0 ]]; then
  TARGETS=("$@")
else
  # 默认:所有 db migration + 所有 test-seed SQL(macOS bash 3.2 兼容,不用 mapfile)
  TARGETS=()
  while IFS= read -r line; do TARGETS+=("$line"); done < <(find db/migration scripts/db -name '*.sql' -not -path '*/target/*' 2>/dev/null)
fi

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "[cron-gate] 无扫描目标"
  exit 0
fi

# 提取 schedule_expr 字面量(单/双引号包裹)中的 cron 行
# 关键字符识别:仅在 cron 表达式上下文(schedule_expr / cron_expr 之类列名邻近)
VIOLATIONS=()
for f in "${TARGETS[@]}"; do
  [[ -f "$f" ]] || continue
  # grep 行号 + 内容,只保留 schedule_expr / cron 出现的同行 (减少误报,如 SQL 注释里的 'last')
  while IFS=: read -r lineno line; do
    # 提取该行所有引号里的 cron 字符串(简单正则)
    expressions=$(echo "$line" | grep -oE "'[^']*'" || true)
    for expr in $expressions; do
      # 跳过明显非 cron(纯字母 / 长度 < 9)
      [[ ${#expr} -lt 9 ]] && continue
      if [[ "$expr" == *L* || "$expr" == *W* || "$expr" == *"#"* ]]; then
        # 排除常见误识别:'COMPLETE'/'COMPLETED'/'WAITING'/'WORKING' 等含 L/W 但非 cron
        if echo "$expr" | grep -qiE '(complete|waiting|working|window|launch|laun|cancel|fail)' ; then
          continue
        fi
        # cron 6/7 字段大致结构:含至少 5 个空格分隔符
        spaces=$(echo "$expr" | tr -cd ' ' | wc -c | tr -d ' ')
        if [[ "$spaces" -lt 5 ]]; then
          continue
        fi
        VIOLATIONS+=("$f:$lineno: $expr")
      fi
    done
  done < <(grep -nE 'schedule_expr|cron_expr' "$f" 2>/dev/null || true)
done

if [[ ${#VIOLATIONS[@]} -gt 0 ]]; then
  echo
  echo "[cron-gate] FAIL: 检测到 Quartz 专属 cron 表达式(L / W / #) ${#VIOLATIONS[@]} 处:"
  echo
  for v in "${VIOLATIONS[@]}"; do
    echo "  $v"
  done
  echo
  echo "[cron-gate] 处理建议:"
  echo "  - 改用通用 cron 表达式(spring CronExpression 兼容):"
  echo "    L 'last day of month'    → 用月末日期分组,或在业务侧判断(getDate() == lastDayOfMonth)"
  echo "    W 'nearest weekday'      → 拆成多条 cron 或在调度器加 calendar 约束"
  echo "    '#3' 'Nth weekday'       → 同上,或借助 business_calendar 预生成 fire 时刻"
  echo "  - 详见 docs/architecture/quartz-replacement-design.md §15 兼容性策略"
  exit 1
fi

echo "[cron-gate] PASS: 无 Quartz 专属 cron 表达式(扫描了 ${#TARGETS[@]} 个 SQL 文件)"
