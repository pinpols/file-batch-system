# Shell 公共库

这里放本仓库脚本共享的 shell helper。

- `env-common.sh`：加载 `.env` / `.env.local` 并导出公共环境变量。
- `logging.sh`：日志目录、当前日志软链和 PID 文件辅助函数。
- `process.sh`：进程探测、停止和 PID 文件辅助函数。
- `sdk-e2e-common.sh`：SDK E2E 脚本共享函数。

这些文件供其他脚本 `source`，不建议直接执行。
