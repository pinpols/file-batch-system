# file-batch-system 项目结构

> 2026-06-29 更新。批量任务编排控制面 + 文件 / 任务交付闭环。本文按实际仓库结构区分三件事：平台运行时固定 10 个逻辑模块、根 Maven reactor 9 个 module path、独立语言 SDK / 独立 reactor / 前端配对仓库。

## 顶层结构

```
file-batch-system/
├── batch-common/                           基础设施 / Spring AutoConfiguration / 共享 DTO
├── batch-trigger/                          调度触发(默认 wheel,opt-in Quartz + 业务日历)→ trigger_outbox_event
├── batch-orchestrator/                     状态主机:CLAIM/EXECUTE/REPORT 闭环 + workflow 编排
├── batch-worker/                           worker 聚合器(aggregator + 各 worker parent;artifactId 不变)
│   ├── core/                               Worker SPI 基础(pipeline stages 抽象;artifactId 仍为 batch-worker-core)
│   ├── import/                             IMPORT pipeline(Preprocess→Validate→Load,5 stages)
│   ├── export/                             EXPORT pipeline(Query→Render→Sink,6 stages)
│   ├── process/                            PROCESS pipeline(纯业务计算)
│   ├── dispatch/                           DISPATCH pipeline(下游分发)
│   └── atomic/                             专用 Task SPI(shell/sql/stored-proc/http 隔离,ADR-029)
├── batch-console-api/                      控制面 REST API(运维/查询/审批)
├── sdk/
│   ├── java/{core,spring,testkit}/         Java SDK(纳入根 Maven reactor):核心 + Spring 适配 + testkit
│   ├── go/                                 Go SDK(独立工具链)
│   ├── python/                             Python SDK(httpx + pydantic + aiokafka,独立工具链)
│   ├── rust/                               Rust SDK(独立工具链)
│   └── typescript/                         TypeScript SDK(独立工具链)
├── batch-e2e-tests/                        BE 端到端测试(根 reactor 内)
├── load-tests/                             压测(独立 reactor,不入根 reactor)
├── security-scan/                          安全扫描编排工具(独立模块,不入根 reactor)
│
├── db/migration/                           Flyway PostgreSQL migrations(V1 起,当前到 V184)
├── docs/                                   全文档体系(见下)
├── scripts/                                工程脚本(ci/db/dev/docker/local/ops/tools)
├── helm/batch-platform/                    Helm Chart(prod 部署)
├── docker/                                 Docker Compose / Dockerfile
├── .github/workflows/                      CI(pr-gate / strict-verify / sdk-publish 等)
├── .githooks/                              本地 pre-commit / pre-push 守护
├── pom.xml                                 Root POM(flatten + revision 占位)
├── CLAUDE.md                               项目高频违反红线 + 关键路径指针(权威)
└── AGENTS.md                               Agent / SDK 协议总览
```

配对前端仓库不在本仓内；前后端联调时使用 sibling repo `../batch-console`，约定见根目录 [`../../AGENTS.md`](../../AGENTS.md)。

## Maven reactor 边界

根 [`../../pom.xml`](../../pom.xml) 当前纳入 9 个 module path：

