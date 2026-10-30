# file-batch-system 项目结构

> 2026-10-01 更新。批量任务编排控制面 + 文件 / 任务交付闭环。本文按实际仓库结构区分四件事：平台运行时固定 10 个逻辑模块、根 Maven reactor 10 个 module path、独立语言 SDK / 独立 reactor / 前端配对仓库、工程治理与示例资产。

## 顶层结构

```
file-batch-system/
├── batch-common/                           基础设施 / Spring AutoConfiguration / 共享 DTO
├── batch-test-support/                     跨模块测试基础设施（仅 test scope）
├── batch-trigger/                          Quartz 调度触发 + 业务日历 → trigger_outbox_event
├── batch-orchestrator/                     状态主机:CLAIM/EXECUTE/REPORT 闭环 + workflow 编排
├── batch-worker/                           worker 聚合器(aggregator + 各 worker parent;artifactId 不变)
│   ├── core/                               Worker SPI 基础(pipeline stages 抽象;artifactId 仍为 batch-worker-core)
│   ├── import/                             IMPORT：RECEIVE→PREPROCESS→PARSE→VALIDATE→LOAD→FEEDBACK
│   ├── export/                             EXPORT：PREPARE→GENERATE→STORE→REGISTER→COMPLETE
│   ├── process/                            PROCESS pipeline(纯业务计算)
│   ├── dispatch/                           DISPATCH pipeline(下游分发)
│   └── atomic/                             专用 Task SPI(shell/sql/stored-proc/http 隔离,ADR-029)
├── batch-console-api/                      控制面 REST API(运维/查询/审批/配置/可观测)
├── sdk/
│   ├── java/{core,spring,testkit}/         Java SDK(纳入根 Maven reactor):核心 + Spring 适配 + testkit
│   ├── go/                                 Go SDK(独立工具链)
│   ├── python/                             Python SDK(httpx + pydantic + aiokafka,独立工具链)
│   ├── rust/                               Rust SDK(独立工具链)
│   └── typescript/                         TypeScript SDK(独立工具链)
├── batch-e2e-tests/                        BE 端到端测试(根 reactor 内)
├── load-tests/                             压测(独立 reactor,不入根 reactor)
├── security-scan/                          安全扫描编排工具(独立模块,不入根 reactor)
├── examples/                               自托管 SDK / Task SPI 插件示例(长期示例资产)
│
├── config/                                质量规则 / 日志策略 / 运维治理 profile（见 config/README.md）
├── db/migration/                           Flyway PostgreSQL migrations（以 db/migration 实际版本为准）
├── docs/                                   全文档体系(见下)
├── scripts/                                工程脚本（分类与入口见 scripts/README.md）
├── helm/batch-platform/                    Helm Chart(prod 部署)
├── docker-compose.yml                      本地基础依赖 Compose 入口
├── docker-compose.kafka-ha.yml             本地 Kafka HA 可选叠加层
├── docker-compose.redis-sentinel-ha.yml     本地 Valkey Sentinel 可选演练叠加层
├── docker-compose.minio-ha.yml              本地 MinIO 分布式纠删码隔离演练栈
├── deploy/                                 Docker / GitOps / HA / release 部署资源（见 deploy/README.md）
├── .agents/skills/                         本仓专用 Codex 工程技能(只登记 SKILL.md)
├── .github/workflows/                      CI(pr-gate / strict-verify / sdk-publish 等)
├── .githooks/                              本地 pre-commit / pre-push 守护
├── pom.xml                                 Root POM(flatten + revision 占位)
├── docs/agent-baseline.md                  项目高频违反红线 + 关键路径指针(权威)
└── AGENTS.md                               Agent / SDK 协议总览
```

不作为结构事实来源的目录：`target/`、`build/`、`logs/`、`reports/`、`.venv/`、`.idea/`、`.vscode/`、`.ruff_cache/` 等属于构建、运行、本地工具或 IDE 产物。文档、脚本和 CI 不应依赖这些目录作为长期源码结构。

质量规则源码统一维护在 [config/quality/](../../config/quality/README.md)，根 Maven 和参数规则回归引用同一份文件；`build/` 不再保留受版本控制的规则文件。工程配置与部署资源的职责入口分别为 [config/README.md](../../config/README.md)、[deploy/README.md](../../deploy/README.md)。

