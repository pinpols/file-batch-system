# 项目工程成熟度评估

> 评估时间：2026-04-26  
> 评估范围：file-batch-system 全 8 个模块 + 配套基础设施 + 文档/治理体系  
> 评估方法：扫量化指标 + 9 维度逐项 spot check + 对照行业 L1–L5 成熟度模型

---

## TL;DR

**总评：L4-（接近 L4，少量维度仍是 L3+）— "生产成熟期早期"**

像一个 1-3 年历史、3-10 人团队、跑过几次真实 release、踩过 incident 也补过 runbook 的中型业务平台。架构骨架 + 治理框架 + 文档体系都已就位，差的是规模化压测验证 + 测试覆盖率提升 + 几处历史包袱清理。

---

## 1. 量化指标快照

| 指标 | 数值 | 说明 |
|---|---|---|
| 主代码（Java） | 1293 文件 / 91,370 行 | 8 模块 |
| 测试代码 | 361 文件 / 319 `*Test.java` + `*IT.java` | unit + IT + e2e 三层 |
| **测试 / 主代码 文件比** | **28%** | 行业生产成熟项目通常 50%+ |
| Flyway 迁移 | 70 个 V*.sql | schema 演进成熟 |
| 三方依赖 | 266 个 component（CycloneDX SBOM） | 0 高危 license |
| 文档 | 113 markdown | 13 architecture + 23 runbook + others |
| GitHub Actions | 4 workflow | pr-gate / full-ci / staging / capacity 分层 |
| Changelog 活跃天数 | 最近 3 天连续登记 | 规范条款自演进机制运转中 |
| 模块数 | 8 | batch-common / -trigger / -orchestrator / -worker-{core,import,export,dispatch} / -console-api |

---

## 2. 成熟度量表（5 级）

| Level | 描述 |
|---|---|
| **L1 · 原型** | 单人玩具，无测试、无文档、无 CI |
| **L2 · 试运行 / Beta** | 有基本测试和文档，可跑通主链路，但缺治理 |
| **L3 · 生产就绪基础（小规模）** | 完整状态机 + 重试 + 监控；适合单机或小集群部署 |
| **L4 · 生产成熟（中规模 + SRE 实践）** | fail-open + DLQ + observability 三件套 + runbook + 灰度发布 |
| **L5 · 平台级（大规模 + 多租户 + 完整治理）** | 分库分表 + 跨 AZ active-active + SLO 量化 + 万级租户实测 |

---

## 3. 9 维度详评

### 3.1 架构成熟度 — **L4** ✅

**强项**：
- 硬约束清晰且强制（Outbox 同事务 / Orchestrator 唯一状态主机 / CLAIM 流程不可绕过 / MyBatis vs JDBC 分层）
- 双轨实现并存模式：Quartz 默认 + Wheel 可选 + `QuartzPauseWhenWheelEnabledCustomizer` 互斥控制器（大型重构灰度模式典范）
- Workflow DAG + Pipeline Stage 二维正交编排
- 刚做了模块边界审计 + 修复 console-api 越权写 outbox_event

**缺口**：
- Phase 3（分库分表）还没动
- Wheel 默认未切（代码 ready 但灰度未推进）

### 3.2 代码工程质量 — **L4** ✅

**强项**：
- `CLAUDE.md` 强制规范（参数 ≤6 / FQN 禁用 / DictEnum / SpecHandler / 分支消除规则）
- pre-commit hook 自动 spotless apply
- PMD 基线 + AvoidDuplicateLiterals 守护
- CI-friendly 版本策略（`${revision}` 单点）
- 编码规范条款变更必须登记 changelog（自演进机制）

**缺口**：
- PMD 基线快照（`docs/pmd-violations.md`）仍有 ExcessiveParameterList × 11 / NcssCount × 5 / AvoidDuplicateLiterals × 283 未清
- ExcessiveParameterList × 11 实质违反 CLAUDE.md "参数 ≤6" 硬约束，应优先清

### 3.3 测试 — **L3+** ⚠️

**强项**：
- 三层覆盖：unit + IT + e2e
- testcontainer PG / Kafka / Redis / MinIO 真实基础设施
- 守护测试机制（`ConsoleMetaEnumRegistrationTest` 强制枚举登记）
- 新加的 `ReadReplicaWiringIntegrationTest` + `ReadReplicaHappyPathIntegrationTest` 完整覆盖 prod 默认 + happy path + quarantine recovery

**缺口**：
- **test:main 文件比仅 28%**（行业生产成熟通常 50%+）
- `application-test.yml` 多处 override prod 默认值（read-replica / worker-cache / mq-routing.mode 三处已知），prod 路径在 IT 层覆盖率打折扣
- 缺统一的覆盖率门控（jacoco 已就位但未在 pr-gate 强制 minCoverage 阈值）

### 3.4 运行时可靠性 — **L4** ✅

