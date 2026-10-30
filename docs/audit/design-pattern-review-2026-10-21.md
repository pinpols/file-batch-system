# 开发分支设计模式审查（2026-10-21）

## 范围与结论

- 分支：`feature/workflow-daily-integration`；HEAD `ee01e97515b04fd167ed17977535ec5cab8572f8`。审查对象包含当前工作区改动；HEAD 并不包含所有正在审查的改动。
- 使用 `project-engineering-review`，并按需参考 `module-config-boundaries` 和 `code-review-and-gates`。
- 本轮聚焦本次新增/改动的 Handler、Registry、Policy、Adapter、Resolver、Outbox 及其领域调用方；没有逐个评估全仓所有既有模式，也没有评估配对 Console 前端。
- 登记 2 项 P3 设计改进：配置初始化处理器仍集中多个领域职责；Trigger 核心服务聚合了多类独立用例。未发现足够证据将其它被抽查的模式定性为误用；这是有范围边界的审查结论，不代表全仓无设计问题。

## 发现

### DP-01 / P3：配置处理器的职责没有随“拆分”收敛

**位置**：[TenantConfigInitApplyHandlers.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitApplyHandlers.java) 第 67–105、258–705、729–965 行。

该类注释把改动称为 God Class 拆分，但当前文件有 965 行、8 个注入依赖及 9 个配置类别的 apply 入口。除共同的“查找→跳过/更新/创建”模板外，类中仍共同维护 Job 依赖排序/环检测和监控策略（约第 258–688 行）、Workflow 图替换/执行定义版本快照（约第 692–884 行），以及文件通道/模板、资源队列、业务窗口/日历、租户配额、告警路由的配置 upsert（约第 886–964 行）。这些变化原因和数据写入契约并不相同。

`SpecHandler`/`applySpecs` 为多个类别共用逐项事务、查找和结果统计，这个 Template/Strategy 组合有实际重复消除价值。问题是专用领域行为仍集中在同一个 Spring 组件；继续增加配置类型或演进 Job/Workflow 规则，会让该类的依赖、测试替身及修改冲突继续增长。仅把旧大类代码移动到一个新大类名下，没有建立领域处理边界。

**类型**：可维护性/职责边界问题，不是已证实的运行时故障。

**建议**：在后续配置初始化改造中，优先将 Job 依赖和监控逻辑、Workflow 图与版本写入、运维配置分类到有明确所有权的处理组件；仅当各自仍有重复流程时共享小型逐项 Apply 模板。保留调用方管理的租户事务/savepoint、逐项错误统计、dry-run 与依赖顺序。不要为了模式完整再为每个简单 upsert 单独创建接口、工厂和包装层。

**关闭条件**：处理器依赖和单类职责缩小；新增组件按自己的领域规则测试；调用方端到端保持租户事务、strict/partial 结果、顺序依赖与 dry-run 行为。当前审查未实施拆分，也未执行测试。

### DP-02 / P3：Trigger 核心服务承载过多独立用例

**位置**：[DefaultTriggerService.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/DefaultTriggerService.java) 第 90 行起，当前约 1156 行、12 个注入依赖。

同一实现公开处理 API 手工启动、外部事件接纳/状态、Quartz 定时启动、拒绝 occurrence 的 catch-up、补跑请求创建/审批和普通请求状态查询。方法和依赖可分成几组：事件受理与多目标投递（`launchExternalEvent`、`admitExternalEvent`、约第 130–360 行）；定时 occurrence、上游 readiness 和 catch-up 审批（约第 362–763 行）；通用请求幂等持久化和 Outbox 转发（`persistAndForward`，约第 764–895 行）；查询和状态装配。服务自身还处理事务、租户激活检查、排程和 Outbox。

**适合的模式**：按稳定用例边界拆为独立 command/application handler，或由轻量门面委派；将确实共享的请求持久化/Outbox 原子流程留在清晰的公共边界。不要仅为降低行数再复制出多层接口，也不要使用按 TriggerType 选择的通用 Strategy 把语义不同的流程伪装为可替换算法：手工、事件、调度和审批的幂等身份、准入条件、事务顺序和 occurrence 副作用各不相同。

