# E2E 场景矩阵

更新时间：2026-04-02

这份矩阵只记录 `batch-e2e-tests` 里已经落地的端到端场景，以及当前仍缺的高价值场景。

字段说明：

- `作业类型`：Import / Export / Dispatch / 平台共性
- `触发类型`：当前 E2E 基本都走 `API`；平台级轮询类用 `N/A`
- `故障类型`：主链路、失败、重试、并发、幂等、租约回收等
- `覆盖状态`：`已覆盖` / `未覆盖`

## 已覆盖

| 测试类 | 作业类型 | 触发类型 | 场景 / 故障类型 | 覆盖状态 | 备注 |
|---|---|---|---|---|---|
| `ImportPipelineE2eIT` | Import | API | 主链路成功 | 已覆盖 | launch -> outbox -> worker -> 业务表落库 |
| `ImportFailureE2eIT` | Import | API | 模板不存在、字段校验失败、重试耗尽 / 死信 | 已覆盖 | 失败态与错误记录落库 |
| `ImportFailurePipelineE2eIT` | Import | API | import pipeline 失败推进 | 已覆盖 | 失败分支回报闭环 |
| `OutboxForwarderE2eIT` | 平台共性 / Import | API | Outbox 自动轮询 | 已覆盖 | NEW -> PUBLISHED |
| `OutboxForwarderRetryE2eIT` | 平台共性 | N/A | Outbox 失败重试与恢复 / 耗尽 | 已覆盖 | mock publisher 控制成功/失败序列 |
| `WorkerDrainE2eIT` | Import | API | worker drain timeout、接管、去注册 | 已覆盖 | DRAINING -> DECOMMISSIONED |
| `ExportPipelineE2eIT` | Export | API | 主链路成功 | 已覆盖 | business table -> file -> register -> success |
| `ExportContentVerificationE2eIT` | Export | API | 产物内容级验证 | 已覆盖 | 行数、金额、内容片段、MinIO |
| `ExportFailurePipelineE2eIT` | Export | API | export pipeline 失败推进 | 已覆盖 | 失败态回报闭环 |
| `ExportStorageFailureE2eIT` | Export | API | 存储失败（MinIO / object store） | 已覆盖 | 产物写入阶段故障 |
| `DispatchPipelineE2eIT` | Dispatch | API | 主链路成功 | 已覆盖 | 通道、文件、回执状态流转 |
| `DispatchFailurePipelineE2eIT` | Dispatch | API | 通道不可达 / 目标失败 | 已覆盖 | 失败与补偿路径 |
| `MultiTenantConcurrentE2eIT` | 跨作业 / Import | API | 多租户并发隔离 | 已覆盖 | 同时触发不串租户 |
| `DedupJobLaunchE2eIT` | 跨作业 / Import | API | 顺序去重、并发去重 | 已覆盖 | 同 dedupKey 稳定收敛 |

## 未覆盖

| 作业类型 | 触发类型 | 场景 / 故障类型 | 兜底情况 | 建议优先级 | 说明 |
|---|---|---|---|---|---|
| Import | MANUAL | 人工发起的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有 trigger / launch 相关集成测试，没有专项 E2E |
| Import | EVENT | 事件触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有触发元数据和调度链路验证，没有专项 E2E |
| Import | CATCH_UP | 补跑触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有 catch-up / misfire 集成验证，没有专项 E2E |
| Import | SCHEDULED | Quartz 定时触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有 Quartz 注册 / cluster 基础验证，没有专项 E2E |
| Export | MANUAL | 人工发起的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有调度和 worker 链路集成验证，没有专项 E2E |
| Export | EVENT | 事件触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有 launch / outbox / worker 逻辑验证，没有专项 E2E |
| Export | CATCH_UP | 补跑触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有调度和补跑逻辑验证，没有专项 E2E |
| Export | SCHEDULED | Quartz 定时触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有 Quartz / 调度集成验证，没有专项 E2E |
| Dispatch | MANUAL | 人工发起的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有 worker / channel 适配器测试，没有专项 E2E |
| Dispatch | EVENT | 事件触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有 dispatch 流程与健康检查验证，没有专项 E2E |
| Dispatch | CATCH_UP | 补跑触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有重试 / 回执 / 补偿逻辑验证，没有专项 E2E |
| Dispatch | SCHEDULED | Quartz 定时触发的完整端到端链路 | E2E缺口但已有集成兜底 | 低 | 有调度入口和 worker dispatch 验证，没有专项 E2E |
| 平台共性 | N/A | Quartz 多实例 / 集群 failover | E2E缺口但已有集成兜底 | 中 | 已有 JDBC store / cluster state 集成验证，但没有完整故障切换 E2E |
| 平台共性 | N/A | 长时间 soak / 反复重入竞态 | E2E缺口但已有集成兜底 | 中 | 已有并发 claim / scheduler 重入回归，但没有长时间 soak E2E |
| 平台共性 | N/A | 补偿审批 / replay / dead letter 人工闭环 | E2E缺口但已有集成兜底 | 中 | 有审批 / replay 控制器和服务层测试，没有专项 E2E |
| 平台共性 | N/A | 真实外部渠道 SFTP / EMAIL / OSS | E2E缺口但已有集成兜底 | 中 | 已补 [`DispatchExternalChannelIntegrationTest`](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/test/java/com/example/batch/worker/dispatchs/integration/DispatchExternalChannelIntegrationTest.java)，E2E 仍缺但风险已下降 |
| 平台共性 | N/A | worker 进程级重启后的恢复 | E2E缺口但已有集成兜底 | 中 | 已补 [`WorkerProcessRestartRecoveryIntegrationTest`](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/test/java/com/example/batch/orchestrator/integration/WorkerProcessRestartRecoveryIntegrationTest.java)，覆盖 worker 进程重启后的注册续跑与任务继续 |
| 平台共性 | N/A | staging live rollout / rollback smoke | E2E缺口但已有脚本兜底 | 中 | 已补 [`run-staging-live-smoke.sh`](/Users/dengchao/Downloads/file-batch-system/scripts/ci/run-staging-live-smoke.sh)，用于 staging 的 live deploy + rollback smoke |

## 结论

当前 E2E 已覆盖核心主链路、典型失败、Outbox 重试、多租户隔离和 dedup 幂等；未覆盖部分主要集中在非 API 触发、Quartz 集群故障切换、长时间竞态、真实外部渠道和少量部署级 smoke。worker 重启恢复和 staging smoke 已分别补上集成与脚本兜底。
