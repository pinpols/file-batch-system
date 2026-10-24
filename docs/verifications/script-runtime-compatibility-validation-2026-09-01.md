# 脚本运行环境兼容性验证 — 2026-09-01

> 从原兼容矩阵中分离的历史执行记录；本次目录整理未重新运行这些实测。当前支持范围见 [脚本运行环境兼容矩阵](../runbook/script-runtime-compatibility-matrix.md)。

## 实测记录

2026-09-01 在本地 PostgreSQL `localhost:15432/batch_platform` 上使用临时 Python 虚拟环境完成：

- `BATCH_PG_CLIENT_MODE=python` 强制走 `psycopg` fallback。
- `-c` 查询和 `-v` 变量替换成功。
- `-f scripts/ops/sql/common-connectivity.sql` 成功执行。
- Shell 全量 `bash -n` 检查通过。
- 缺少 `psycopg` 时返回 `127` 并给出安装提示。

因此“无宿主机 `psql`”已具备可运行路径，但前提是按
`scripts/requirements-postgres.txt` 安装 Python 驱动；这不是免客户端执行 SQL。

