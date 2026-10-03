# 工程成熟度路线图

本文把 Java 后端、数据库、运维、CI 和配对前端协作的长期治理目标收敛为一个当前入口。历史评估见 [`maturity-assessment.md`](./maturity-assessment.md),历史 P0/P1/P2 方案见 [`p0-p1-p2-roadmap.md`](./p0-p1-p2-roadmap.md)。本文只保留后续持续执行的工程口径。

## 成熟度目标

| 维度 | 当前口径 | 目标口径 |
|---|---|---|
| Java 服务边界 | 多模块 + bounded context + ArchUnit | 领域边界、应用端口、基础设施适配和测试证据同步收敛 |
| API 契约 | OpenAPI、协议文档和前端生成类型联动 | Controller DTO、错误码、权限、审计和前端 fixture 同步变更 |
| 数据库治理 | Flyway、SQL 安全门禁和只读 schema 巡检 | 表、索引、分区、归档、RLS、初始化卷和真实库版本形成固定巡检 |
| 批处理可靠性 | Outbox、DLQ、claim/report、重试和补偿已成体系 | 业务 SLO、积压阈值、文件到达准点率、重放成功率和告警闭环 |
| CI / 发布 | PR gate、Full Gate、staging、镜像构建分层 | 按变更域聚合失败,发布前证据可复查,main 失败可追责 |
| 运维与灾备 | Runbook 覆盖部署、HA、备份、观测 | 恢复演练、容量压测、故障注入和依赖降级成为定期证据 |

## P0: 生产准入底座

1. **接口契约链**
   - 修改 `/api/console/**` 时同步 OpenAPI、前端生成类型、权限矩阵、错误码和测试 fixture。
   - 内部 `/internal/**` 保持明确协议,不被 Console 包络规则误套。
   - TraceId、tenantId、bizDate、jobInstanceId、stepInstanceId 等定位字段应在接口、日志、审计和前端错误提示中保持一致。

2. **数据库与 SQL 治理**
   - DDL 只走 Flyway migration;运维和巡检 SQL 进入 `scripts/db/` 对应目录。
   - 表职责、索引依据、分区策略、归档策略和 RLS 边界需要文档入口。
   - 低使用索引、分区膨胀和 pending migration 只能作为候选证据;DROP 或迁移必须结合 staging/生产统计、查询计划和回滚窗口。

3. **批处理主链路可靠性**
   - Trigger → Orchestrator → Outbox → Kafka → Worker claim/report → Console 查询链路必须有真实 E2E 或集成测试证据。
   - 重试、取消、补偿、重放和审批不得只测 happy path。
   - READY / WAITING / terminal / failed terminal 等状态分类必须由领域枚举或专项测试守护。

4. **安全与租户隔离**
   - 四角色 RBAC、tenant guard、RLS、对象存储路径、密钥注入和审计字段必须 fail-closed。
   - 本地兼容开关不得静默进入生产 profile。
   - 对象存储、AI 附件、批量文件和系统文档的物理隔离边界需要在配置和文档中一致。

## P1: 大厂工程实践对齐

1. **可观测性和 SLO**
   - 将调度延迟、批次日完成率、Outbox 积压、Kafka lag、文件到达准点率、重试成功率和 Worker 心跳漂移列入 SLI。
   - 告警阈值需要能反查到业务 SLO 或容量假设,避免只有技术指标。
   - 线上排障目标是通过 TraceId / tenantId / bizDate / instanceId 在 3 分钟内定位到模块与关键状态。

2. **防误操作**
   - 高危操作默认提供 dry-run、影响范围预览、二次确认、审计记录和补偿说明。
   - 配置导入、批次日重放、批量取消、密码重置、租户级权限变更和对象清理必须有回滚或恢复边界。

3. **测试分层**
   - 单测验证纯逻辑和边界分类。
   - 集成测试验证事务、DB、Kafka、MinIO、Redis 和配置 profile。
   - E2E / staging 验证跨服务真实链路,不得用 mock 结果替代。
   - 性能结论必须带环境、负载、数据规模、指标和未覆盖项。

4. **CI 失败聚合**
   - 可独立执行的检查应汇总后再失败,一次 PR 暴露全部问题。
   - 基础环境、依赖安装、checkout 和无法继续的步骤仍应 fail-fast。
   - required job 不应因变更域跳过而丢失状态,skip 需要有明确 reason。

## P2: 长期维护成本

1. **自动化优先**
   - 能从权威源确定生成的内容才自动生成,例如 SBOM、许可证清单和 LOC 快照。
   - 需要语义判断的内容由 CI 提示漂移,不自动改写,例如 changelog、架构决策、环境 owner 和 release note。

2. **文档单一事实源**
   - 架构、设计、runbook、testing、analysis、archive 分工明确。
   - 长期规范不固化易漂移数量;带日期的报告可以记录当时证据。
   - 当前待办必须指向代码、测试、门禁或外部阻塞,不保留已完成问题的伪待办。

3. **本地可复现**
   - 本地重建基础件、后端 jar、前端 dev server、镜像和 smoke 应有固定脚本。
   - 换机器时 Java、Maven、Node、Docker、PostgreSQL、Kafka、MinIO、Valkey 等版本来源可追踪。

## 近期执行顺序

| 优先级 | 动作 | 证据 |
|---|---|---|
| P0 | 把数据库治理巡检接入 nightly 或本地 full gate 的报告路径 | schema governance 输出 + 失败 / warn 规则 |
| P0 | 固化前后端上线准入矩阵 | 后端 go-live runbook + 前端 go-live readiness |
| P1 | 梳理 SLO / SLI 与告警阈值映射 | SLO 文档 + Alertmanager/Grafana 引用 |
| P1 | 将真实全链路 smoke 输出机器可读结果 | JSON/JUnit 报告 + 失败定位 |
| P2 | 定期清理历史文档和已关闭待办 | docs 结构门禁 + changelog |

## 不做边界

- 不为了“平台化”拆数据库或拆微服务;没有容量、组织和故障隔离证据前,优先保持单体控制面边界清晰。
- 不把业务调度职责推给前端或脚本。
- 不把本地测试库状态直接当成生产 schema 结论。
- 不把历史压测、历史验收或 SKIPPED CI 当作当前通过。
