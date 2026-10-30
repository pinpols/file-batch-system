# 验证证据索引

本目录保存按日期冻结的测试、压测和质量验证证据。报告不代表持续保证；上线结论必须结合最新代码、
[`../testing/release-gate.md`](../testing/release-gate.md) 与目标环境重新验证。

## 最新基线

| 文档 | 范围 |
|---|---|
| [前后端Bug审查与验证汇总](../review/frontend-backend-bug-review-2026-10-27.md#backend-validation-record) | 配置身份140项/真实PG61项，Worker Core402项、上游783通过/5跳过、配额真实PG12/Redis6项；前轮迁移4项独立保留；配对前端证据与首红范围保留 |
| [wdi-local-security-validation-2026-10-25.md](wdi-local-security-validation-2026-10-25.md) | 本地秘密/SAST/依赖、B199/B200各8镜像与认证DAST；保留NVD失败及告警边界 |
| [wdi-local-closure-2026-10-25.md](wdi-local-closure-2026-10-25.md) | 双仓测试/模型/脚本、批量接线、真实SIM/浏览器和开关证据；原始WARN与新增维护候选分列 |
| [wdi-functional-closure-2026-10-25.md](wdi-functional-closure-2026-10-25.md) | readiness、Kafka 耗尽恢复、WR-04、独立 SDK 故障边界及配置导入定向验证；与正式发布验收分开 |
| [local-log-retention-2026-10-24.md](./local-log-retention-2026-10-24.md) | 本地日志默认留存、内容校验归档、保护边界、CLI与预览证据；真实日志未清理，全仓高风险操作基线仍有既有差异 |
| [worker-five-load-validation-2026-10-04.md](./worker-five-load-validation-2026-10-04.md) | 五类 Worker 1w / 10w 压测验收记录模板与当前证据入口 |
| [worker-local-runtime-performance-2026-09-14.md](worker-stress-and-recovery-validation-2026-09-14.md) | 本地 JVM Worker 严格阶梯、混合压力与故障恢复复测 |
| [worker-local-runtime-performance-2026-09-20.md](worker-local-acceptance-and-mixed-load-2026-09-20.md) | 2026-09-20 的本地构建、BE acceptance、SIM 全场景与30秒 mixed 复测；不覆盖9月14日的阶梯/故障画像 |
| [heavy-workload-guarantees-2026-09-14.md](./heavy-workload-guarantees-2026-09-14.md) | 重任务容量、资源池、长任务、下游健康和墙钟吞吐保障 |
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

- [load-test-report-2026-04-25.md](load-test-report-2026-04-25.md)：早期单实例调度拐点压测，数字只适用于当时构建和负载。
- [worker-stage-coverage-2026-04-25.md](worker-stage-coverage-2026-04-25.md)：IMPORT / EXPORT / DISPATCH 三类 Worker 的历史运行证据；当前场景入口见 [后端 Sim 可靠性场景组](../testing/backend-sim-reliability.md)。


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

历史静态扫描见审计目录；下列独立验收和扫描均按原基线追溯： [2026-08-05](../archive/audit/sonar-full-scan-2026-08-05.md)、
[原始 CSV](../archive/audit/sonar-full-scan-2026-08-05.csv)、[2026-08-06](../archive/verifications/batch-4-ci-coverage-validation-2026-08-06.md)。

## 构建、CI 与对象存储验证

| 文档 | 职责 |
|---|---|
| [build-time-optimization-2026-09-12.md](build-time-optimization-2026-09-12.md) | Java 应用构建耗时分析与优化验收 |
| [ci-gate-runtime-optimization-2026-10-07.md](ci-gate-runtime-optimization-2026-10-07.md) | CI 门禁耗时分析与优化记录（2026-10-07） |
| [rustfs-poc-2026-09-25.md](rustfs-poc-2026-09-25.md) | RustFS S3 Compatibility POC |
| [s3-compatible-backend-comparison-2026-09-25.md](s3-compatible-backend-comparison-2026-09-25.md) | RustFS and SeaweedFS Aligned S3 Validation |
| [seaweedfs-poc-2026-09-25.md](seaweedfs-poc-2026-09-25.md) | SeaweedFS S3 Compatibility POC |

- [script-runtime-compatibility-validation-2026-09-01.md](script-runtime-compatibility-validation-2026-09-01.md)：原兼容矩阵中的 Python PostgreSQL fallback 与失败退出码历史实测。

## 专题入口

| 文档 | 职责 |
|---|---|
| [resilience4j-sb4-compat-2026-09-25.md](resilience4j-sb4-compat-2026-09-25.md) | Resilience4j 与 Spring Boot 4 兼容性 |

## 文件用途补充

| 文档 | 用途 |
|---|---|
| [control-plane-worker-throughput-2026-06-07.md](control-plane-worker-throughput-2026-06-07.md) | Control Plane Worker Throughput Baseline - 2026-06-07 |
| [import-partition-replace-copy-10m-system-2026-06-07.md](import-partition-replace-copy-10m-system-2026-06-07.md) | Import PARTITION_REPLACE_COPY 1000w 系统链路验证（2026-06-07） |
| [multitenant-peak-single-node-ceiling-2026-06-13.md](multitenant-peak-single-node-ceiling-2026-06-13.md) | 多租户并发峰值流量 — 单机控制面吞吐上限实测(2026-06-13) |
| [preprod-worker-p0-pressure-2026-06-08.md](preprod-worker-p0-pressure-2026-06-08.md) | 上线前 Worker P0 压测复验报告 - 2026-06-08 |
| [process-worker-throughput-2026-06-07.md](process-worker-throughput-2026-06-07.md) | Process Worker Throughput Report - 2026-06-07 |
| [sim-e2e-2026-05-29.md](sim-e2e-2026-05-29.md) | sim 端到端真实跑通验证(2026-05-29) |
| [sonar-report-2026-08-06.md](../archive/verifications/batch-4-ci-coverage-validation-2026-08-06.md) | Batch 4 验收报告 |
| [streaming-large-file-import-export-2026-06-06.md](streaming-large-file-import-export-2026-06-06.md) | 百万级宽表长字段 流式导入/导出 实证(2026-06-06) |
| [worker-business-scenario-matrix-2026-06-08.md](worker-business-scenario-matrix-2026-06-08.md) | Worker 业务场景矩阵验证报告（2026-06-08） |
| [worker-matrix-verification-2026-06-08.md](worker-matrix-verification-2026-06-08.md) | Worker 压测矩阵复核报告 - 2026-06-08 |
| [worker-p2-capacity-profile-2026-06-08.md](worker-p2-capacity-profile-2026-06-08.md) | Worker P2 本地容量画像报告 - 2026-06-08 |

## Worker 两轮记录的范围

- [2026-09-14 阶梯与恢复验证](worker-stress-and-recovery-validation-2026-09-14.md)：含1/2/4/8阶梯、混合流量、Process进程终止和数据库会话中断恢复。
- [2026-09-20 本地验收与混合压测](worker-local-acceptance-and-mixed-load-2026-09-20.md)：重新构建、完整本地验收、SIM全场景与30秒混合流量。

两轮基线和验证范围不同，后者不等同于覆盖前者全部场景；日期报告保留原结论，不推导为本轮改造已验收。

- [后端 Bug 运行复现与整改验证（2026-10-26）](backend-bug-remediation-2026-10-26.md): 9 项修复、真实 PostgreSQL/SQLite/Kafka 与定向回归证据。