## 模块结构图

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  FE["batch-console · 配对前端"] --> C["batch-console-api"]
  C -->|"发起运行"| T["batch-trigger"]
  C -->|"审批 / 运维"| O["batch-orchestrator"]
  T -->|"Trigger Outbox Relay"| K1["Kafka · launch"] --> O
  O -->|"Task Outbox Relay"| K2["Kafka · task dispatch"] --> W["五类 Worker 应用"]
  W -->|"HTTP CLAIM / REPORT"| O
  C --> DB[("平台 PostgreSQL")]
  T --> DB
  O --> DB
  W --> B["业务库 / 批处理对象存储 / 外部通道"]
  C --> A["AI 附件 · 独立 bucket / prefix"]
  COMMON["batch-common · 共享库"] -. "依赖" .-> C
  T -. "依赖" .-> COMMON
  O -. "依赖" .-> COMMON
  W -. "依赖" .-> COMMON
  W -. "依赖" .-> CORE["batch-worker/core · 共享执行库"]
```

图中两条边界需要同时成立：平台运行时沿 `trigger → orchestrator → worker` 传递任务状态；
Console 内部沿 `web → application.contract → application/domain` 处理控制面请求，`shared` /
`support` 只提供跨 context 的值对象和轻量支撑，并由 `infrastructure` 实现外部访问端口。
Console 的 DTO 不应下沉到 `batch-common`，Worker 也不能
绕过 Orchestrator 直接写实例状态。

AI 会话附件属于 Console 控制面数据：PostgreSQL 保存租户、所有者、会话绑定、对象键、保留期和审计元数据，文件正文进入与批处理业务文件物理隔离的 S3 bucket 或受控 prefix。浏览器只调用 Console API；不得直连对象存储、复用批处理文件下载权限或把对象存储凭据下发前端。存储隔离和生命周期配置见 [`../runbook/object-storage-s3-backends.md`](../runbook/object-storage-s3-backends.md)。

配对前端仓库不在本仓内；前后端联调时使用 sibling repo `../batch-console`，约定见根目录 [`../../AGENTS.md`](../../AGENTS.md)。

## Maven reactor 边界

根 [`../../pom.xml`](../../pom.xml) 当前纳入 10 个 module path：

```text
batch-common
batch-test-support          # 只供测试依赖，不进运行时
batch-trigger
batch-orchestrator
batch-worker                  # aggregator;内部聚合 6 个 worker 子模块
sdk/java/core
sdk/java/spring
sdk/java/testkit
batch-console-api
batch-e2e-tests
```

`load-tests`、`security-scan`、`sdk/{go,python,rust,typescript}` 是独立 reactor / 独立语言工具链，不按根 Maven reactor 统计。

## 平台运行时模块(固定,不可擅自增删;worker 已聚合到 `batch-worker` 下)

> `batch-worker` 是聚合器(aggregator + parent),其下 6 个子模块的 artifactId 保持不变(仍为 `batch-worker-core` / `batch-worker-import` 等)。

| 模块 | 主要 package | 一句话职责 |
|---|---|---|
| `batch-common` | `common/{config,events,outbox,security,...}` | 跨模块运行时复用:AutoConfig、Outbox 抽象、RLS、Timezone、i18n |
| `batch-trigger` | `trigger/{quartz,calendar,outbox}` | Quartz 调度 → `trigger_outbox_event`（orchestrator 消费后启动 instance） |
| `batch-orchestrator` | `orchestrator/{application,domain,infrastructure,controller}` | **状态主机**:CLAIM / EXECUTE / REPORT 状态流转;workflow DAG 编排;outbox 投递 |
| `batch-worker/core`(artifactId `batch-worker-core`) | `worker/core/{support,infrastructure,mapper,route}` | Worker 执行骨架、阶段上下文、步骤登记、路由统一契约 |
| `batch-worker/import`(artifactId `batch-worker-import`) | `worker/imports/{stage,domain,infrastructure}` | 文件 IMPORT 6 stages(RECEIVE/PREPROCESS/PARSE/VALIDATE/LOAD/FEEDBACK);预处理策略由固定阶段调用 |
| `batch-worker/export`(artifactId `batch-worker-export`) | `worker/exports/{stage,renderer,sink}` | 文件 EXPORT 5 stages(PREPARE/GENERATE/STORE/REGISTER/COMPLETE);导出格式策略由固定阶段调用 |
| `batch-worker/process`(artifactId `batch-worker-process`) | `worker/processes/{stage,...}` | 纯业务计算(无文件 IO) |
| `batch-worker/dispatch`(artifactId `batch-worker-dispatch`) | `worker/dispatch/{stage,target,...}` | 下游分发(向外部系统投递);渠道适配器由固定阶段路由 |
| `batch-worker/atomic`(artifactId `batch-worker-atomic`) | `worker/atomic/{shell,sql,stored-proc,http}` | **专用 Task SPI**:特权执行器隔离(RCE 风险面收敛);插件能力替换原子执行实现 |
| `batch-console-api` | `console/{application,domain,infrastructure,shared,support,web}` | 控制面 REST + 审批 + 运维操作 + AI Ops + 可观测(唯一允许走读写分离的模块) |

## 平台模块展开说明

下表是模块职责的展开版。新增代码先按“状态归属”和“协议边界”判断归属，再按包结构落位；不能
因为调用方便就把控制面、调度状态或业务数据写入责任下沉到共享模块或 Worker。

| 模块 | 主要内部结构 | 负责什么 | 不负责什么 / 硬边界 |
|---|---|---|---|
| `batch-common` | `config`、`events`、`outbox`、`security`、`rls`、`timezone`、`i18n` | 跨模块基础设施、公共协议、自动配置和安全基线 | 不放 Console DTO、业务编排、对象存储/AI/Excel 等重运行时依赖 |
| `batch-test-support` | Testcontainers、数据库初始化、共享测试资源、测试 fixture、共享断言 | 为 Java 模块提供测试基础设施 | 仅 test scope，不进入生产运行时 |
| `batch-trigger` | `quartz`、`calendar`、`outbox`、`controller` | 计算触发时间、业务日历、misfire/catch-up，并写入 trigger outbox | 不直接读取或写入 Orchestrator 状态表，不执行 Worker 任务 |
| `batch-orchestrator` | `controller`、`application`、`domain`、`infrastructure`、`mapper` | 唯一状态主机，负责 launch、claim、report、lease、retry、workflow 编排和任务派发 | 不执行文件/业务处理，不让 Worker 直接改 `job_instance`、`job_task`、`outbox_event` |
| `batch-worker-core` | `support`、`infrastructure`、`mapper`、`route`、pipeline runtime | Worker SPI、任务领取/续租/上报适配、阶段上下文和通用执行骨架 | 不拥有调度状态，不绕过 Orchestrator 写控制面终态 |
| `batch-worker-import` | `imports/{stage,domain,infrastructure}` | 文件接收、解析、校验、COPY/UPSERT/分区导入、checkpoint；预处理策略由固定阶段调用 | 不负责调度、跨作业 DAG 决策或外部通知编排 |
| `batch-worker-export` | `exports/{stage,renderer,sink}` | 查询、渲染、分片导出、multipart 和对象存储落地；导出格式策略由固定阶段调用 | 不负责导出任务 launch、租户审批或调度状态推进 |
| `batch-worker-process` | `processes/{stage,domain,infrastructure}` | staging、计算/聚合、校验、commit 和幂等重跑 | 不承担文件传输和控制面状态机 |
| `batch-worker-dispatch` | `dispatch/{stage,target,infrastructure}` | SFTP、NAS、HTTP、S3 兼容对象存储等下游交付、重试和 manifest；渠道适配器由固定阶段路由 | 不负责源数据计算、调度编排或权限审批 |
| `batch-worker-atomic` | `atomic/{shell,sql,stored-proc,http,runtime}` | 隔离的原子 Task SPI，执行受控 shell/SQL/存过/HTTP；插件能力替换原子执行实现 | 不扩展成通用编排器；必须遵守 allowlist、隔离和超时边界 |
| `batch-console-api` | `web`、`application`、`domain`、`infrastructure`、`shared`、`support` | REST/BFF、配置、查询、审批、运维动作、审计、AI Ops 和可观测性 | 不成为状态主机，不执行长任务，不把 API DTO 下沉到 common/worker |

### 平台模块展开图

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart LR
  subgraph WORKERS["Worker 应用 · 前四类复用文件 Pipeline"]
    IMP["import"]
    EXP["export"]
    PROC["process"]
    DISP["dispatch"]
    AT["atomic"]
  end
  IMP -. "依赖" .-> CORE["batch-worker/core<br/>SPI / 租约 / Pipeline runtime"]
  EXP -. "依赖" .-> CORE
  PROC -. "依赖" .-> CORE
  DISP -. "依赖" .-> CORE
  AT -. "依赖" .-> CORE
  CORE -. "依赖" .-> COMMON["batch-common<br/>共享基础设施"]
  T["batch-trigger<br/>Quartz / Calendar / Outbox"] -. "依赖" .-> COMMON
  O["batch-orchestrator<br/>应用 / 领域 / 持久化"] -. "依赖" .-> COMMON
```

