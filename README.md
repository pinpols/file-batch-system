# File Batch System

分布式批量调度与执行平台，面向金融、结算、数据传输等高可靠批处理场景。

## 项目概述

File Batch System 是一个多模块 Spring Boot 应用，实现了从文件接收、解析、校验、加载到分发的完整批处理链路，核心架构遵循 `DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT` 主链。

### 核心特性

- **多租户隔离**：所有数据按 `tenant_id` 隔离，支持差异化 SLA 和配额策略
- **Outbox 保证投递**：任务状态写入与消息发布在同一事务内，消除消息丢失风险
- **DAG 工作流编排**：支持多节点有向无环图调度，含条件分支与并行执行
- **资源配额管理**：Fair-share 调度、突发借用（Burst）、滑动窗口重置
- **Worker 优雅排空**：ONLINE → DRAINING → DECOMMISSIONED 生命周期管理
- **补偿与重试**：内置重试策略（FIXED / EXPONENTIAL / NONE）和审批补偿链路
- **文件错误追踪**：逐行记录解析/校验/加载失败，支持跳过与审计

## 模块结构

| 模块 | 端口 | 职责 |
|------|------|------|
| `batch-common` | — | 公共枚举、DTO、Kafka 消息定义、测试基础设施 |
| `batch-trigger` | 8081 | Quartz 定时调度、手动触发、Misfire 处理 |
| `batch-orchestrator` | 8082 | 唯一状态主机，负责 DAG 编排、分片、路由、Outbox |
| `batch-worker-core` | — | Worker 注册、心跳、执行适配器基座 |
| `batch-worker-import` | 8083 | 导入链路：RECEIVE → PREPROCESS → PARSE → VALIDATE → LOAD → FEEDBACK |
| `batch-worker-export` | 8084 | 导出链路：PREPARE → GENERATE → STORE → REGISTER → COMPLETE |
| `batch-worker-dispatch` | 8085 | 分发链路：PREPARE → DISPATCH → ACK → RETRY/COMPENSATE → COMPLETE |
| `batch-console-api` | 8080 | 控制台 REST API、审计、AI 辅助 |
| `batch-e2e-tests` | — | 端到端集成测试（内嵌 Orchestrator + Worker） |

## 技术栈

| 层次 | 选型 |
|------|------|
| 运行时 | JDK 25, Spring Boot 4.0.3 |
| 消息队列 | Apache Kafka 4.x |
| 数据库 | PostgreSQL 16（JSONB、TIMESTAMPTZ） |
| 对象存储 | MinIO（兼容 S3 协议） |
| 调度器 | Quartz JDBC |
| 数据迁移 | Flyway |
| ORM | MyBatis（运行态）+ Spring Data JDBC（配置态） |

## 快速开始

### 环境要求

- JDK 25
- Docker（用于本地基础设施）
- Maven 3.9+

### 启动本地基础设施

```bash
docker compose -f docker/docker-compose.yml up -d
```

本地服务端口：

| 服务 | 地址 |
|------|------|
| PostgreSQL | `localhost:15432`（用户 `batch_user`，密码 `batch_pass_123`） |
| Kafka | `localhost:9092` |
| MinIO API | `http://localhost:9000`（Bucket: `batch-dev`） |
| MinIO Console | `http://localhost:9001` |

### 编译

```bash
mvn -q compile
```

### 运行测试

```bash
# 单元测试
mvn test -pl batch-common,batch-orchestrator -Dgroups=\!e2e

# 集成测试（需要 Docker）
mvn verify -pl batch-orchestrator

# 端到端测试
mvn verify -pl batch-e2e-tests -Dgroups=e2e
```

### 系统测试种子数据

```bash
scripts/local/load-system-test-data.sh
```

详见 [docs/sql/system-test/README.md](docs/sql/system-test/README.md)。

## 架构约束

### 任务主链

```
DB (job_task: READY)
  → Outbox (outbox_event: NEW)
  → Kafka (task-dispatch-{import|export|dispatch})
  → Worker CLAIM (job_task: RUNNING)
  → Worker EXECUTE
  → Worker REPORT (job_task: SUCCESS/FAILED)
  → Orchestrator 汇总 (job_instance 状态推进)
```

**关键约束：**
- Orchestrator 是唯一状态主机，Worker 不得直接写入 `job_instance` / `workflow_run` / `workflow_node_run`
- `outbox_event` 必须与任务状态写入在同一事务
- Worker 执行前必须先 CLAIM，不得绕过
- Kafka 仅负责异步驱动，数据库是业务状态事实来源

### 持久层规则

- **Spring Data JDBC**：配置态、定义态静态数据（实体类用 `*Record` 后缀）
- **MyBatis**：运行态、实例态、状态推进、复杂查询（实体类用 `*Entity` 后缀）
- 同一写路径禁止混用 Repository 和 Mapper

### 数据库边界

| 数据库 | Schema | 用途 |
|--------|--------|------|
| `batch_platform` | `batch`, `quartz` | 平台元数据、运行态、编排态 |
| `batch_business` | `biz` | 业务导入/导出目标表 |

## 测试策略

测试分三层推进，详见 [docs/testing/test-strategy.md](docs/testing/test-strategy.md)：

| 层次 | 框架 | 范围 |
|------|------|------|
| 单元测试 | JUnit 5 + Mockito | 领域逻辑、状态机、路由策略 |
| 集成测试 | Spring Boot Test + Testcontainers | Mapper、Repository、Service 与真实 DB/Kafka |
| 端到端测试 | Awaitility + 内嵌 App | 完整 Kafka 主链（IMPORT/EXPORT/DISPATCH） |

集成测试和端到端测试使用 Testcontainers 自动启动 PostgreSQL 16 和 Apache Kafka 4.1.2，无需预先安装本地服务。

## 文档索引

| 文档 | 说明 |
|------|------|
| [设计说明书](批量调度系统设计说明书（完整版）-20260321.md) | 系统完整设计，含数据模型、流程、接口定义 |
| [AGENT.md](AGENT.md) | 工程基线约束，供 AI 辅助开发时参考 |
| [本地开发](docs/local-development.md) | 环境搭建、调试、常见问题 |
| [Docker 部署](docs/deployment/docker-deployment.md) | 容器化部署指南 |
| [运行时通信](docs/architecture/runtime-module-communication.md) | 模块间消息协议与接口规范 |
| [设计差距审计](docs/architecture/design-gap-audit.md) | 当前实现与设计文档的差距分析 |
| [默认运行参数](docs/architecture/runtime-default-parameters.md) | 调度器、Worker、Outbox 等默认参数说明 |
| [Flyway 迁移](docs/sql/flyway/README.md) | 数据库迁移脚本说明 |

## 贡献指南

1. 遵守 `AGENT.md` 中的工程基线约束
2. 新功能必须附带对应的集成测试
3. 修改持久层时同步更新 `platform-init.sql` 和相应 Flyway 迁移
4. 不得引入 JPA/Hibernate 依赖
