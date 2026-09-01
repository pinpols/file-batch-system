# 测试与运维脚本运行环境兼容矩阵

## 结论

脚本统一通过 `scripts/lib/env-common.sh` 读取环境变量。PostgreSQL 命令统一使用公共 `psql` 入口：

- 默认 `BATCH_PG_CLIENT_MODE=auto`：优先使用宿主机 `psql`。
- `BATCH_PG_CLIENT_MODE=host`：只使用宿主机客户端；缺失时立即失败。
- `BATCH_PG_CLIENT_MODE=docker`：使用 `PG_CONTAINER` 内的 `psql`，不依赖宿主机安装 PostgreSQL 客户端。
- 也可以使用 `BATCH_PSQL_BIN=/path/to/psql` 指定宿主机客户端。

宿主机和 Docker 均没有可用 PostgreSQL 客户端时，依赖 SQL 的脚本必须失败并提示环境问题，不能跳过 SQL 后继续报告成功。当前不引入 Python PostgreSQL 驱动或新的运行时依赖。

## 兼容矩阵

| 场景 | Linux + Docker | Linux 非 Docker | macOS + Docker | macOS 非 Docker | 无宿主机 `psql` |
|---|---:|---:|---:|---:|---:|
| Shell 语法检查 | 支持 | 支持 | 支持 | 支持 | 支持 |
| 单元测试 | 支持 | 支持 | 支持 | 支持 | 支持 |
| Testcontainers IT/E2E | 支持 | 不支持，明确要求 Docker | 支持 | 不支持，明确要求 Docker | 与 Docker 状态无关 |
| 数据初始化 | 支持 | 支持 | 支持 | 支持 | 支持 Docker 客户端模式 |
| Load Test | 支持 | 支持宿主机客户端 | 支持 | 支持宿主机客户端 | 支持 Docker 客户端模式 |
| 运维巡检/自愈 | 支持 | 支持远程 PG | 支持 | 支持远程 PG | 支持 Docker 客户端模式 |
| Sim 全链路 | 支持 | Docker-only，明确拒绝 | 支持 | Docker-only，明确拒绝 | 依赖 Docker 内客户端 |
| Kafka/MinIO 直连初始化 | 支持 | 支持 Kafka CLI/`mc` | 支持 | 支持 Kafka CLI/`mc` | 不涉及 PostgreSQL 客户端 |

## 边界说明

1. Sim、Testcontainers、容灾故障注入需要容器编排能力，不伪装成纯宿主机流程。
2. 非 Docker 只表示脚本可以连接宿主机或远程依赖，不表示脚本可以绕过 PostgreSQL 客户端。
3. SQL 文件本身不是可执行程序；执行 SQL 必须依赖 `psql`、容器内 `psql` 或后续明确引入的数据库驱动。
4. `scripts/ops` 和 `load-tests` 使用公共客户端入口；已有直接 `docker exec ... psql` 的 Sim 专用步骤保持 Docker-only，避免改变故障注入语义。

## 验证要求

- 所有 Shell 脚本使用 `bash -n` 检查。
- host 模式验证 `BATCH_PSQL_BIN` 和 `PGHOST/PGPORT/...` 连接路径。
- Docker 模式验证 `PG_CONTAINER` 内客户端路径。
- 无客户端场景验证返回非零状态和明确错误信息。
- Testcontainers 测试继续由 CI/Docker 环境执行；无 Docker 时按测试类既有配置跳过或明确失败。
