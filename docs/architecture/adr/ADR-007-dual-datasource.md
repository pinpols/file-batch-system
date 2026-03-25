# ADR-007: 单 PostgreSQL 实例双 Schema 隔离（batch / batch_worker）

- **状态**: 已采纳
- **日期**: 2026-03-25
- **决策人**: 后端平台团队

## 背景

平台由两个独立部署单元组成：
- **Orchestrator**（`batch-orchestrator`）：管理作业生命周期，持有 `batch` schema 的写权限。
- **Worker**（各 `batch-worker-*` 模块）：执行具体任务，只需读写 `job_task` 和 `job_execution_log`。

初期设计选项：
1. **单数据库单 Schema**：最简单，但 Orchestrator 和 Worker 的表混在一起，权限边界模糊。
2. **两个独立数据库实例**：强隔离，但运维成本高（两套备份、两套连接池），开发环境配置复杂。
3. **单数据库双 Schema**（`batch` + `batch_worker`）：逻辑隔离，单实例运维，通过数据库用户权限控制访问边界。

## 决策

**采用方案 3**：单 PostgreSQL 实例，两个 schema：

| Schema | 所有者 | 主要表 | 访问方 |
|---|---|---|---|
| `batch` | `batch_app` | `job_instance`, `job_partition`, `job_task`, `outbox_event`, `workflow_*`, `job_definition` 等 | Orchestrator（读写） |
| `batch_worker` | `batch_worker_app` | `worker_registration`, `worker_heartbeat` | Worker（读写），Orchestrator（只读） |

数据源配置：

```yaml
# Orchestrator application.yml
spring.datasource.url: jdbc:postgresql://localhost:5432/batchdb?currentSchema=batch
spring.datasource.username: batch_app

# Worker application.yml
spring.datasource.url: jdbc:postgresql://localhost:5432/batchdb?currentSchema=batch_worker
spring.datasource.username: batch_worker_app
```

Worker 需要读取 `batch.job_task` 时，通过显式 schema 前缀或 `search_path` 设置跨 schema 查询，`batch_worker_app` 用户对 `batch.job_task` 有 SELECT/UPDATE 权限（通过 GRANT）。

Flyway 迁移：
- `batch-orchestrator` 管理 `batch` schema 的迁移（V1–V26）。
- `batch-worker-core` 管理 `batch_worker` schema 的迁移（W1–W5）。

## 后果

**正面**：
- 单实例运维简单，本地开发只需一个 PostgreSQL 容器。
- Schema 级权限隔离，Orchestrator 无法误写 `batch_worker` 表。
- 两个 Flyway 迁移路径独立演化，互不干扰。

**负面**：
- 跨 schema 查询需要显式前缀，SQL 可读性略降。
- Worker 应用的 `search_path` 配置错误可能导致查询到错误 schema 的表（同名表场景）；通过统一 `currentSchema` 参数规避。
- 未来如需微服务化（独立数据库），需要迁移工作量；当前阶段不考虑。

## 系统测试配置

`docs/sql/system-test/platform_seed.sql` 包含测试环境的 schema 初始化脚本，创建两个 schema 及对应用户权限。