```text
batch-common
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
| `batch-common` | `common/{config,events,outbox,security,testing,...}` | 跨模块复用:AutoConfig、Outbox 抽象、RLS、Timezone、i18n、Testcontainers 基类 |
| `batch-trigger` | `trigger/{wheel,quartz,calendar,outbox}` | 调度(默认 wheel,opt-in Quartz)→ `trigger_outbox_event`(orchestrator 拉取后启动 instance) |
| `batch-orchestrator` | `orchestrator/{application,domain,infrastructure,controller}` | **状态主机**:CLAIM / EXECUTE / REPORT 状态流转;workflow DAG 编排;outbox 投递 |
| `batch-worker/core`(artifactId `batch-worker-core`) | `worker/core/{pipeline,stage,registry}` | Worker SPI 抽象、PipelineStage 接口、StepRegistry |
| `batch-worker/import`(artifactId `batch-worker-import`) | `worker/imports/{stage,domain,infrastructure}` | 文件 IMPORT 5 stages(Preprocess/Validate/Load/...) |
| `batch-worker/export`(artifactId `batch-worker-export`) | `worker/exports/{stage,renderer,sink}` | 文件 EXPORT 6 stages(Query/Render/Sink/...) |
| `batch-worker/process`(artifactId `batch-worker-process`) | `worker/processes/{stage,...}` | 纯业务计算(无文件 IO) |
| `batch-worker/dispatch`(artifactId `batch-worker-dispatch`) | `worker/dispatch/{stage,target,...}` | 下游分发(向外部系统投递) |
| `batch-worker/atomic`(artifactId `batch-worker-atomic`) | `worker/atomic/{shell,sql,stored-proc,http}` | **专用 Task SPI**:特权执行器隔离(RCE 风险面收敛) |
| `batch-console-api` | `console/{domain,application,controller,...}` | 控制面 REST + 审批 + 运维操作(唯一允许走读写分离的模块) |

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

## 关键架构约束(详 [`../../CLAUDE.md`](../../CLAUDE.md))

- **主链**:`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- **orchestrator 是唯一状态主机**:worker 不能直写 `job_instance` / `workflow_run`
- **读写分离仅 console-api**:trigger / orchestrator / worker 禁引入
- **3 张 outbox 表分工**:`outbox_event`(通用) / `event_outbox_retry`(退避重试) / `trigger_outbox_event`(trigger fire)
- **持久化统一 MyBatis**(禁 JPA / Spring Data JDBC),entity `*Entity` 后缀(禁 `*Record`)
- **多租隔离**:所有业务表 `tenant_id` + UNIQUE 含 tenant_id,守护 `MapperXmlTenantGuardArchTest` × 6 模块
- **archive 1:1 镜像**:`batch.*` 改 schema 必须同 PR 补 `archive.*_archive`(`ArchiveSchemaDriftCheck` 启动期 fail-fast)

## docs/ 体系

```
docs/
├── README.md                文档总入口(新人从这里)
├── changelog.md             架构约束 / CLAUDE.md 变更日志(日期倒序)
├── coding-conventions.md    Java 编码细则 + 反例表(CLAUDE.md §Java 红线展开)
├── agent-baseline.md        Agent / 自动化协作基线
│
├── analysis/                深扫报告(2026-06-03 全方位 11 lane / P2 评估等)
├── api/                     console-api OpenAPI yaml + protocol changelog
├── architecture/            架构图 + 项目结构(本文件)
├── archive/                 历史归档(老分析 / 失效 ADR)
├── audit/                   安全审计 / 合规 audit
├── backlog/                 待办 / roadmap
├── compliance/              合规清单
├── design/                  设计文档(pipeline-vs-workflow / sensor / sdk 等)
├── dict/                    业务字典 / enum 表
├── plans/                   阶段计划(Phase / R3 等)
├── review/                  评审结论
├── runbook/                 运维手册(release / rollback / sdk-publish / feature-switches)
├── sdk/                     SDK 使用说明(Java / Python)
├── spike/                   Spike 实验记录
├── stats/                   代码统计(LoC / 覆盖率)
├── test-data/               测试数据规约
├── testing/                 测试约定(单测 / IT / Testcontainers)
└── verifications/           验证记录(CD / e2e)
```

## scripts/ 体系

```
scripts/
├── ci/        CI 辅助(workflow-lint / strict-verify 等)
├── codegen/   代码生成(OpenAPI / fixture)
├── data/      测试 / seed 数据生成
├── db/        DB 工具(migration check / schema dump)
├── dev/       本地开发(start-stack / reset)
├── docker/    Docker compose 辅助
├── ha/        高可用 / DR 演练脚本
├── lib/       脚本共享函数库
├── local/     本地特定(pre-push-sdk-checks.sh / be-acceptance.sh / sdk-handler-tests.sh)
├── ops/       运维(prod 巡检 / 一次性脚本)
├── ps1/       PowerShell / Windows 兼容辅助
├── sim/       场景模拟脚本
├── sim-4day/  四日链路模拟脚本
└── tools/     杂项工具
```

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
| `feature/docker-deploy` | 本地 Docker 部署相关(**不 PR 进 main**,只接 main → deploy 单向 sync) |
| `fix/<topic>` | bug 修复(同上,PR → main) |
| `docs/<topic>` | 纯文档变更(PR → main) |
