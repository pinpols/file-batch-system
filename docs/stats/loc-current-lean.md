# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `481928647`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 6,371 | 831,625 | 434,571 | 52.3% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| test | 1,936 | 414,851 | 223,204 | 53.8% |
| prod | 3,517 | 328,714 | 156,701 | 47.7% |
| script | 740 | 69,164 | 42,269 | 61.1% |
| config | 68 | 10,286 | 7,687 | 74.7% |
| infra-config | 34 | 6,160 | 4,479 | 72.7% |
| sql | 76 | 2,450 | 231 | 9.4% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 4,801 | 645,576 | 340,926 | 52.8% |
| Python | 351 | 53,254 | 29,221 | 54.9% |
| Shell | 218 | 36,101 | 28,570 | 79.1% |
| YAML | 109 | 15,011 | 11,608 | 77.3% |
| XML | 225 | 31,615 | 9,440 | 29.9% |
| TypeScript | 42 | 8,679 | 4,596 | 53.0% |
| Rust | 24 | 9,854 | 3,529 | 35.8% |
| Properties | 5 | 3,187 | 2,647 | 83.1% |
| SQL | 551 | 19,631 | 2,198 | 11.2% |
| Go | 37 | 8,364 | 1,604 | 19.2% |
| TOML | 8 | 353 | 232 | 65.7% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowOccurrenceCatchUpApprovalE2eIT.java` | test | Java | 7,852 | 3,290 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobNodeDispatchIntegrationTest.java` | test | Java | 6,272 | 2,713 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerReportLostAfterBusinessCommitE2eIT.java` | test | Java | 4,925 | 2,583 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayMetricsMapperIntegrationTest.java` | test | Java | 3,750 | 1,791 |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerServiceIntegrationTest.java` | test | Java | 2,798 | 1,459 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SelfServiceJobCompensationHttpE2eIT.java` | test | Java | 2,916 | 1,389 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,635 | 1,373 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,635 | 1,373 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java` | test | Java | 3,064 | 1,347 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,583 | 1,323 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,581 | 1,323 |
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,409 | 1,293 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | test | Java | 2,157 | 1,128 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/WorkflowSensorApplicationContextRestartIntegrationTest.java` | test | Java | 2,522 | 1,080 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/StaleWorkerReportFenceE2eIT.java` | test | Java | 2,040 | 1,014 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DispatchPipelineE2eIT.java` | test | Java | 2,065 | 988 |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanPersistenceIntegrationTest.java` | test | Java | 1,531 | 954 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBatchInitStrictAtomicityIntegrationTest.java` | test | Java | 1,570 | 951 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 1,030 | 939 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigDependencyRlsIntegrationTest.java` | test | Java | 1,854 | 929 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