**类型与证据**：P3 结构/可维护性建议，不是已确认的行为缺陷。依据当前源码的类行数、12 项依赖、公开用例和私有流程分组；未做运行时故障复现。模块内统一 Trigger 接纳是合理边界，因此拆分应保持 `TriggerService` 对外契约和每条用例的事务/幂等行为。

**关闭条件**：在继续扩展 Trigger 用例或进行相关重构时，先分别明确事务所有者及依赖；把相互独立的外部事件/定时补跑/查询职责移出核心实现，保留必要的原子共用流程；用 Trigger 定向集成回归证明事件多目标去重、Quartz occurrence/CATCH_UP CAS、Outbox 与租户围栏均未改变。当前未拆分、未运行测试，不要求立即进行跨模块重构。

## 模式抽查矩阵

| 模式/边界 | 观察范围 | 本轮判断 |
|---|---|---|
| `Template` + `Strategy` | 配置初始化 `SpecHandler` 和 `applySpecs` | 共同的单项 apply 流程确有重复；保留通用流程，领域逻辑需继续隔离（DP-01） |
| `Command Handler` / 用例服务 | Trigger API、事件、Quartz、补跑和查询入口 | 多种用例及其事务/幂等责任在一个实现中聚合；适合按用例边界拆分，但不适合强行共用一个 TriggerType Strategy（DP-02） |
| `Registry` | `ConfigReleaseTypeRegistry` | 通过配置类型注册/规范化表达输入类型分派；未发现空转工厂或无意义继承 |
| `Policy` | 通知事件类型、订阅匹配/限流策略；文件到达与数据库行传感器策略 | 变化规则有明确语义；抽查范围内未发现仅为命名而存在的策略层。订阅匹配与限流可以在未来变化方向分离时再评估，不单独登记问题 |
| `Adapter` | Trigger 到 Orchestrator 的启动适配；各 Worker 阶段执行适配 | 位于协议/模块边界，有转换与执行边界职责，模式使用合理 |
| `Outbox` | Orchestrator、通知及 Worker 报告相关改动 | 数据库与消息/报告一致性需要持久边界；未发现本轮抽查的 Outbox 仅包装一次调用而无所有权价值 |
| `Factory` / `Decorator` / `State` | 本轮改动中按类名与调用点抽样 | 未找到有充分证据的过度使用；这不等于完成全仓模式盘点 |

## 权威约束

审查按 `docs/agent-baseline.md` §11.1、§11.4、§11.6–§11.8 执行：一个类承载稳定职责；优先组合；设计模式只用于明确复杂度；避免过多接口、工厂、适配器、包装器；模式调整应能降低复杂度且避免主链路未稳定时的大范围重构。

## 验证边界

本轮为静态设计审查，检查了类职责、依赖数量、调用点和现有架构边界测试定义。没有运行测试或模块边界 ArchUnit；没有更改业务代码。`git diff --check`、`python3 scripts/ci/check-docs-structure.py`、`python3 scripts/ci/check-doc-timestamp-policy.py` 均退出 0。工作区包含大量并行开发改动，结果仅适用于上述抽查范围与本轮工作区状态。

## 后续处理（2026-10-27）

- DP-01已在WDI工作树完成职责分离：Job规则与Workflow图/版本写入各有协作者，共同逐项事务和统计保留，受影响44项通过。当前源码、选择范围及静态/运行证据见[本轮治理记录](backend-25-package-governance-2026-10-26.md)的CONV-20261027-DP01。以上2026-10-21原始审查结论保持原样。
- DP-02也已单独实施：统一门面委派事件、定时补跑、查询及共享请求持久化，原事务入口保留；46项静态身份核对及72项受影响测试通过。见上述治理记录CONV-20261027-DP02，不用DP-01结果替代本项证据。
