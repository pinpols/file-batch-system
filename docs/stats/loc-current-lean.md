# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `e1c2ad9bc`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,782 | 504,651 | 259,598 | 51.4% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,757 | 251,564 | 115,160 | 45.8% |
| test | 1,240 | 184,066 | 101,170 | 55.0% |
| script | 638 | 53,166 | 33,257 | 62.6% |
| config | 54 | 8,250 | 5,930 | 71.9% |
| infra-config | 32 | 5,344 | 3,912 | 73.2% |
| sql | 61 | 2,261 | 169 | 7.5% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,516 | 367,662 | 190,002 | 51.7% |
| Shell | 202 | 30,442 | 23,779 | 78.1% |
| Python | 250 | 35,447 | 18,320 | 51.7% |
| YAML | 95 | 12,461 | 9,439 | 75.7% |
| XML | 179 | 21,589 | 6,772 | 31.4% |
| TypeScript | 37 | 6,632 | 3,124 | 47.1% |
| Rust | 23 | 8,423 | 2,835 | 33.7% |
| Properties | 5 | 2,887 | 2,361 | 81.8% |
| SQL | 433 | 11,791 | 1,458 | 12.4% |
| Go | 34 | 6,983 | 1,288 | 18.4% |
| TOML | 8 | 334 | 220 | 65.9% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,383 | 1,269 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,433 | 1,180 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,431 | 1,180 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,228 | 1,027 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,228 | 1,027 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 879 | 796 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 991 | 712 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,147 | 646 |
| `deploy/docker/compose/app.yml` | config | YAML | 724 | 643 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 768 | 560 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 979 | 559 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 647 | 528 |
| `scripts/local/start-all.sh` | script | Shell | 638 | 512 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationServiceTest.java` | test | Java | 814 | 503 |
| `pom.xml` | config | XML | 823 | 500 |
| `scripts/local/be-acceptance.sh` | script | Shell | 612 | 479 |
| `scripts/local/sim-harness.sh` | script | Shell | 619 | 477 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | prod | Java | 1,028 | 451 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceServiceTest.java` | test | Java | 793 | 438 |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/AbstractTaskConsumerTest.java` | test | Java | 698 | 433 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