**强项**：
- fail-open 多处兜底：read-replica quarantine + worker-cache Redis 异常退 DB + quota Redis 异常放行
- Outbox stale PUBLISHING 重置（防 JVM 崩溃事件卡死）
- 熔断器（`OutboxPublishCircuitBreaker`）三态
- 优雅停机 `isDraining()` 短路（防 Lettuce 关闭后还抢 Redis 锁）
- retry 三级（NONE / FIXED / EXPONENTIAL）+ DLQ 双轨（Kafka 隔离 + DB 落盘）+ replay 状态机
- 分布式锁 ShedLock JDBC + Redis 双实现 + 多分片 outbox poller

**缺口**：
- 海量场景（>1000 万/天）承载力还在 `scalability-assessment.md` 评估阶段，实测未做
- `load-tests` 模块存在但产出报告未归档

### 3.5 可观测性 — **L4** ✅

**强项**：
- 完整 OTel 栈：Tempo（traces）+ Loki（logs）+ Prometheus（metrics）+ Jaeger（trace UI 兼容）+ Grafana + Alertmanager
- traceId 跨日志关联，metric exemplar 跳 trace
- 业务指标（outbox lag / replica failover.count / consumer lag）+ 基础设施指标都有 Grafana dashboard
- 一站排障路径文档化（`observability-stack.md`）

**缺口**：
- SLO / SLI 定义未见独立文档
- 告警阈值散在 `alertmanager-batch-template.yml` 里，没有"业务 SLO → 告警阈值"的双向映射文档

### 3.6 安全合规 — **L4** ✅

**强项**：
- bypass-mode 全局开关 + prod profile @PostConstruct 守护拒绝
- KMS / 加解密 / 审计 / 限流 / 审批 / 渠道校验多层安全链
- 完整 license 评估（266 deps / 0 高危 / SBOM CycloneDX 1.6 / 红线扫描脚本）
- `license-risk-assessment.md` + `THIRD-PARTY-LICENSES.md` + `sbom.json` 三件套
- security-scan.md runbook

**缺口**：
- NOTICE 文件极简（指针式），对外发版严格法务可能要求嵌入完整 attribution
- BSD-3-Clause / EPL-2.0 全文未原文嵌入（admin gap）

### 3.7 持续交付 / CI — **L4** ✅

**强项**：
- 4 个 GitHub Actions workflow 分层（pr-gate / full-ci-gate / staging-gate / capacity-gate）
- `feature-switches.md` 6 个开关 + 风险等级 + 回滚指引
- 关键大改有专属灰度 runbook：`mq-topic-routing-rollout.md` / `wheel-scheduler-rollout.md` / `read-replica.md`

**缺口**：
- 未看到 release notes / version-cut 流程文档
- artifact 发布流程（Docker Hub / Maven Central）未文档化

### 3.8 运维就绪 — **L4+** ✅✅

**强项**：
- 23 个 runbook 覆盖大多数运维场景：incident-response / daily-inspection / rolling-upgrade-workers / pg-table-partitioning / minio-lifecycle / orchestrator-statefulset-migration 等
- feature-switches 索引 + fail-open 速查 + 部署形态建议矩阵
- autoscaling + ha-elastic-scaling runbook 提供扩缩容指引

**缺口**：
- 未见 capacity planning 真实压测数据归档（load-tests 模块在但产出报告位置不明）
- 没有 chaos engineering / DR drill 记录

### 3.9 文档 / 协作约束 — **L5** ✅✅✅

**强项**：
- 113 markdown / 23 runbook / 13 架构文档 / API openapi yaml + protocol changelog 强制双更约束
- changelog 严格只记规范条款变化（避免文件膨胀）
- CLAUDE.md 自演进机制 — 任何规范变化必须 changelog 追加
- 每条变更 commit message 写得详细（"why" 而非 "what"）
- 文档之间互相引用 + 单点权威（如 sbom.json 是依赖权威源，THIRD-PARTY-LICENSES.md 是 curated 摘要，license-risk-assessment.md 是评估视角）

**这是项目最强的维度，达到平台级（L5）标准。**

---

## 4. 综合评级矩阵

| 维度 | 级别 | 关键动作 |
|---|---|---|
| 1. 架构成熟度 | L4 | 推进 wheel 灰度切默认 / 启动 Phase 3 分库分表评估 |
| 2. 代码工程质量 | L4 | 清 PMD 基线（特别 ExcessiveParameterList × 11） |
| 3. 测试 | **L3+** | 提升 test:main 比 → 50%+ |
| 4. 运行时可靠性 | L4 | load-tests 实测 + 报告归档 |
| 5. 可观测性 | L4 | 加 SLO/SLI 独立文档 |
| 6. 安全合规 | L4 | 嵌入完整 BSD-3 / EPL-2.0 attribution |
| 7. 持续交付 / CI | L4 | release notes 流程文档化 |
| 8. 运维就绪 | L4+ | capacity planning 真实压测数据归档 |
| 9. 文档 / 协作约束 | **L5** | 维持现状 |

**综合：L4-** （短板拉低，但骨架坚实）

---

## 5. 像 / 不像什么

