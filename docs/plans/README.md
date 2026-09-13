# 阶段计划

近期工程化计划与能力对照：

| 文档 | 用途 |
|---|---|
| [java-readability-refactoring-roadmap-2026-08-12.md](./java-readability-refactoring-roadmap-2026-08-12.md) | Java 可读性、事务自注入、固定 Map 契约和复杂类的分阶段治理路线 |
| [backend-borrowings-and-improvements-2026-07.md](./backend-borrowings-and-improvements-2026-07.md) | 后端工程化借鉴、已落地能力与剩余治理项 |
| [bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md](./bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md) | 对标开源调度器后的 BFS 调度 / 编排边界、五块强化计划和验证口径 |
| [spring-boot-engineering-patterns-plan-2026-08-02.md](./spring-boot-engineering-patterns-plan-2026-08-02.md) | Spring Boot 工程化样板落地计划 |
| [engineering-benchmark-comparison-2026-08-02.md](./engineering-benchmark-comparison-2026-08-02.md) | BFS 与优秀系统的工程能力对照表 |

## 计划登记

计划状态以各文档头部和 [`../analysis/todo-master.md`](../analysis/todo-master.md) 为准；“Implemented”文档只保留实施与验收依据。

| 领域 | 文档 |
|---|---|
| 控制面与 HA | [控制面吞吐](./control-plane-throughput-optimization-2026-09-07.md)、[HA 部署](./2026-06-13-ha-deployment-plan.md)、[多租隔离](./multi-tenant-isolation-plan-2026-05-31.md)、[biz 分层租户](./biz-tiered-tenancy-plan-2026-06-14.md) |
| 文件与恢复 | [Export keyset](./2026-06-06-export-partition-keyset-range.md)、[Export slice](./2026-06-06-export-partition-slice.md)、[S3 SDK 迁移](./2026-06-06-migrate-minio-sdk-to-aws-sdk-v2.md)、[完整性 sidecar](./file-integrity-sidecar-manifest-plan-2026-06-07.md)、[checkpoint](./checkpoint-resume-design-2026-07.md) |
| 批量日与结算 | [整批量日 dry-run](./batch-day-dry-run-enhancement-plan-2026-09-08.md)、[结算差距治理](./settlement-gap-remediation-roadmap-2026-06-20.md) |
| SDK 与前端 | [SDK roadmap](./sdk-roadmap-2026-h2.md)、[roadmap 进度](./sdk-roadmap-2026-h2-progress.md)、[FE 工作清单](./fe-worklist-2026-h2-atomic-sdk.md) |
| 可观测与扩展 | [Alertmanager 迁移](./alertmanager-migration-plan-2026-07.md)、[AI 接入](./ai-integration-plan-2026-07.md) |

历史验证基础设施计划：

## r3 Validation Infrastructure Plans

补 `scripts/sim/` 之外的验证维度。详见 `docs/architecture/` 关于本系统验证分层的总图。

## 4 个 Plan

| # | Plan | 解决什么 | 优先级 | 估时 |
|---|---|---|---|---|
| 1 | [Chaos / Toxiproxy IT](./r3-1-chaos-toxiproxy-it.md) | broker / db 故障下的熔断 / 降级 / 自愈 | P0 | 1d |
| 2 | [运维剧本](./r3-2-runbook-playbooks.md) | on-call 凌晨 3 点的"怎么救" | P0 | 0.5d |
| 3 | [Soak Test](./r3-3-soak-tests.md) | 长期稳定性(泄漏 / 跨日 / 累计 lag) | P1 | 1d |
| 4 | [Forensic 回放](./r3-4-forensic-replay.md) | 用昨日生产数据本地回放看是否改判 | P1 | 1.5d |

## 并行度

- Plan #1 与 Plan #2 配对:剧本写"怎么救",IT 验"救得回" — 同一人接收益最大
- Plan #3 独立,可并行
- Plan #4 依赖 sim(已存在)+ console-api,可并行

## 总工期
完整 4 件套约 4 天工。可由 1 人顺序 / 多人并行。

## 总验收
4 个 Plan 各自验收完毕 + 在 `docs/architecture/` 加一节"验证维度全图",标注 sim / chaos IT / soak / forensic replay 各自负责的格子。
