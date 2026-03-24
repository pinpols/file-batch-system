# 系统多维度改进分析（设计 / 工程 / 编码 / 业务）

## 1. 分析目标与方法

### 目标

- 对照设计文档与当前实现，识别系统中影响可维护性、稳定性、交付效率和业务可控性的关键改进点。
- 输出可落地的改进路线：不仅指出问题，还给出优先级、实施方式、验收指标和风险控制。

### 分析依据

- 设计与架构文档：
  - `README.md`
  - `docs/批量调度系统设计说明书（完整版）-20260321.md`
  - `docs/architecture/design-gap-audit.md`
  - `docs/architecture/runtime-module-communication.md`
  - `docs/architecture/runtime-default-parameters.md`
  - `docs/testing/test-strategy.md`
  - `docs/testing/e2e-three-flows-coverage.md`
  - `docs/sql/flyway/README.md`
  - `docs/sql/sql-script-usage-scenarios.md`
- 核心实现模块：
  - `batch-orchestrator`
  - `batch-worker-core`
  - `batch-worker-import`
  - `batch-worker-export`
  - `batch-worker-dispatch`
  - `batch-e2e-tests`

---

## 2. 结论总览（Executive Summary）

系统主链路已经达到“可跑通、可回归”的阶段：`触发 -> 调度 -> outbox -> Kafka -> worker -> 回报` 在 Import/Export/Dispatch 三条 E2E 上都可通过。  
但从“生产级稳定性与长期演进”视角看，仍存在四类核心短板：

- **设计层面**：模块边界在组合运行场景中仍偏脆弱（E2E 需要额外兜底配置才能装配成功）。
- **工程层面**：配置和运行循环重复较多，跨模块一致性依赖人工维护。
- **编码层面**：关键路径上的错误处理、幂等保护、并发保护策略不统一。
- **业务层面**：当前测试偏 happy path，失败分支、并发与多租户隔离验证不足，生产风险暴露能力不够。

一句话判断：**当前系统可用，但距离“高置信度、低运维负担”的生产形态，还需要一轮结构化治理。**

---

## 3. 设计维度（Architecture / Design）

## 3.1 设计与实现边界存在“隐性耦合”

### 现象

- E2E 组合应用需要额外引入 `E2ePlatformDataSourceConfiguration`、`E2ePlatformMybatisConfiguration`、Kafka consumer/producer 定制配置，才可稳定启动。
- 说明 orchestrator + 多 worker 组合时，Spring 自动装配契约不足够清晰和稳定。

### 风险

- 运行时环境变化（新增 bean、配置项）容易触发上下文冲突，导致“本地可跑、集成不稳定”。

### 建议

- 明确“平台主数据源 / 业务数据源 / worker 专属数据源”的统一命名规范和优先级规则（Primary + Qualifier 约束）。
- 对组合运行模式建立专门的“装配契约层”（例如单独配置模块），减少通过排除扫描来维持运行。

---

## 3.2 Outbox 设计意图与测试验证存在缝隙

### 现象

- 设计强调 outbox-forwarder 的轮询发布与重试治理。
- 当前 E2E 为提高可控性，主要通过 `E2eOutboxPublishSupport.publishAllPending(...)` 手动发布，未覆盖完整调度器行为。

### 风险

- 生产问题常发生在“轮询时序、重试、退避、状态切换”层，而非单次 publish。

### 建议

- 增加“真实 forwarder 轮询”E2E（不手动 publish），验证：
  - NEW -> PUBLISHED / FAILED 状态机
  - 重试上限与失败审计
  - topic 不存在、临时网络异常时的恢复

---

## 3.3 设计文档基线一致性仍有歧义

### 现象

- 不同文档中对 Flyway 版本范围、测试成熟度、部署成熟度存在表述差异。

### 风险

- 团队会在“当前到底到哪一步”上产生认知偏差，影响优先级和计划准确性。

### 建议

- 增加一份单一事实源文档（Architecture Truth / ADR Index）：
  - 当前状态（As-Is）
  - 目标状态（To-Be）
  - 已关闭差距 / 未关闭差距
  - 所有关键基线版本（DB、测试、部署）

---

## 4. 工程维度（Engineering / Delivery）

## 4.1 Worker 循环与消费者逻辑重复度高

### 现象

- Import/Export/Dispatch 的 `*WorkerLoop`、`*TaskConsumer` 结构高度相似。

### 风险

- 任何缺陷修复和改造要三处同步，回归成本高，易出现“只修一处”。

### 建议

- 在 `batch-worker-core` 下抽象共享模板：
  - 通用生命周期 loop（start/heartbeat/shutdown）
  - 通用消费执行骨架（parse -> accept -> execute -> report）
  - 各 worker 仅实现差异策略（topic、workerType、plugin）。

---

## 4.2 配置治理存在 drift 风险

### 现象

- 多模块存在大量结构相似配置（datasource/kafka/security/logging），局部命名/默认值差异较难全局审计。

### 风险

- 一处改动需要多处同步，线上环境一致性与排障复杂度上升。

### 建议

- 引入共享配置基线（core defaults + module overlay）。
- 建立配置契约测试（启动时验证关键配置键/bean 必存在且命名一致）。

---

