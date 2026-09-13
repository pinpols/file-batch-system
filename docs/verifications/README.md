# 验证证据索引

本目录保存按日期冻结的测试、压测和质量验证证据。报告不代表持续保证；上线结论必须结合最新代码、
[`../testing/release-gate.md`](../testing/release-gate.md) 与目标环境重新验证。

## 最新基线

| 文档 | 范围 |
|---|---|
| [control-plane-100k-throughput-optimization-2026-09-12.md](./control-plane-100k-throughput-optimization-2026-09-12.md) | 控制面 10 万任务吞吐 |
| [batch-day-dry-run-verification-2026-09-11.md](./batch-day-dry-run-verification-2026-09-11.md) | 整批量日 dry-run |
| [control-plane-admission-optimization-2026-09-05.md](./control-plane-admission-optimization-2026-09-05.md) | 控制面准入与排空 |
| [trigger-drain-optimization-2026-09-02.md](./trigger-drain-optimization-2026-09-02.md) | Trigger drain |
| [import-export-dispatch-scenario-performance-2026-09-01.md](./import-export-dispatch-scenario-performance-2026-09-01.md) | Import、Export、Dispatch 场景性能 |
| [process-atomic-scenario-performance-2026-09-01.md](./process-atomic-scenario-performance-2026-09-01.md) | Process、Atomic 场景性能 |
| [long-batch-schedule-processing-2026-08-31.md](./long-batch-schedule-processing-2026-08-31.md) | 长作业调度处理 |
| [front-ops-joint-system-verification-2026-08-29.md](./front-ops-joint-system-verification-2026-08-29.md) | 前台运维联合验证 |
| [import-mainline-scenario-closure-2026-08-28.md](./import-mainline-scenario-closure-2026-08-28.md) | Import 主链收口 |
| [control-plane-hot-path-optimization-2026-08-25.md](./control-plane-hot-path-optimization-2026-08-25.md) | 控制面热路径 |
| [quality-closure-2026-08-21.md](./quality-closure-2026-08-21.md) | 质量收尾复验 |

## 历史容量与场景证据

其余 2026-05 至 2026-06 报告用于历史对比：
[sim E2E](./sim-e2e-2026-05-29.md)、
[流式大文件](./streaming-large-file-import-export-2026-06-06.md)、
[控制面 Worker](./control-plane-worker-throughput-2026-06-07.md)、
[Process Worker](./process-worker-throughput-2026-06-07.md)、
[Import 千万行](./import-partition-replace-copy-10m-system-2026-06-07.md)、
[Worker 矩阵](./worker-matrix-verification-2026-06-08.md)、
[Worker 业务矩阵](./worker-business-scenario-matrix-2026-06-08.md)、
[Worker P2 容量](./worker-p2-capacity-profile-2026-06-08.md)、
[预生产 P0 压测](./preprod-worker-p0-pressure-2026-06-08.md)、
[多租户单机峰值](./multitenant-peak-single-node-ceiling-2026-06-13.md)。

Sonar 快照： [2026-08-05](./sonar-report-2026-08-05.md)、
[原始 CSV](./sonar-report-2026-08-05.csv)、[2026-08-06](./sonar-report-2026-08-06.md)。