```mermaid
%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%
flowchart TB
  subgraph CONSOLE["batch-console-api · 内部依赖"]
    WEB["web · HTTP / SSE"] --> CONTRACT["application.contract"]
    WEB --> APP["application · 用例"]
    APP --> CONTRACT
    APP --> DOMAIN["domain · 规则 / Port"]
    INFRA["infrastructure · Port 实现"] -. "实现" .-> DOMAIN
    APP --> SHARED["shared / support"]
  end
  APP -. "依赖" .-> COMMON["batch-common · 共享基础设施"]
```

### 辅助模块边界

| 模块 | 定位 | 边界 |
|---|---|---|
| `batch-e2e-tests` | 全链路验收和真实依赖测试 | 可以依赖所有运行模块，但生产代码不得反向依赖它 |
| `load-tests` | 压测、容量画像和结果校验 | 不进入生产 reactor，不承载业务运行时逻辑 |
| `security-scan` | DAST/依赖/安全扫描编排 | 只消费应用和构建产物，不参与业务主链 |
| `examples/self-hosted-sdk` | 租户自托管 SDK 接入示例 | 只展示协议接入方式，不复制平台内部实现 |
| `examples/task-spi-plugin` | Task SPI 插件示例 | 展示插件打包和注册，不改变固定 pipeline 顺序 |
| `sdk/*` | 租户自托管 Worker 接入 | 只通过协议接入 Orchestrator，不依赖平台内部 Entity 或数据库 |

