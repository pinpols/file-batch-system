# Worker 本地运行时验收与性能复测（2026-09-20）

## 结论

本轮使用当前工作树重新构建 8 个应用 JAR，应用运行在宿主 JVM，PostgreSQL、Kafka、
MinIO 和 Valkey 运行在 Docker。完整 BE acceptance、sim 全场景和 30 秒 mixed 严格压测均通过。

这些结果证明当前代码在本机环境下没有复现功能回归、终态残留或控制面积压；不代替预生产
容量测试、外部依赖故障注入或长时间 soak。

## 构建与验收

- 构建：`bash scripts/local/build-apps.sh`，8 个 `1.0.0` 可执行 JAR 重新输出到 `build/runtime-jars/`。
- BE acceptance：`PASS 20 / FAIL 0`，包含单元、集成、E2E 和严格 20/20 终态核验。
- BE 日志：`logs/runs/be-acceptance/be-acceptance-20260920-144951-31703dbcc`。
- sim：`sim-harness.sh all` 的 25 个阶段全部 PASS，包含双 PG 分片、三租户导入/导出/分发、
  Atomic、Process、Trigger、bundle、批量 claim 和 checkpoint crash 恢复。
- sim 日志：`logs/runs/sim-harness/sim-harness-20260920-162031-31703dbcc`。

## Mixed 严格压测

有效运行标识：`ctlw-local-mixed-20260920164649`。

- 时长：30 秒。
- 流量：Process / Dispatch / Atomic 各1 launch/s，Trigger Atomic 3 launch/s，调度读取 1 user/s。
- HTTP：240/240 成功，0 失败，p95 25 ms，p99 164 ms，平均 8 requests/s。
- 入口与终态：180/180 请求创建实例，180/180 实例进入 `SUCCESS`。
- 分模块：Atomic 30/30、Dispatch 30/30、Process 30/30、Trigger to Atomic 90/90。
- 完成 p95：Atomic 1.319 s，Dispatch 1.215 s，Process 1.311 s，Trigger to Atomic 1.359 s。
- 执行 p95：Atomic 0.051 s，Dispatch 0.190 s，Process 0.155 s，Trigger to Atomic 0.027 s。
- Worker 结束负载均为 0；Process staging 为 0；30 条 Dispatch 全部 `ACKED`。
- Kafka：Atomic、Dispatch、Process 和 Trigger launch 相关分区 lag 均为 0。
- 清理：自动清理后，该 `RUN_ID` 的 `job_instance + trigger_request` 残留计数为 0，业务压测数据已删除。

详细报告：`load-tests/target/control-plane-worker-report-ctlw-local-mixed-20260920164649.md`。

## 性能分析

本轮实例完成 p95 约 1.2-1.4 秒，而业务执行 p95 为 0.027-0.190 秒，主要时间仍在控制面
排队、派发和 claim，不在 Worker 执行器。PostgreSQL 观测窗口内无回滚、无锁等待、无请求型
checkpoint，Kafka 结束 lag 为 0，说明 6 launch/s 的本地基线尚未形成持续积压。

本轮不能用于推导生产最大吞吐。下一档容量验证应在预生产固定 CPU/内存/连接池，逐档提升
launch RPS，同步采集 claim/report SQL 等待、Kafka lag、Worker semaphore 和 GC pause。

## 本轮修复

- 本地共享 Worker 能力标签补齐 `TRANSACTION / STATEMENT / REVIEW / RISK`，使三租户队列能真实路由。
- sim 主线模板引用与 Console 导入后的规范化小写编码对齐。
- BE/sim 强制宿主 JVM 运行模式，避免 stopped Compose 标签导致误用旧镜像。
- sim 先决数据改为最小幂等 SQL，不再重放带固定 ID 的整份平台 seed。
- Kafka lag 采样按工具实际所在位置选择宿主或容器 CLI，不再由应用运行模式错误跳过容器。
