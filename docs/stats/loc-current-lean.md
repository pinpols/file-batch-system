# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `9d78b7d03`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,641 | 480,436 | 243,152 | 50.6% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,703 | 247,810 | 113,414 | 45.8% |
| test | 1,189 | 171,072 | 91,090 | 53.2% |
| script | 605 | 45,923 | 28,804 | 62.7% |
| config | 53 | 8,077 | 5,778 | 71.5% |
| infra-config | 32 | 5,332 | 3,901 | 73.2% |
| sql | 59 | 2,222 | 165 | 7.4% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,430 | 353,097 | 179,347 | 50.8% |
| Shell | 188 | 28,187 | 21,818 | 77.4% |
| Python | 218 | 29,011 | 14,786 | 51.0% |
| YAML | 95 | 12,358 | 9,340 | 75.6% |
| XML | 177 | 21,413 | 6,627 | 30.9% |
| TypeScript | 37 | 6,572 | 3,097 | 47.1% |
| Rust | 23 | 8,405 | 2,833 | 33.7% |
| Properties | 5 | 2,887 | 2,361 | 81.8% |
| SQL | 427 | 11,209 | 1,440 | 12.8% |
| Go | 34 | 6,967 | 1,287 | 18.5% |
| TOML | 7 | 330 | 216 | 65.5% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,378 | 1,264 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,433 | 1,180 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,431 | 1,180 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,216 | 1,016 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,216 | 1,016 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 789 | 714 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 991 | 712 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,145 | 644 |
| `deploy/docker/compose/app.yml` | config | YAML | 718 | 637 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 768 | 560 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 979 | 559 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 647 | 528 |
| `scripts/local/start-all.sh` | script | Shell | 640 | 512 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationServiceTest.java` | test | Java | 811 | 500 |
| `scripts/local/be-acceptance.sh` | script | Shell | 612 | 479 |
| `pom.xml` | config | XML | 764 | 455 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | prod | Java | 1,028 | 451 |
| `scripts/local/pre-push-sdk-checks.sh` | script | Shell | 558 | 424 |
| `scripts/local/sim-harness.sh` | script | Shell | 557 | 418 |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java` | prod | Java | 869 | 408 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