Worker 扩展约定：平台固定 `claim → stage pipeline → report` 的执行骨架；Import / Export /
Dispatch 等 worker 的插件或策略只在对应固定阶段内被调用。没有插件时走内置默认实现；有插件时
按能力、类型或配置路由到插件实现。插件不得新增一套调度状态机，也不得跳过 Orchestrator 的
claim、lease、report 和终态 CAS。详细设计见 [`../design/task-spi-design.md`](../design/task-spi-design.md)。

Console 内部约定：`web` 只放 HTTP/SSE/文件流适配器；请求、查询、响应契约放在所属 context 的
`application.contract.{request,query,response}`，跨 context 契约才进入顶层
`console.application.contract`。`shared` 放跨 context 值对象、事件、查询模型和轻量安全支撑；
`support` 放 Web、缓存、命名、Excel、限流等技术支撑。应用层和基础设施层不得依赖 `web` 下的契约包。

Controller 的跨服务查询或运维动作必须依赖应用端口，不得直接持有
`OrchestratorInternalRestClient`、`RestClient` 或 `BatchObjectStore`。内部 URL、租户解析、降级策略、
响应 envelope 透传和外部存储的签名/路径校验分别由 application port 的 infrastructure adapter 或文件应用服务负责；
Controller 只绑定参数、执行 Bean Validation、调用端口并适配 HTTP 响应。治理参数默认值和 allowlist 也属于应用策略，
由 `ConsoleGovernanceParameterPolicy` 持有，避免在 Controller 中维护业务配置规则。

### Console 分层职责结论（2026-09-28）

| 层 | 允许承担的职责 | 明确不承担的职责 | 允许依赖 |
|---|---|---|---|
| `web` | HTTP 路由、认证/授权入口、参数绑定、Bean Validation、状态码和响应包装、SSE/文件流适配 | 业务编排、MyBatis 查询、事务边界、直接操作 Entity 持久化 | `application`、`application.contract`、Web/HTTP 基础设施 |
| `application.contract` | API 入参、查询条件、响应投影、跨层稳定契约和校验声明 | 业务规则、数据库访问、Spring Web 类型、持久化 Entity | JDK、通用值对象、必要的轻量序列化注解 |
| `application` | 用例编排、事务边界、租户守卫、跨 context 端口和业务操作协调 | HTTP 细节、Controller 状态码、底层数据库实现细节 | `application.contract`、领域对象/端口、shared、batch-common |
| `domain` | Entity、领域规则、领域端口、context 内业务语义 | 依赖其他 context 的具体实现、直接暴露 HTTP DTO | context 内部包、显式 application port、shared |
| `infrastructure` | MyBatis、对象存储、Kafka/Redis/HTTP 等外部适配器、端口实现 | HTTP 路由、API DTO 组装、跨 context 业务决策 | `application` 端口、`application.contract` 投影、domain、shared、外部库 |
| `service`（存量） | 尚未迁移的应用服务兼容层；新改动仅允许继续收敛职责 | 新增 Web DTO 依赖、直接承载跨 context 业务编排 | `application.contract`、domain/application port、shared |
| `shared` | 无业务逻辑的跨 context 值对象、事件、纯工具 | 业务规则、数据库访问、context 专属 DTO 的集中存放 | JDK、明确批准的公共库 |
| `support` | Web、缓存、Excel、命名、安全表达式、限流等技术支撑 | 业务编排、领域规则、数据库写入 | JDK、Spring 基础设施、shared、轻量第三方库 |

