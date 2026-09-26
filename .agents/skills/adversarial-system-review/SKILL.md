---
name: adversarial-system-review
description: 用户要求对项目或关键链路做全面、对抗式安全/韧性/容量审查时使用。跨信任边界构造可验证失效路径，不替代针对单个 diff 的常规代码审查。
---

# 对抗式系统审查

## 定义审查边界

- 明确审查目标、部署边界、外部主体、信任域、数据敏感度和运行约束。广域审查按模块与链路建立覆盖清单，不把一次 grep、静态工具或单个 happy-path 测试当作全系统结论。
- 先阅读仓库 `AGENTS.md`、当前架构/安全/运行手册和代码入口。系统实际行为以当前实现、部署配置和可复现证据为准；旧 ADR、快照与历史审计先核实状态。
- 对项目级/上线级审查，先按下列维度建立覆盖表。每项标记“适用且有证据”“不适用及原因”“未验证”；不要因任务范围小而机械扫描所有面，也不要把未覆盖默认为通过：
  - **业务真实性与结果可信度**：账期/批次表达、补数重跑、部分失败、控制总额/笔数、文件编码/校验/断点续跑。
  - **日常运维闭环**：查询、审批、暂停/恢复、取消、重试、DLQ、outbox 修复是否有授权入口、审计和幂等保护，是否仍要求直接改库。
  - **数据一致性与 exactly-once**：数据库约束、事务/Outbox、Kafka offset、幂等键、CAS、防双 claim/finish、重试、终态复活、冷热归档和 replay。
  - **多租户与访问控制**：API/内部端点认证授权、tenant 绑定、RBAC、Mapper 谓词、业务库 RLS/GUC、分片路由、Kafka group/topic、缓存和对象存储边界；构造跨租查询验证。
  - **安全纵深与攻击面**：外部输入到 SQL/HTTP/Shell/文件路径、SSRF/RCE、Atomic 隔离、密钥存储/轮换、敏感信息脱敏、审批是否真拦截、审计留痕和安全扫描覆盖。
  - **调度和时间语义**：misfire、catch-up、readiness defer、重复 fire、leader 切换、日历依赖、DST/时区、bizDate 固定、pause/resume 与状态机交互。
  - **容量、背压与公平性**：线程/队列/连接池/请求体/文件大小是否有界，pre-claim admission、租户公平、重跑隔离、outbox/Kafka lag 收敛、固定批处理窗口和容量余量。
  - **故障恢复与灾备**：Worker 全队崩溃、lease 回收、PG failover/PITR、Kafka/Redis/对象存储中断、DLQ/replay、checkpoint 恢复、RPO/RTO 和恢复后数据一致性。
  - **数据生命周期与迁移**：Flyway/手工业务库脚本、复合键和分布约束、锁与耗时、回滚、分区、保留、archive 镜像和 PITR 覆盖。
  - **模块边界与复杂度**：领域所有权、依赖方向、跨模块直写、重复抽象/过度封装、死代码、资源生命周期、系统是否偏离批量运行控制面范围。
  - **API、Console 与用户工作流**：异常到 HTTP 契约、分页/排序一致性、权限映射、空态/失败恢复、键盘可访问性、响应式布局及关键操作是否真实生效。
  - **SDK 与外部兼容性**：多语言 wire contract、offset disposition、lease/heartbeat/cancel/shutdown/backpressure、未知协议版本、真实 transport 与 fixture 差异、滚动升级兼容。
  - **部署、配置与供应链**：生产 overlay、危险开关 fail-safe、版本/镜像对齐、依赖与许可证、容器/Kubernetes 安全、GitHub Actions 权限、路径过滤、required checks 和发布回滚。
  - **可观测与上线证据**：端到端 trace、SLO/告警、异常/metric 对应关系、优雅停机、真实数据和 staging 同构验收；代码绿、mock、fixture、sim、staging 证据分层报告。

## 构造攻击与失效路径

- 从攻击者或故障源出发，明确前置条件、入口、边界穿越、状态/数据副作用和业务影响；尝试绕过校验、租户绑定、限流、幂等、重试或人工审批。
- 对时间线和状态变更做对抗检查：并发请求、超时后重试、旧 leader 恢复、进程崩溃、依赖不可用、部分提交、重复消息、备份回滚和恢复后重放。
- 对容量攻击检查队列/连接池/线程池/文件体积/请求体/分页/扫描范围是否有界，是否在昂贵副作用前实施 admission/backpressure，以及失败是否放大重试。
- 对每个审查维度采用报告中的四项结构：检查对象、形式化落地/假阳性信号、可复现验证方法、通过判据。静态扫描只证明它所覆盖的属性，不可用同一模型内一致性替代威胁模型、真实数据、混沌注入、负载或 staging 演练。
- 每个疑似问题沿调用链追到具体现有代码、配置、SQL 或权限规则；尽可能添加最小复现或针对性验证。区分确认缺陷、纵深防御建议和未验证假设，不为假设堆叠宽泛防御代码。
- 需要真实数据、Docker、staging、凭据、破坏性操作或外部系统时，先确认环境和授权；静态检查不能替代这类实测。

## 结论格式

- 先按严重度列出可操作发现。每项给出文件/行或配置位置、触发条件、攻击/故障路径、影响、证据、建议修复及验证方式。
- 把“当前不存在可复现缺陷”与“没有风险”区分开；列出工具未覆盖、mock 与真实服务差异、未运行的演练和残余风险。
- 若用户只要求审查，先报告发现和建议，不擅自改代码、触发破坏性测试或发布；若明确要求修复，再按优先级单独实施并回归验证。

## 领域技能路由

- 单个 PR/diff 的缺陷审查：`code-review-and-gates`
- Worker、Outbox、Kafka 与执行恢复：`worker-pipeline-review`
- 安全工具与扫描覆盖：`security-scan-governance`
- 数据库性能、迁移、灾备和业务验收：分别使用 `sql-query-performance`、`database-migration-safety`、`disaster-recovery-validation`、`acceptance-validation`。

## 审查报告参考

- `docs/runbook/go-live-realism-audit-2026-06-21.md`：业务真实性、数据可信、容量/恢复、租户隔离、调度、时区、生命周期、迁移、兼容和安全的审核维度及“形式化落地”反例。
- `docs/audit/backend-six-round-adversarial-audit-2026-07-27.md`、`docs/audit/backend-adversarial-audit-2026-07-27.md`：模块边界、安全/租户、数据库/迁移、并发/状态机、资源、部署与 staging 证据的分轮审查方式。
- `docs/archive/analysis/2026-06-03-deep-scan-summary.md`：后端架构/资源/安全/业务运维、前端交互/反馈/布局/a11y、SDK 数据完整性和 CI/CD 的多 lane 覆盖方法。
- 上述均为历史报告，用于提取审查维度，不作为当前缺陷或修复状态依据。当前状态以最新代码、守护和验证记录为准。
