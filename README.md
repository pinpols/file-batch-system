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
| `batch-trigger` | 18081 | Quartz 定时调度、手动触发、Misfire 处理 |
| `batch-orchestrator` | 18082 | 唯一状态主机，负责 DAG 编排、分片、路由、Outbox |
| `batch-worker-core` | — | Worker 注册、心跳、执行适配器基座 |
| `batch-worker-import` | 18083 | 导入链路：RECEIVE → PREPROCESS → PARSE → VALIDATE → LOAD → FEEDBACK |
| `batch-worker-export` | 18084 | 导出链路：PREPARE → GENERATE → STORE → REGISTER → COMPLETE |
| `batch-worker-dispatch` | 18085 | 分发链路：PREPARE → DISPATCH → ACK → RETRY/COMPENSATE → COMPLETE |
| `batch-console-api` | 18080 | 控制台 REST API、审计、AI 辅助 |
| `batch-e2e-tests` | — | 端到端集成测试（内嵌 Orchestrator + Worker） |
| `security-scan` | — | 本地/CI 安全扫描编排工具（独立模块，不进 root reactor） |

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

### 环境变量文件

仓库提供三份独立环境配置：

- [`.env.example`](.env.example) - 模板样例，首次使用可复制为 `.env.local`
- [`.env.local`](.env.local) - 本地开发默认配置
- [`.env.test`](.env.test) - 测试环境隔离配置
- [`.env.prod`](.env.prod) - 生产环境模板配置，真实密钥应由密钥管理系统或 CI 注入

如果只想快速启动本地环境，先复制 `.env.example` 为 `.env.local` 即可。

### 启动本地基础设施

```bash
docker compose --env-file .env.local -f docker-compose.yml up -d
```

本地服务端口：

| 服务 | 地址 |
|------|------|
| PostgreSQL | `localhost:15432`（用户 `batch_user`，密码 `batch_pass_123`） |
| Redis | `localhost:16379` |
| Kafka | `localhost:19092` |
| MinIO API | `http://localhost:19000`（Bucket: `batch-dev`） |
| MinIO Console | `http://localhost:19001` |

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

### 启动应用容器栈

```bash
./scripts/docker/up-apps.sh
```

停止应用容器栈：

```bash
./scripts/docker/down-apps.sh
```

测试环境可切换为：

```bash
COMPOSE_ENV_FILE=.env.test ./scripts/docker/up-apps.sh
```

生产环境模板可切换为：

```bash
COMPOSE_ENV_FILE=.env.prod ./scripts/docker/up-apps.sh
```

### 本地联调启动

首次启动或代码有变更时，先构建本地应用模块：

```bash
bash scripts/local/build-apps.sh
```

再启动本地联调环境：

```bash
bash scripts/local/start-all.sh
```

停止本地 Java 进程：

```bash
bash scripts/local/stop-all.sh
```

说明：
- `start-all.sh` 默认只启动基础依赖和本地 Java 进程，不自动 Maven 打包
- 如需“构建 + 启动”，可使用 `BUILD=1 bash scripts/local/start-all.sh`

### 控制台默认登录

- 登录页：`/console-login.html`
- 默认 seed 账号：
  - `admin`
  - `auditor`
  - `config-admin`
- 登录接口：`POST /api/console/auth/login`
- 仓库只保存密码哈希，不保存明文密码
- 登录成功后返回 JWT，后续请求使用 `Authorization: Bearer <token>`

### 启动观测栈

```bash
./scripts/docker/up-observability.sh
```

停止观测栈：

```bash
./scripts/docker/down-observability.sh
```

也可以用更短的入口：

```bash
make observability-up
make observability-down
```

### 系统测试种子数据

```bash
scripts/data/load-system-test-data.sh
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
| [LICENSE](LICENSE) | Apache-2.0 许可声明 |
| [NOTICE](NOTICE) | 第三方声明和合规入口 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献和提交约定 |
| [SECURITY.md](SECURITY.md) | 安全漏洞报告入口 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |
| [测试文档索引](docs/testing/README.md) | 测试计划、覆盖矩阵、门禁规则和测试报告总入口 |
| [API 文档索引](docs/api/README.md) | 控制台接口协议、OpenAPI 和对接说明 |
| [本地开发](docs/runbook/local-development.md) | 环境搭建、调试、常见问题 |
| [安全扫描](docs/runbook/security-scan.md) | 本地漏洞自测组合：secret、依赖、SAST、镜像、ZAP |
| [Docker 部署](docs/deployment/docker-deployment.md) | 容器化部署指南 |
| [控制台侧边栏菜单树](docs/architecture/console-sidebar-menu-tree.md) | 前端 sidebar 分组、页面可见角色与操作权限边界 |
| [观测栈 Docker 环境](docs/deployment/observability-docker/README.md) | Prometheus / Exporter / OTel Collector / Jaeger / Loki / Grafana 的独立启动与管理 |
| [运行时通信](docs/architecture/runtime-module-communication.md) | 模块间消息协议与接口规范 |
| [设计差距审计](docs/architecture/design-gap-audit.md) | 当前实现与设计文档的差距分析 |
| [默认运行参数](docs/architecture/runtime-default-parameters.md) | 调度器、Worker、Outbox 等默认参数说明 |
| [Flyway 迁移](docs/sql/flyway/README.md) | 数据库迁移脚本说明 |

## 贡献指南

1. 遵守 `AGENT.md` 中的工程基线约束
2. 新功能必须附带对应的集成测试
3. 修改持久层时只维护 Flyway 迁移（`batch-orchestrator/src/main/resources/db/migration/`）；`platform-init.sql` 仅含与 V1 等价的 schema，勿再复制表 DDL
4. 不得引入 JPA/Hibernate 依赖