边界方向固定为：

```text
HTTP/Web -> Application Contract -> Application Port/Use Case -> Domain
                                      ^
                                      |
                              Infrastructure Adapter
```

存量收口结论：`batch-console-api` 的 `web.request`、`web.query`、`web.response` 已全部迁移到所属
context 的 `application.contract`（跨 context 的通用契约才放顶层 `console.application.contract`）。
应用层、基础设施层和存量 `service` 层不得反向引用 `web` 契约；Controller 不得直接返回持久化
Entity，必须通过 Response DTO 投影。Webhook secret 等敏感字段必须由 Response DTO 显式排除。

后续复扫使用以下门禁作为唯一判据：

```bash
./mvnw -pl batch-console-api -am test
.venv/bin/python scripts/ci/check-console-openapi-paths.py
```

并检查 `LayerBoundaryArchTest`、`BoundedContextDependencyArchTest`、旧 `web.request/query/response`
引用和 Controller 的 Entity 返回类型。未通过任一项，不得宣称模块边界收口完成。

## SDK 模块(多语言,不属平台运行时固定模块)

| 模块 | 类型 | 用途 |
|---|---|---|
| `sdk/java/core` | Java(Spring-free;根 reactor 内) | 租户在自有进程里跑 worker 的核心 SDK(ADR-035) |
| `sdk/java/spring` | Java(可选;根 reactor 内) | SDK 的 Spring Boot 自动装配 starter |
| `sdk/java/testkit` | Java(test-scope;根 reactor 内) | `FakeBatchPlatform` 端到端测试工具 |
| `sdk/go` | Go | 与 Java SDK 对齐的 Go 实现 |
| `sdk/python` | Python | 与 Java SDK 对齐的 Python 实现(httpx + pydantic + aiokafka) |
| `sdk/rust` | Rust | 与 Java SDK 对齐的 Rust 实现 |
| `sdk/typescript` | TypeScript | 与 Java SDK 对齐的 TypeScript 实现 |

## 关键架构约束(详 [`../../docs/agent-baseline.md`](../agent-baseline.md))