**像**：
- 一个 1-3 年历史、3-10 人团队、跑过几次真实 release、踩过 incident 也补过 runbook 的中型业务平台
- 已经走出"功能开发为主"阶段，进入"治理 + 演进 + 防回归"阶段
- 类比：成熟期早期的中后端系统（如典型的 SaaS B2B 中台、内部数据平台）

**不像 L1-L2 玩具**：
- 硬约束 + 强制 changelog + license 评估 + 完整 OTel 栈，这些都是 toy 项目不会做的
- pre-commit hook + 模块边界审计 + 守护测试是团队成熟度的强信号

**不像 L5 平台级**：
- 分库分表 / 跨 AZ active-active / SLO 量化 / 真实万级租户压测都还没做
- Workflow DAG 已就位但没看到 thousands-of-DAG-instances 实测
- 没有 multi-region / multi-tenant federation 模式

---

## 6. 最值得收紧的 3 个短板（按 ROI 排序）

### P0：测试覆盖率 28% → 50%+ 路线图

**问题**：test:main 文件比 28%，且 `application-test.yml` 多处 override prod 默认值（read-replica / worker-cache / mq-routing.mode），prod 路径在 IT 层覆盖率打折扣。

**收益**：
- 真正覆盖 prod 主路径，避免 "wiring bug 启动 fail-fast 才暴露" 这种迟来的反馈
- 为后续大改（wheel 切默认 / 分库分表）提供安全网

**做法**：
- 已经为 read-replica 开了头（`ReadReplicaWiringIntegrationTest` + `ReadReplicaHappyPathIntegrationTest` 两类各跑 prod 默认 + 故障路径）
- 复制此模式到 worker-cache + mq-routing TENANT 模式 + outbox happy/failure path
- 启用 jacoco minCoverage 门控（pr-gate 加 60% 阈值，逐步提到 80%）

### P1：PMD 基线 299 条 violation 设清零截止时间

**问题**：
- ExcessiveParameterList × 11（**违反 CLAUDE.md "参数 ≤6" 硬约束**）
- NcssCount × 5（方法过长，可读性差）
- AvoidDuplicateLiterals × 283（魔法字符串散布）

**收益**：
- ExcessiveParameterList × 11 是硬约束违反，必须清；其他两类是代码质量问题
- 清零后 PMD 可启动 `failOnViolation=true` 防 regression

**做法**：
- 11 条 ExcessiveParameterList 按 CLAUDE.md "参数 ≥7 必须封装为参数对象" 改造（Command / Context / Param record）
- 283 条 AvoidDuplicateLiterals 提常量到 `batch-common/constants/`
- pr-gate 加 PMD 增量检测（只看 PR diff 行）

### P2：Wheel scheduler 切默认值

**问题**：
- Phase 1 已完成（4 周实施 + 57 IT 通过 + 4 个 bug 修复）
- 但 `application.yml: scheduler-impl: ${BATCH_TRIGGER_SCHEDULER_IMPL:quartz}` 默认仍是 quartz
- 留着会"双轨腐化"：两套 codepath 都得维护，两套 IT 都得跑

**收益**：
- 收敛维护面，减少日后 Quartz 相关运维问题（QRTZ_LOCKS 行锁瓶颈）
- 兑现 Phase 1 投入

**做法**：
- 按 `wheel-scheduler-rollout.md` 灰度路线推进
- 先在测试环境改 `BATCH_TRIGGER_SCHEDULER_IMPL=wheel`，跑 1 周观察 micrometer 指标
- 再切 staging，最后改 application.yml fallback 为 wheel + 删 Quartz 相关 codepath（一次性收尾）

---

## 7. 复评建议

| 节奏 | 触发条件 |
|---|---|
| **季度复评** | 每 3 个月跑一次本评估（重点看 test 比例 / PMD 基线 / 新增模块边界） |
| **大改前评** | Phase 3 启动前做一次（分库分表会改变多个维度的成熟度，需基线对比） |
| **incident 后** | 重大事故后 review 涉及维度的成熟度（哪个维度的缺口被命中了？） |

---

## 8. 相关文档

- 架构硬约束：[`/CLAUDE.md`](../../CLAUDE.md)
- 模块边界规则：[`/CLAUDE.md` § 模块边界](../../CLAUDE.md)
- License 评估：[`docs/compliance/license-risk-assessment.md`](../compliance/license-risk-assessment.md)
- 一张图看链路：[`docs/architecture/system-flow-overview.md`](./system-flow-overview.md)
- 海量承载评估：[`docs/architecture/scalability-assessment.md`](./scalability-assessment.md)
- 改造分类：[`docs/architecture/rework-classification.md`](./rework-classification.md)
- Wheel 替换设计：[`docs/architecture/quartz-replacement-evaluation.md`](./quartz-replacement-evaluation.md)
- 改造排期：[`docs/architecture/rework-classification.md`](./rework-classification.md) Phase 1/2/3
- Feature 开关：[`docs/runbook/feature-switches.md`](../runbook/feature-switches.md)
- Changelog（规范条款变化）：[`docs/changelog.md`](../changelog.md)
