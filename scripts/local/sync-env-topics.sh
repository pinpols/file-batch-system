#!/usr/bin/env bash
# sync-env-topics.sh —— 把 .env.example 的 KAFKA_TOPICS 同步到本地 gitignored 的 .env.prod / .env.local。
#
# 背景:.env.prod / .env.local 是 gitignored 的本地文件,跨分支共享;而 .env.example(committed)
# 跟着分支走。切到加了新 topic 的分支后,本地两文件会 drift,导致 validate-kafka-topics.sh 报
# "三文件不一致 / topic 缺失"。本脚本一键把它们对齐到当前分支的 .env.example。
#
# 用法:切分支后跑一次
#   bash scripts/local/sync-env-topics.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/.env.example"

if [[ ! -f "$SRC" ]]; then
  echo "❌ 找不到 $SRC" >&2
  exit 1
fi

LINE="$(grep '^KAFKA_TOPICS=' "$SRC" || true)"
if [[ -z "$LINE" ]]; then
  echo "❌ .env.example 里没有 KAFKA_TOPICS= 行" >&2
  exit 1
fi

synced=0
for f in "$ROOT/.env.prod" "$ROOT/.env.local"; do
  [[ -f "$f" ]] || { echo "⏭  跳过(不存在):$(basename "$f")"; continue; }
  # 用 python 替换,避免 sed 的 BSD/GNU 差异 + 特殊字符转义问题
  python3 - "$f" "$LINE" <<'PY'
import sys
path, line = sys.argv[1], sys.argv[2]
lines = open(path, encoding="utf-8").read().splitlines()
if not any(l.startswith("KAFKA_TOPICS=") for l in lines):
    lines.append(line)
else:
    lines = [line if l.startswith("KAFKA_TOPICS=") else l for l in lines]
open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
  echo "✅ 已同步:$(basename "$f")"
  synced=$((synced + 1))
done

echo
echo "同步 $synced 个文件到当前分支的 KAFKA_TOPICS($(echo "$LINE" | tr ',' '\n' | grep -c 'batch\.') topics)。"
echo "可跑 bash scripts/ci/validate-kafka-topics.sh 验证一致。"
