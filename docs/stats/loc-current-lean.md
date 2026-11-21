# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `9ecccbb54`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 6,383 | 834,756 | 436,286 | 52.3% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| test | 1,944 | 416,935 | 224,445 | 53.8% |
| prod | 3,519 | 329,622 | 157,105 | 47.7% |
| script | 741 | 69,241 | 42,329 | 61.1% |
| config | 68 | 10,291 | 7,689 | 74.7% |
| infra-config | 34 | 6,160 | 4,479 | 72.7% |
| sql | 77 | 2,507 | 239 | 9.5% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 4,811 | 648,413 | 342,538 | 52.8% |
| Python | 351 | 53,254 | 29,221 | 54.9% |
| Shell | 219 | 36,178 | 28,630 | 79.1% |
| YAML | 109 | 15,022 | 11,614 | 77.3% |
| XML | 225 | 31,758 | 9,463 | 29.8% |
| TypeScript | 42 | 8,679 | 4,596 | 53.0% |
| Rust | 24 | 9,854 | 3,529 | 35.8% |
| Properties | 5 | 3,193 | 2,653 | 83.1% |
| SQL | 552 | 19,688 | 2,206 | 11.2% |
| Go | 37 | 8,364 | 1,604 | 19.2% |
| TOML | 8 | 353 | 232 | 65.7% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowOccurrenceCatchUpApprovalE2eIT.java` | test | Java | 7,854 | 3,290 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobNodeDispatchIntegrationTest.java` | test | Java | 6,331 | 2,733 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerReportLostAfterBusinessCommitE2eIT.java` | test | Java | 4,925 | 2,583 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayMetricsMapperIntegrationTest.java` | test | Java | 3,750 | 1,791 |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerServiceIntegrationTest.java` | test | Java | 2,798 | 1,459 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SelfServiceJobCompensationHttpE2eIT.java` | test | Java | 2,916 | 1,389 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,635 | 1,373 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,635 | 1,373 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java` | test | Java | 3,064 | 1,347 |
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,458 | 1,333 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,586 | 1,326 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,584 | 1,326 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | test | Java | 2,157 | 1,128 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/WorkflowSensorApplicationContextRestartIntegrationTest.java` | test | Java | 2,524 | 1,081 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/StaleWorkerReportFenceE2eIT.java` | test | Java | 2,040 | 1,014 |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DispatchPipelineE2eIT.java` | test | Java | 2,065 | 988 |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanPersistenceIntegrationTest.java` | test | Java | 1,531 | 954 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBatchInitStrictAtomicityIntegrationTest.java` | test | Java | 1,570 | 951 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 1,031 | 940 |
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
