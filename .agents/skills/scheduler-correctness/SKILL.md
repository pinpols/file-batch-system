---
name: scheduler-correctness
description: 用户要求设计、修改或审查 Trigger、业务日历、定时触发、misfire、补跑、readiness defer 或 pause/resume 时使用。
---

# 调度与时序正确性

## 建立当前事实

- 先读 `docs/runbook/trigger-operations.md`、相关 Trigger 实现和测试，再核对关联 ADR 的状态；ADR-033 已被标记 Superseded，不能据此假定当前引擎是时间轮。不要执行标为已废止的 rollout 步骤。
- 调度状态、请求状态、outbox 和 `job_instance` 的责任边界要分别确认。不要通过直接改 Quartz 表或业务状态表代替受支持的 API/迁移路径。
- 多日历行为以当前实现和已接受 ADR 为准；识别哪些能力已落地、哪些只是设计，不把 ADR 规划误报为产品现状。

## 正确性审查

- 明确 scheduled fire 的时间点、租户、业务日期、时区、日历和幂等键。使用项目统一的 `BatchTimezoneProvider` 等注入时区策略；不依赖 `ZoneId.systemDefault()`。覆盖 DST、跨午夜、日历 cutoff 和业务日期固定语义。
- 沿 fire 到实例创建追踪事务、outbox、重试和去重。并发 fire、leader 切换、超时重试不得丢触发或产生重复实例；状态写入须防终态复活。
- 分别审查 misfire 策略、catch-up 审批/重放、上游 readiness defer 的重检窗口及超时行为。延期、跳过、失败和人工审批必须语义明确、可观测且幂等。
- pause/resume 不得取消已有审计或复活终态；核对单租户/单 Job/全局操作边界、权限、CAS 和并发操作顺序。
- 测试需包含边界时间、并发、重启/恢复和失败路径。真实 Quartz misfire callback 与 fixture/fallback 注入是不同证据，报告中明确标记来源。

## 验证与运维

- 优先运行相关 Trigger 单元/集成测试，再按场景运行 `scripts/sim/22-trigger-stage6c.sh`、`scripts/sim/24-trigger-stage6d.sh` 或本地 sim harness；遵循脚本的数据库隔离、Trigger 停启和清理边界。
- staging misfire 专项按 `trigger-operations.md` 执行并记录 callback、pending、指标、requestId、outbox 和最终实例状态。fixture 测试通过不得宣称真实 Quartz callback 已验证。
- 运行管理操作前先 dry-run 并确认租户、目标 Job、维护窗口和回滚方式。生产操作仅通过已授权的管理 API。

## 参考入口

- `docs/runbook/trigger-operations.md`
- `docs/architecture/adr/ADR-023-multi-calendar-coordination.md`
- `docs/architecture/adr/ADR-033-quartz-to-wheel-scheduler.md`（历史决策状态须先核实）
- `batch-trigger/src/main/java/`、`batch-trigger/src/test/java/`
- `scripts/sim/22-trigger-stage6c.sh`、`scripts/sim/24-trigger-stage6d.sh`
- 涉及 Worker/Outbox/Kafka 主链时，同时使用 `worker-pipeline-review`；涉及变更门禁时使用 `code-review-and-gates`。
