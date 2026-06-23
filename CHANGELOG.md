# 变更日志

本文件记录仓库的主要变更，按日期倒序（最新在上）。分类遵循 [Keep a Changelog](https://keepachangelog.com/) 约定：**Added**（新增）/ **Changed**（调整）/ **Fixed**（修复）/ **Removed**（移除）/ **Notes**（说明）。

日期基于提交时间（`Asia/Shanghai`）。版本号采用 Maven CI-friendly `${revision}`，默认 `1.0.0`（非 SNAPSHOT），构建期可通过 `-Drevision=X.Y.Z` 覆盖。

---

## [Unreleased]

滚动合并中的变更入口；冻结发版时整段下移到正式版本标题下。

---

## [1.1.0] - 2026-06-24

### Added
- 本地 Docker Compose 增加 Kafka UI 服务，默认端口 `18090`，连接同一 compose 网络内的 Kafka `kafka:29092`。
- MinIO 运维文档补充 `mc` 常用命令，覆盖 alias、bucket/object 查看、上传下载、同步、查找、删除和临时分享链接。

### Changed
- 应用版本从 `1.1.0-SNAPSHOT` 切到正式版 `1.1.0`，同步 Maven revision、load-tests、Helm appVersion、生产镜像 tag、OpenAPI 与 SDK 文档。
- 基础环境镜像版本对齐到同一模板入口；保留 `REDIS_IMAGE_TAG` 兼容旧命名，同时补 `VALKEY_IMAGE_TAG` 表达实际镜像。
- 版本升级脚本与 CI 对齐检查扩展为统一守护：后续 GA 发布必须同步应用版本、部署 tag、OpenAPI、SDK 文档、load-tests、基础镜像 tag 与 `CHANGELOG.md`。

---

## 2026-05-23 — 跨仓库版本引用对齐（infra/dep bump 收尾）

### Changed
- **`.github/actions/setup-build-env/action.yml`**：预拉镜像列表对齐 `postgres:16` → `postgres:17`、`minio/minio:RELEASE.2025-04-03T14-56-28Z` → `minio/minio:RELEASE.2025-09-07T16-13-09Z`
- **`batch-common/src/test/java/io/github/pinpols/batch/testing/MinIOContainer.java`**：Testcontainers MinIO image 字面量同步到 `RELEASE.2025-09-07T16-13-09Z`
- **`batch-orchestrator` / `batch-console-api` 集成测 3 处 `PostgreSQLContainer` 字面量**：`postgres:16` → `postgres:17`（`BatchDaySqlMigrationsIntegrationTest`、`LocalFlywayPlatformMigrationsIntegrationTest`、`ReadReplicaHappyPathIntegrationTest`；`SqlConsistencyIntegrationTest` 已先前对齐）
- **`docs/runbook/base-services-deployment.md`**：裸机 PG 安装提示 `brew install postgresql@16` → `postgresql@17`
- **`docs/runbook/partition-cutover-2026-05.md`**：cutover 演练 `docker run … postgres:16` → `postgres:17`
- **`docs/compliance/THIRD-PARTY-LICENSES.md`**：Okio 版本字段 `3.16.1` → `3.17.0`

### Notes
- 上游 commit：`66d074cf`（postgres 16→17 / minio bump）+ `f64be608`（okio 3.16.1→3.17.0）
- `docs/compliance/sbom.json` 本次**未重生成**：`mvn -P compliance cyclonedx:makeAggregateBom` 因 `batch-trigger` / `batch-worker-core` 的 `mockwebserver` dependency 缺 `version` 字段而 build 失败（预存遗留问题，非本次引入），手改 10 处 okio bom-ref / purl / version 不能同步 hash 字段，按 "JSON 不要手改大文件" 约束跳过，留待 sbom 单独修补 PR
- `docs/analysis/dependency-upgrade-evaluation-2026-05-23.md` 中描述 `RELEASE.2025-04-03 → 2026-Q1 release` 是当时评估记录，保留不改

---

## 2026-04-26 — 文档体系重构 + dict 自动化 + compliance 重生成

### Added
- **Diátaxis Reference dict**（`docs/dict/`）：错误码 dict（脚本生成自 `ResultCode.java`）、配置键 dict（Spring Boot configuration-processor 编译期自动生成 metadata.json）、业务术语 glossary（41 词手写上限）
- **`spring-boot-configuration-processor`** 加入根 pom annotationProcessorPaths：8 模块共 440 个 `@ConfigurationProperties` 字段编译期生成 IDE 可消费的 metadata.json；其中 5 个高频类（`BusinessDataSourceProperties` / `RetryGovernanceProperties` / `FileGovernanceProperties` / `ConsoleSecurityProperties` / `ConsoleAiProperties`）补全 javadoc → 156 个 keys 带 description
- **`scripts/codegen/gen-error-codes-dict.py`**（90 行 Python，零依赖）：解析 ResultCode 枚举生成 `docs/dict/error-codes.md`；CI（`pr-gate.yml`）加 `--check` 步骤，dict 与源不同步即 fail
- **mega 设计文档拆分剩余 3 章**：`design/tech-stack-and-principles.md`（ch.3）、`design/sla-and-quality.md`（ch.11）、`design/multi-tenant-and-security.md`（ch.15）—— 按当前代码状态校正（角色名 / 表名 / 类名以代码为准）
- **`archive/design/mega-chapter-map.md`**：mega 8005 行 20 章 → 当前位置精确导航表（含 ✅ 12 / 🟢 3 / 🟡 5 状态标注）
- **9 个子目录 README**：每个子目录有编号化文件清单 + 角色路径 + 与其他目录分工，按 Diátaxis 分类
- **`docs/README.md`**：顶层文档导航入口

### Changed
- **mega 设计文档清空为 redirect 存根**：`archive/design/system-design-2026-03-21.md` 8005 → 372 行（−95%），保留全部 20 章 + 95 子节标题供 `§X.Y` 兼容历史链接，正文移到主干 / git 历史
- **docs/ 100+ 文档按 5 个 Stage 整理**（A 临时产物清理 / B 重复对合并 / C 8000 行 mega 归档 / D scalability 历史归档 / E archive 命名规范）：128 → 100 .md
- **docs/ 全英文命名 + .md 强制后缀**：`改造分类.md` → `rework-classification.md`；6 文件 10 处引用同步
- **docs/ 根 5 → 4 文件**：`AGENT.md` → `agent-baseline.md`、`ci.md` → `runbook/ci.md`、`code-patterns.md` 合并到 `coding-conventions.md` §22
- **`docs/compliance/THIRD-PARTY-LICENSES.md`** 重生成：版本字段从 `1.0.0-SNAPSHOT` 改为 `${revision}`，加 `spring-boot-configuration-processor` + Netty HashedWheelTimer 条目，generated 日期 → `2026-04-26`
- **`docs/compliance/sbom.json`** 重生成：30 → 266 个 transitive 依赖（CycloneDX v1.6 完整版）
- **`design/tech-stack-and-principles.md` §3.4** 交付物表：NOTICE 状态 🟡待补 → ✅
- **`load-test-report-2026-04-25.md`** → `load-test-report.md`（去日期后缀，遵循"主干无日期 / archive 带日期"约定）

### Removed
- **`.flattened-pom.xml`** 从 git 跟踪移除并 gitignore（flatten-maven-plugin build artifact，每次 install 重生成）
- **本地 `.env`**（与 `.env.local` byte-identical 的误生成副本）+ 5 个 `.DS_Store`（macOS Finder 临时文件，已 gitignore 但本地残留）
- **15 个 deprecated 文档**（v1/v2/v3 老版本、临时跑测产物、对话日志归档）从主干进 archive/
- **`qodana.yaml`** 删除：JetBrains Qodana 静态分析配置，CI / Makefile / scripts 0 引用，已被 PMD + Spotless + spring-boot-configuration-processor 覆盖

### Notes
- 文档体系按业界中团队成熟度（Diátaxis + ADR + 自动 dict）落地，13 个 commit 覆盖
- 维护策略：dict 自动生成（CI 校验同步）+ 手写 glossary（≤50 词上限）+ design 散文档（Diátaxis Explanation 类）
- 业界对照：mega 不反更新（Linux / Spring / Kubernetes 都走过这条路），存根模式保留 anchor 兼容历史链接

---

## 2026-04-18 — 版本控制 & 构建脚本

### Changed
- 所有 pom 改用 Maven CI-friendly `${revision}`，根 pom 单点定义（默认 `1.0.0`），11 个子模块（`batch-common` / `batch-trigger` / `batch-orchestrator` / `batch-worker-*` / `batch-console-api` / `batch-e2e-tests` / `security-scan` / `load-tests`）统一引用
- 根 pom 装配 `flatten-maven-plugin`，install/deploy 前展开 `${revision}`，消除下游消费者拿到未解析 `${revision}` 的风险
- `scripts/local/build-apps.sh` 和 `docker/Dockerfile.app`：`-Dmaven.test.skip=true` → `-DskipTests`。前者会阻断 `batch-common:tests` 依赖链，后者只跳过测试执行、保留 test-jar 产物
- `scripts/ci/security-scan.sh` 硬编码 jar 路径改为 glob 匹配 `security-scan-*.jar`

### Fixed
- 设计文档（`docs/design/*.md`）、运行手册（`docs/runbook/security-scan.md`）、模块 README（`security-scan/README.md`）、CLAUDE.md 中 `1.0.0-SNAPSHOT` 残留引用一并更新

### Notes
- `load-tests` 是独立模块（未纳入根 reactor），`${revision}` 无法继承；版本使用字面量，需与根版本手工同步

---

## 2026-04-17 — 枚举契约统一（DictEnum）

### Added
- **`DictEnum` 接口**（`batch-common/.../enums/DictEnum.java`）：统一 60 个业务枚举的 `code()` / `label()` 契约，附带静态工具 `fromCode(Class, String)` / `codes(Class)` / `labels(Class)`
- **`ConsoleMetaQueryService.buildEnums` 扩充 20 个枚举 key**：`priorityLevel` / `aiPromptDecision` / `checksumType` / `workflowJoinMode` / `fileDispatchRunStatus` / `fileDispatchStatus` / `fileReceiptStatus` / `pipelineRunStatus` / `compensationStatus` / `retryScheduleStatus` / `encryptType` / `compressType` / `errorSinkType` / `priorityBand` / `stepInstanceStatus` / `runMode` / `skipAction` / `workflowNodeRunStatus` / `deadLetterReplayStatus` / `skipThresholdMode`
- **守护测试 `ConsoleMetaEnumRegistrationTest`**：扫描枚举包，强制每个公共枚举二选一 —— 要么在 `REGISTRATIONS` 注册暴露给前端，要么加入 `EXCLUDED` 白名单并注明原因；同时断言所有公共枚举实现 `DictEnum`

### Changed
- 60 个枚举迁移到 Lombok 样式（`@RequiredArgsConstructor` + `@Accessors(fluent = true)` + `@Getter`），删除手写构造器和 `code()` / `label()` accessor；每个枚举从 20+ 行样板降到 ~10 行
- `ErrorSinkType` / `SkipAction` / `SkipThresholdMode` 的 bean 风格 `getCode()` / `getLabel()` 统一为 `code()` / `label()`
- `FileDispatchStatus` / `FileReceiptStatus` / `PipelineRunStatus` 三个裸枚举补齐 `code` / `label` 字段
- 5 个有特殊语义的 `fromCode`（`CatchUpPolicyType` / `WorkflowJoinMode` / `ShardStrategy` / `RunMode` / `FileStatus`）改为 `DictEnum.fromCode` 的薄包装，保留各自的"抛异常 / 默认值 / Optional"行为
- `ConsoleMetaQueryService.EnumReg` record 精简到 `(key, enumClass)` 两字段
- OpenAPI `CommonResponseMetaEnums` schema 同步补齐 20 个新 key
- `CLAUDE.md` §领域数据字典 重写，追加 Lombok 样板 + 工具说明；新增 §版本管理、§变更记录两节

### Removed
- 35 个枚举各自的 `public static Set<String> codes()` 副本
- 3 个枚举各自的 `fromCode`（`ErrorSinkType` / `SkipAction` / `SkipThresholdMode`）

---

## 2026-04-16 — v6：安全防御 & 租户配置合包

### Added
- **`DnsResolveGuard` / `BlockedAddressException`**：阻断 webhook / callback 向内网地址（含 metadata 服务、loopback、link-local）外发，防 SSRF
- **`CallbackUrlValidator`**：校验 webhook URL 仅允许 http(s) 协议 + 非黑名单 IP
- **`ConfigPackageExcelValidator` / `ConfigPackageExcelWorkbookWriter`**：租户配置包 Excel 合包导入（8-Sheet：job_definition / file_channel / alert_routing / pipeline / pipeline_step / workflow_definition / workflow_node / workflow_edge）
- `GET /api/console/config/tenant-package/excel/export`：导出当前租户全量配置包
- `POST /api/console/config/tenant-package/excel/upload` / `preview/{token}` / `apply/{token}`：单事务导入 + 跨 Sheet 依赖校验
- `RequiresNewTransactionBoundaryIntegrationTest`：`@Transactional(REQUIRES_NEW)` 隔离语义的端到端验证
- `DispatchChannelHealthService` / `HttpDispatchChannelAdapter`：分发通道健康检查 + HTTP 通道适配器

### Changed
- 6 个 Excel 控制器（file-templates / file-channels / alert-routings / batch-windows / quota-policies / resource-queues）的 upload/apply 请求响应统一为共享 `ExcelUploadResponse` / `ExcelApplyResponse` / `ExcelApplyRequest` / `ExcelRowIssue`
- `ExcelApplyResponse` 新增 `skippedRows` 字段，`ExcelPreviewResponse` 新增 `previewWorkbookUrl`
- `ConsoleJobApplicationService` 拆分为 `ConsoleJobApprovalService` / `ConsoleJobRecoveryService` / `ConsoleJobTriggerService` 三个独立服务（`ConsoleJobOpsSupport` 持共享逻辑）
- 软删除改 `PATCH`：`POST /{id}/toggle?enabled=` 及 `POST /batch-toggle` 统一改为 `PATCH /{id}` / `PATCH /batch`，适用于 job-definitions / workflow-definitions / file-channels / file-templates
- `POST /api/console/files/delete` → `DELETE /api/console/files/{fileId}`
- list 查询 `enabled` 参数默认 `true`：四类列表接口不传 `enabled` 时只返回已启用记录
- 10 个独立 Excel 导入 Controller 的 upload / preview / apply 端点标注 `deprecated`，推荐改用 `/api/console/config/tenant-package/excel` 系列

### Added (Meta)
- `GET /api/console/meta/enums` 新增 3 个 key：`operationType` / `operationResult` / `fileStatus`
- `GET /api/console/meta/enums` 再新增 6 个 key：`taskStatus` / `partitionStatus` / `workflowRunStatus` / `approvalType` / `outboxPublishStatus` / `aiPromptCategory`；OpenAPI schema 由 `CommonResponseObject` 改为精确的 `CommonResponseMetaEnums`
- `GET /api/console/meta/biz-types?tenantId=`：按租户动态返回 `file_record` 中 distinct `biz_type`，用于文件列表下拉筛选
- `POST /api/console/tenants/batch`：批量建租户 + `initConfigFrom` 复制源租户全部配置；批量密码最低长度 12 位

### Removed
- `DELETE job-definitions/{id}` / `workflow-definitions/{id}` / `file-channels/{id}` / `file-templates/{id}`（软删除统一走 PATCH）
- `DELETE /api/console/users/{id}`（账号不可物理删除，走 disable / tenant suspend）
- `POST /api/console/users`（独立创建账号，改由建租户接口统一创建）

---

## 2026-04-15 — v5：Excel 应用服务批量重构

### Changed
- 10+ Excel Application Service 迁移到 `AbstractSingleSheetExcelService` 抽象基类，消除 upload/preview/apply 三段重复代码
- 涉及：`AlertRouting` / `BatchWindow` / `BusinessCalendar` / `FileChannel` / `FileTemplate` / `JobDefinition` / `Notification` / `PipelineDefinition` / `ResourceQueue` / `TenantQuotaPolicy` / `Workflow`
- `WorkflowRunEntity` / `BatchSecurityProperties` / `Stateful` 持久化基类细化

---

## 2026-04-11 ～ 04-12 — Spotless 格式化 & PMD 规则集 & 大规模清理

### Changed
- 全项目 Spotless 格式化 + PMD 规则集（`build/pmd-ruleset.xml`）入库，CI 阶段强制检查
- 大规模重排（单次提交 1466 文件 / 87k 行变更）：代码风格统一、注释补齐
- `org.springframework.boot:spring-boot-starter-parent` 升级链维护
- `.env.local` / `.env.test` / `.env.prod` 模板分离

---

## 2026-04-08 ～ 04-11 — 系统优化（36 次提交，跨 4 天）

### Added
- 通知订阅中心（`ConsoleNotificationController` + V49 迁移）：channel CRUD（EMAIL / DINGTALK / WECOM / WEBHOOK / SMS）、subscription rule CRUD、delivery logs、测试通知
- 配置审批流（`ConsoleConfigApprovalController`）：submit → approve / reject 状态机 + 审计轨迹
- 跨环境配置同步（`ConsoleConfigSyncController`）：export / preview / import 配置包 + 同步日志
- `ConsoleOutboxOpsController`：stats / cleanup / republish
- SLA 报告端点 `GET /api/console/dashboard/sla-report`
- 文件错误记录 CSV 导出 `GET /api/console/files/{fileId}/errors/export`
- Job 定义克隆 `POST /api/console/job-definitions/{id}/clone`
- Excel 导入跨引用校验（queue / calendar / window code 存在性检查）
- OpenAPI SDK 生成 profile（`openapi-codegen`）+ `scripts/ci/generate-sdk.sh`
- `.github/actions/setup-build-env`：复用构建环境（JDK / Maven 缓存）的 composite action

### Changed
- `62bc3fb` — 全仓 FQN → import 替换（39 文件）：禁止 `java.util.concurrent.TimeUnit.SECONDS` 这类写法
- PMD `ExcessiveParameterList` / `NcssCount` / `AvoidDuplicateLiterals` 违规基线治理

---

## 2026-04-05 ～ 04-07 — SSE & 观测性 & Docker

### Added
- Console 实时推送体系（`ConsoleRealtimeProperties` / `ConsoleRealtimeRedisPubSubConsumer` / `ConsoleOpsSummaryRealtimeStream` / `ConsoleWebhookDomainEventListener`）基于 Redis Pub/Sub + SSE
- `ConsoleRealtimeReplayStore`：重连后补发最近 N 条事件
- `BatchConsoleApiApplication` 装配 + Docker Compose 重组（`scripts/docker/`）
- Makefile 入口 `dev-restart` / `dev-up` / `dev-down`

### Changed
- `DefaultConsoleAlertApplicationService` / `DefaultConsoleFileTemplateApplicationService` 拆 Excel 专职服务
- `batch-defaults.yml` 新增 SSE / realtime 相关配置
- README 重组，指向测试和 API 文档索引

### Fixed
- 2026-04-06 ～ 04-07 "测试类修复" 波次：7 个测试类断言修正 + 环境变量 / fixture 稳定性

---

## 2026-04-01 ～ 04-03 — API & 参数重构

### Changed
- 179 文件 / 11648 行变更（一次提交）：Query record 工厂方法规约落地（`ofTenant` / `ofDefinition` 等），禁止调用处写 `null` 参数
- `BatchKmsProperties` / `BatchSchedulingProperties` / `BatchSecurityProperties` 等 config properties 拆分
- `CLAUDE.md` 首版：方法参数约束（≤ 6）、FQN 禁令、分支消除规则、API 文档同步约束

---

## 2026-03-29 ～ 03-31 — 调度引擎 & 补偿 & 重试治理

### Added
- `DefaultScheduleForwarder` / `ScheduleForwarder` / `ScheduleForwarderResult`：调度决策 → Forwarder 链路
- `TokenBucketRateLimiter` + `TenantActionRateLimiter`：租户级动作限流
- `DefaultCompensationService` / `DefaultRetryGovernanceService`：补偿 & 重试治理主服务
- E2E 测试矩阵：`DedupJobLaunchE2eIT` / `DispatchPipelineE2eIT` / `ExportContentVerificationE2eIT` / `ImportFailureE2eIT` / `MultiTenantConcurrentE2eIT` / `OutboxForwarderE2eIT` / `WorkerDrainE2eIT` 等 10+

### Changed
- Flyway 迁移整理（29 文件）：多版本合并 + 测试夹具 SQL 收敛到 `PlatformTestdataSql`
- 注释 / 命名统一整理：`RunMode`、`BatchDeadLetterMessage`、`BatchRetryMessage`、`WorkerRouteModel`、`StructuredLogField` 等

### Fixed
- M-10 `CatchUpPolicyType.fromCode` 未知 code 抛 `IllegalArgumentException`（保留 `fromCodeOrDefault` 兼容容错场景）
- L-3 `WorkflowJoinMode.fromCode` 同语义对齐

---

## 2026-03-28 — Security-scan & GitHub CI

### Added
- **`security-scan` 独立模块**：`SecurityScanApplication` + `ScanMode` / `ScanStep` / `ScanReport` / `ExternalCommand` / `ProcessCommandExecutor`，调度现有扫描工具（不自研扫描器）
- `scripts/ci/security-scan.sh` + `docs/runbook/security-scan.md`
- GitHub Actions workflows：`pr-gate.yml` / `staging-gate.yml` / `full-ci-gate.yml`
- 127 文件 / 8375 行 `test& docs` 大批量测试和文档补齐

---

## 2026-03-26 ～ 03-27 — 导出插件体系 & SQL 任务

### Added
- `ExportDataPlugin` / `WorkerPluginIds` / `ExportDataPluginRegistry`：导出数据插件化
- `SettlementExportDataPlugin`（清算导出内置）+ `SqlTemplateExportDataPlugin`（SQL 模板导出）
- `SqlTemplateExportSpec` / `SqlTemplateExportSqlValidator`：SQL 模板校验
- E2E 测试夹具 `export-template-config-seed.sql` / `import-template-config-seed.sql`
- 6 个 SQL 结构任务（sql & task1→6）：schema 调整 + 索引

---

## 2026-03-25 — P1-P3：审批台账 & 优雅启停 & 设计模式

### Added
- **审批台账产品化**：`ApprovalCommandEntity` / `ApprovalCommandQuery` / `ApprovalCommandMapper` + `ConsoleOpsController` 审批列表 / 详情接口
- **优雅启停**：Spring `SmartLifecycle` + `DisposableBean` 双保险，保证 Worker drain → Orchestrator stop → Trigger stop 的有序退出
- `ContentMaskingUtils`：错误记录 / 错误文件 / 日志的敏感内容脱敏
- `@ValidBizDate` / `@ValidTenantId` JSR-380 自定义校验
- Query / Request record 全家桶：`DeadLetterQueryRequest` / `OutboxDeliveryLogQueryRequest` / `JobDefinitionQueryRequest` / `CompensateRequest` / `CompensationCommandRequest` 等

---

## 2026-03-24 — P0 & E2E 测试框架

### Added
- `ApprovalWorkflowIntegrationTest` / `MultiTenantIsolationIntegrationTest` / `OutboxPublishIntegrationTest` / `SchedulingDecisionLaunchIntegrationTest`：Orchestrator 核心集成测试
- `OutboxForwarderRetryE2eIT`：Outbox → Kafka 重试全链路
- `StructuredLogField`：结构化日志字段（traceId / spanId / tenantId / jobInstanceId）
- `AbstractPipelineStepExecutionAdapter` / `AbstractTaskConsumer` / `StageExecutionContext`：Worker 执行阶段抽象
- `AbstractIntegrationTest` + `OrchestratorWireMockSupport`：集成测试基础设施
- Platform init SQL + test application.yml

---

## 2026-03-23 — 结构重组

### Changed
- 139 文件重组：module 间 package 边界校正
- 枚举补齐：`AlertStatus` / `ApprovalCommandStatus` / `CompensationCommandStatus` / `FileDispatchRunStatus` / `FileDispatchStatus` / `FileReceiptStatus` / `PipelineRunStatus` / `StepInstanceStatus`
- `BusinessDataSourceProperties` / `MinioStorageProperties` / `OrchestratorApiPaths`

### Fixed
- 3 个端到端测试 bug：事务边界、数据隔离、异步等待

---

## 2026-03-22 — v1-v4：核心业务骨架

### Added
- **Console 应用服务族**：`ConsoleAiApplicationService` / `ConsoleConfigApplicationService` / `ConsoleFileApplicationService` / `ConsoleJobApplicationService` / `ConsoleQueryApplicationService` 及各自 `Default*` 实现（281 文件，10911 行）
- 枚举补齐首轮：`AiPromptCategory` / `AiPromptDecision` / `ConfigLifecycleStatus` / `ErrorSinkType` / `ShardStrategy` / `SkipAction` / `SkipThresholdMode`
- `META-INF/spring-configuration-metadata.json`：IDE 配置提示
- 测试补齐（`add tests` 波次）：单元测试 + 部分集成测试骨架

---

## 2026-03-21 — 项目初始化（init）

### Added
- 多模块 Maven 骨架：`batch-common` / `batch-trigger` / `batch-orchestrator` / `batch-worker-core` / `batch-worker-import` / `batch-worker-export` / `batch-worker-dispatch` / `batch-console-api` / `batch-e2e-tests`
- 通用基础设施：
  - `context` — `AuditContext` / `ExecutionContext` / `TenantContext` / `TraceContext`
  - `dto` — `CommonResponse` / `LaunchRequest` / `LaunchResponse` / `ResponseMeta` / `TaskMessage`
  - `logging` — 结构化日志锚点
  - `plugin` — 插件接口族
  - `constants` — `BatchStatusConstants` / `CommonConstants`
- 技术选型：Spring Boot 4.0.3 + MyBatis + Flyway + Kafka + Redis + MinIO（首版骨架曾含 Spring Data JDBC，已于后续版本移除，现全平台统一 MyBatis）
- 架构主链：`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`（文档化）
- `AGENT.md` + `.env.example` + `.gitignore`
- 477 文件 / 30430 行，奠定整体形状

---

## Notes

- 版本通过 Maven CI-friendly `${revision}` 在根 pom 统一控制（默认 `1.0.0`）。构建期覆盖：`mvn -Drevision=X.Y.Z ...`
- 更细颗粒度的 API 层变更详见 `docs/api/console-api-protocol.md` 的 Changelog 表
- 更细颗粒度的编码规范 / 架构变化详见 `CLAUDE.md` 的 §变更记录
