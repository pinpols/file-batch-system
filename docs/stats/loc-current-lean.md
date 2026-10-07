# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `a725e8212`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,788 | 505,533 | 260,207 | 51.5% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,758 | 251,578 | 115,183 | 45.8% |
| test | 1,245 | 184,765 | 101,598 | 55.0% |
| script | 638 | 53,335 | 33,415 | 62.7% |
| config | 54 | 8,250 | 5,930 | 71.9% |
| infra-config | 32 | 5,344 | 3,912 | 73.2% |
| sql | 61 | 2,261 | 169 | 7.5% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,521 | 368,324 | 190,419 | 51.7% |
| Shell | 202 | 30,606 | 23,937 | 78.2% |
| Python | 251 | 35,498 | 18,354 | 51.7% |
| YAML | 95 | 12,461 | 9,439 | 75.7% |
| XML | 179 | 21,589 | 6,772 | 31.4% |
| TypeScript | 37 | 6,632 | 3,124 | 47.1% |
| Rust | 23 | 8,423 | 2,835 | 33.7% |
| Properties | 5 | 2,887 | 2,361 | 81.8% |
| SQL | 433 | 11,796 | 1,458 | 12.4% |
| Go | 34 | 6,983 | 1,288 | 18.4% |
| TOML | 8 | 334 | 220 | 65.9% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,385 | 1,271 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,433 | 1,180 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,431 | 1,180 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,228 | 1,027 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,228 | 1,027 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 879 | 796 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 991 | 712 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 1,147 | 646 |
| `deploy/docker/compose/app.yml` | config | YAML | 724 | 643 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 769 | 561 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 979 | 559 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 648 | 529 |
| `scripts/local/start-all.sh` | script | Shell | 639 | 513 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationServiceTest.java` | test | Java | 814 | 503 |
| `pom.xml` | config | XML | 823 | 500 |
| `scripts/local/sim-harness.sh` | script | Shell | 624 | 482 |
| `scripts/local/be-acceptance.sh` | script | Shell | 614 | 481 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | prod | Java | 1,028 | 451 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceServiceTest.java` | test | Java | 793 | 438 |
| `scripts/local/pre-push-sdk-checks.sh` | script | Shell | 573 | 437 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
