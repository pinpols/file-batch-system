# file-batch-system 项目结构

> 2026-06-03 整理。批量任务编排控制面 + 文件 / 任务交付闭环。10 平台模块 Maven multi-module + 4 SDK 模块 + 独立 reactor。

## 顶层结构

```
file-batch-system/
├── batch-common/                           基础设施 / Spring AutoConfiguration / 共享 DTO
├── batch-trigger/                          调度触发(Quartz + 业务日历)→ trigger_outbox_event
├── batch-orchestrator/                     状态主机:CLAIM/EXECUTE/REPORT 闭环 + workflow 编排
├── batch-worker-core/                      Worker SPI 基础(pipeline stages 抽象)
├── batch-worker-import/                    IMPORT pipeline(Preprocess→Validate→Load,5 stages)
├── batch-worker-export/                    EXPORT pipeline(Query→Render→Sink,6 stages)
├── batch-worker-process/                   PROCESS pipeline(纯业务计算)
├── batch-worker-dispatch/                  DISPATCH pipeline(下游分发)
├── batch-worker-atomic/                    专用 Task SPI(shell/sql/stored-proc/http 隔离,ADR-029)
├── batch-console-api/                      控制面 REST API(运维/查询/审批)
├── batch-worker-sdk/                       租户自托管 SDK(Spring-free,ADR-035)
├── batch-worker-sdk-spring-boot-starter/   SDK 的可选 Spring 适配模块
├── batch-worker-sdk-testkit/               SDK 端到端测试工具(FakeBatchPlatform)
├── batch-worker-sdk-python/                Python SDK(httpx + pydantic + aiokafka)
├── batch-e2e-tests/                        BE 端到端测试(独立 reactor 节点)
├── load-tests/                             压测(独立 reactor,不入主 reactor)
│
├── db/migration/                           Flyway PostgreSQL migrations(V160+)
├── archive/migration/                      冷表 archive.* schema migrations
├── docs/                                   全文档体系(见下)
├── scripts/                                工程脚本(ci/db/dev/docker/local/ops/ps1/tools)
├── helm/batch-platform/                    Helm Chart(prod 部署)
├── docker/                                 Docker Compose / Dockerfile
├── .github/workflows/                      CI(pr-gate / strict-verify / sdk-publish 等)
├── .githooks/                              本地 pre-commit / pre-push 守护
├── pom.xml                                 Root POM(flatten + revision 占位)
├── CLAUDE.md                               项目高频违反红线 + 关键路径指针(权威)
└── AGENTS.md                               Agent / SDK 协议总览
```

## 平台 10 模块(固定,不可擅自增删)

| 模块 | 主要 package | 一句话职责 |
|---|---|---|
| `batch-common` | `common/{config,events,outbox,security,testing,...}` | 跨模块复用:AutoConfig、Outbox 抽象、RLS、Timezone、i18n、Testcontainers 基类 |
| `batch-trigger` | `trigger/{quartz,calendar,outbox}` | Quartz 调度 → `trigger_outbox_event`(orchestrator 拉取后启动 instance) |
| `batch-orchestrator` | `orchestrator/{application,domain,infrastructure,controller}` | **状态主机**:CLAIM / EXECUTE / REPORT 状态流转;workflow DAG 编排;outbox 投递 |
| `batch-worker-core` | `worker/core/{pipeline,stage,registry}` | Worker SPI 抽象、PipelineStage 接口、StepRegistry |
| `batch-worker-import` | `worker/imports/{stage,domain,infrastructure}` | 文件 IMPORT 5 stages(Preprocess/Validate/Load/...) |
| `batch-worker-export` | `worker/exports/{stage,renderer,sink}` | 文件 EXPORT 6 stages(Query/Render/Sink/...) |
| `batch-worker-process` | `worker/processes/{stage,...}` | 纯业务计算(无文件 IO) |
| `batch-worker-dispatch` | `worker/dispatch/{stage,target,...}` | 下游分发(向外部系统投递) |
| `batch-worker-atomic` | `worker/atomic/{shell,sql,stored-proc,http}` | **专用 Task SPI**:特权执行器隔离(RCE 风险面收敛) |
| `batch-console-api` | `console/{domain,application,controller,...}` | 控制面 REST + 审批 + 运维操作(唯一允许走读写分离的模块) |

## SDK 4 模块(不属固定 10)

| 模块 | 类型 | 用途 |
|---|---|---|
| `batch-worker-sdk` | Java(Spring-free) | 租户在自有进程里跑 worker 的核心 SDK |
| `batch-worker-sdk-spring-boot-starter` | Java(可选) | SDK 的 Spring Boot 自动装配 starter |
| `batch-worker-sdk-testkit` | Java(test-scope) | `FakeBatchPlatform` 端到端测试工具 |
| `batch-worker-sdk-python` | Python | 与 Java SDK 对齐的 Python 实现(PyPI: `batch-worker-sdk-python`) |

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
├── CLAUDE.md → ../CLAUDE.md 红线 + 关键路径(根目录的 CLAUDE.md 是权威)
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
├── local/     本地特定(sync-main.sh / pre-push-sdk-checks.sh / be-acceptance.sh)
├── ops/       运维(prod 巡检 / 一次性脚本)
├── ps1/       PowerShell 等价物(Windows)
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