- **主链**:`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- **orchestrator 是唯一状态主机**:worker 不能直写 `job_instance` / `workflow_run`
- **读写分离仅 console-api**:trigger / orchestrator / worker 禁引入
- **3 张 outbox 表分工**:`outbox_event`(通用) / `event_outbox_retry`(退避重试) / `trigger_outbox_event`(trigger fire)
- **持久化统一 MyBatis**(禁 JPA / Spring Data JDBC),entity `*Entity` 后缀(禁 `*Record`)
- **多租隔离**:所有业务表 `tenant_id` + UNIQUE 含 tenant_id,守护 `MapperXmlTenantGuardArchTest` × 6 模块
- **archive 1:1 镜像**:纳入归档的 `batch.*` 表改 schema 必须同 PR 补 `archive.*_archive`(`ArchiveSchemaDriftCheck` 启动期 fail-fast);明确不归档的表须有数据保留与隐私依据。V218 的 `console_ai_conversation` / `console_ai_turn` 含用户对话数据，按用户删除和保留期清理，不复制至 archive

## docs/ 体系

```text
docs/
├── README.md / agent-baseline.md / coding-conventions.md / changelog.md
├── architecture/   架构、模型、原语、边界、运行主链与 ADR
├── design/         业务与能力设计、数据库逻辑模型
├── api/            OpenAPI、协议与 API 黄金样本
├── sdk/            五语言 SDK 接入、通信与发布契约
├── dict/           错误码、配置键生成参考与统一术语
├── standards/      工程规范、文档与协作流程
├── governance/     可机器读取的治理契约与清单
├── plans/          总方案、阶段计划、TODO与持续台账
├── audit/          日期化审计、扫描与调查记录
├── review/         日期化评审结论
├── testing/        长期验证方法、用例说明与覆盖策略
├── verifications/  日期化执行结果与验收证据
├── runbook/        开发部署、运维、恢复与排障手册
├── compliance/     合规与第三方许可证资料
├── stats/          代码量等统计快照
└── archive/        按上述职责分类的历史资料
```

长期资产原位更新，不带文件名日期；阶段/审计/执行记录按文档路由保留日期。没有 analysis/backlog/spike 或 docs/test-data 目录；可执行共享测试数据在根 `test-data/`，API黄金样本仍属于协议资产。每个目录README负责文件用途与跳转。

## scripts/ 体系

```text
scripts/
├── ci/        质量与CI守卫（基线在ci/baselines）
├── codegen/   代码/契约生成
├── data/      场景数据与初始化工具
├── db/        数据库工具与独立SQL；Flyway在根db/migration
├── deploy/    交付部署工具
├── dev/       开发工具与诊断
├── docker/    镜像/Compose辅助
├── ha/        HA/DR演练
├── lib/       共享脚本能力
├── local/     本地启动与验收
├── minio/     对象存储本地辅助
├── ops/       运维、观测与环境诊断
├── sim/       场景模拟
└── sim-4day/  四日链路模拟
```

脚本/SQL的逐项用途和调用入口以 [scripts/README.md](../../scripts/README.md) 及各子目录索引为准。`__pycache__` 等本地产物不是脚本分类；不恢复已合并到dev/ops的tools、observability或staging目录。

## 运行系统边界

| 运行环境 | 支持级别 | 说明 |
|---|---|---|
| Linux 容器 / Kubernetes | 生产主路径 | Helm、Dockerfile、JVM 日志/JFR/heap dump 路径和运维脚本均以 Linux 容器为权威目标 |
| Linux 主机 + Docker Compose | 本地 / staging 验证主路径 | 本地依赖栈、sim、BE acceptance 和压测脚本默认按 Bash + Docker Compose 执行 |
| Windows + WSL2 + Docker Desktop | 推荐的 Windows 开发形态 | 仓库、JDK、Maven、Python、Git 和脚本放在 WSL2 Linux 文件系统内运行；Windows 宿主机只承载 IDE、浏览器和 Docker Desktop |
| Windows Server 原生进程 | 暂不承诺 | 当前没有 PowerShell 运维体系、Windows Service 托管脚本、Windows 原生日志 / 临时目录 / kill 语义验证，也没有 Windows runner 门禁 |

WSL2 使用约束：仓库不要放在 `/mnt/c/...` 下跑 Maven、Docker bind mount、脚本或测试；建议放在
`~/workspace/file-batch-system` 这类 WSL2 Linux 文件系统路径。Git 换行建议 `core.autocrlf=input`，
脚本仍按 Linux 入口执行，例如 `bash scripts/local/start-all.sh`、`./mvnw` 和 `docker compose ...`。

Netty macOS DNS profile 边界：`netty.version` 由根 POM 统一锁定；`netty-resolver-dns-native-macos`
只放在可独立启动的应用模块或 worker parent 中，避免 macOS 本地运行时的 DNS warning。不要把该
native resolver 抽到根 POM 默认依赖，否则会污染非运行时模块、SDK、SBOM 和许可证分析。

## 构建命令

| 命令 | 用途 |
|---|---|
| `mvn package` | 默认 build,产物 `batch-*-${revision}.jar` |
| `mvn -Drevision=X.Y.Z package` | release 覆盖版本 |
| `mvn test -DskipITs` | 跳 IT 只跑单测(**禁 `-Dmaven.test.skip=true`**) |
| `mvn -pl <mod> -am test` | 单模块测试(含上游依赖) |
| `mvn clean verify` | 完整 build + 集成测(需 Docker 起 testcontainers) |

## 分支策略

| 分支 | 用途 |
|---|---|
| `main` | 唯一发布分支,所有 PR 合到这 |
| `feature/<topic>` | 业务开发 / bug fix / 测试 / 文档(标准 PR → main) |
| `fix/<topic>` | bug 修复(同上,PR → main) |
| `docs/<topic>` | 纯文档变更(PR → main) |