## 4.3 SQL 资产治理已成体系但仍需“自动化一致性守卫”

### 现象

- 已有 Flyway、test seed、docker init 场景文档，但实际运行中仍出现过约束/表结构与测试 SQL 语义不匹配问题。

### 风险

- 迁移脚本和测试数据脚本演进不同步时，E2E 会出现“非业务失败”。

### 建议

- 增加 SQL 一致性 CI 检查：
  - 核心表唯一约束存在性检测
  - 关键 `ON CONFLICT` 语句兼容性检测
  - migration + seed 在空库一键重放验证

---

## 5. 编码维度（Code Quality / Reliability）

## 5.1 错误处理策略需要统一化（尤其跨 worker）

### 现象

- 导入/导出/分发执行器在“step 异常处理、日志粒度、失败码标准化”上仍有不一致。

### 风险

- 同类问题在不同 worker 中表现不一致，影响定位和自动化恢复策略。

### 建议

- 统一 stage executor 的异常处理契约：
  - 标准失败码
  - 必填上下文日志字段（tenantId、jobInstanceId、taskId、stage、workerId）
  - 一致的 step-run finish 语义

---

## 5.2 并发/幂等保护点需加强数据库原子语义

### 现象

- 部分流程仍以“应用层先查后改”保障状态合法性，数据库层 compare-and-set 保护不足。

### 风险

- 并发竞争下可能出现状态跳变或重复处理。

### 建议

- 对关键状态更新引入原子条件更新（`where status = expected`）。
- 对关键定义创建路径引入原子 upsert（避免 select-then-insert 竞争）。

---

## 5.3 外部依赖调用韧性策略不足

### 现象

- worker -> orchestrator HTTP 调用目前重试/超时/断路策略偏薄。

### 风险

- 网络抖动时，执行已成功但 report 失败，容易造成重入与误判失败。

### 建议

- 标准化外部调用韧性层：
  - connect/read timeout
  - 幂等 report + 有界重试
  - 错误分类（可重试 / 不可重试）
  - 失败指标与告警

---

## 6. 业务维度（Business Correctness / Operability）

## 6.1 Happy path 覆盖够，失败与恢复覆盖不足

### 现象

- 三条 E2E 已验证主链路成功，但 failure/retry/compensation 分支覆盖不足。

### 风险

- 真正生产事故通常发生在异常流，不在成功流。

### 建议

- 每条链路补至少 2 类失败场景：
  - 业务数据不合法
  - 外部依赖失败（Kafka/DB/Channel）
- 校验不仅看 `task_status`，还要看：
  - 错误码
  - 重试次数
  - dead-letter / audit / receipt 记录

---

## 6.2 多租户与公平性能力缺少强验证

### 现象

- 当前 E2E 以单租户 `t1` 为主。

### 风险

- 租户隔离、配额公平、抢占策略等核心业务目标缺少回归护栏。

### 建议

- 新增多租户并行 E2E：
  - tenant A/B 同时触发
  - 校验数据隔离、队列公平、无串租户污染

---

## 6.3 结果内容校验深度不足

### 现象

- 当前导出/分发更多断言状态成功，内容级校验较浅。

### 风险

- “状态成功但产物错误”可能漏检。

### 建议

- Export：增加导出文件内容、字段映射、行数、金额汇总一致性断言。
- Dispatch：增加目标产物存在性、内容摘要、回执一致性断言。

---

## 7. 优先级路线图（建议）

## P0（1~2 周，稳态收敛）

- 统一 worker 生命周期/consumer 模板，减少重复实现。
- 统一 stage 异常处理契约和日志字段规范。
- 增加 outbox-forwarder 真轮询 E2E。
- 增加关键 SQL 原子更新保护（状态 compare-and-set）。

## P1（2~4 周，风险前移）

- 补 failure/retry/compensation E2E 套件。
- 补多租户并发 E2E 套件。
- 引入 HTTP 调用韧性策略（timeout/retry/circuit）。
- 建立 SQL 一致性 CI 检查。

## P2（4~8 周，工程化升级）

- 配置基线模块化（shared defaults + overlay）。
- 架构真相文档与 ADR 体系落地。
- 导出/分发内容级验收标准化（可观测 + 可追溯）。

---

## 8. 验收指标（可量化）

- **稳定性**
  - 三条主链路 E2E 持续 30 次通过率 >= 99%
  - outbox-forwarder 异常恢复 E2E 通过率 >= 95%
- **质量**
  - failure-path E2E 覆盖率（按关键场景清单）>= 80%
  - 多租户并发回归套件纳入 CI 每日运行
- **工程效率**
  - worker 侧重复代码行数减少 >= 30%
  - 跨模块配置 key 差异告警为 0
- **业务可靠性**
  - 关键业务断言从“状态”扩展到“状态 + 产物内容 + 审计一致性”

---

## 9. 立即可执行的下一步

- 先从 P0 启动一个“小闭环”：
  1) 新增 `OutboxForwarderE2eIT`（真实轮询）  
  2) 为 Import/Export/Dispatch 各新增 1 个失败场景 E2E  
  3) 统一 stage 异常处理模板到 worker-core  

这样可以在最短周期内，把系统从“主链路可跑”提升到“异常流可控、回归可依赖”。
