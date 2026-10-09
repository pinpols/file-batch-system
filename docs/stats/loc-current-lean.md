# 精简代码量统计 — 当前快照

> 口径：git 跟踪文件；排除 `docs/`、构建产物、生成物、依赖目录和 Flyway migration；主指标为 **Lean logical LOC**，用语句/块/配置项计数削弱格式化换行带来的膨胀。生成来源：HEAD `29dc65387`。

## 总览

| Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---:|---:|---:|---:|
| 4,864 | 512,053 | 264,276 | 51.6% |

## 按用途

| Group | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| prod | 2,782 | 252,799 | 115,896 | 45.8% |
| test | 1,269 | 187,962 | 103,332 | 55.0% |
| script | 658 | 55,089 | 34,742 | 63.1% |
| config | 59 | 8,552 | 6,200 | 72.5% |
| infra-config | 32 | 5,373 | 3,934 | 73.2% |
| sql | 64 | 2,278 | 172 | 7.6% |

## 按语言

| Language | Files | Physical LOC | Lean logical LOC | Lean/Physical |
|---|---:|---:|---:|---:|
| Java | 3,564 | 372,041 | 192,577 | 51.8% |
| Shell | 209 | 31,709 | 24,911 | 78.6% |
| Python | 257 | 36,420 | 18,853 | 51.8% |
| YAML | 100 | 12,807 | 9,746 | 76.1% |
| XML | 180 | 21,672 | 6,803 | 31.4% |
| TypeScript | 37 | 6,667 | 3,141 | 47.1% |
| Rust | 23 | 8,499 | 2,856 | 33.6% |
| Properties | 5 | 2,897 | 2,371 | 81.8% |
| SQL | 447 | 11,958 | 1,496 | 12.5% |
| Go | 34 | 7,049 | 1,302 | 18.5% |
| TOML | 8 | 334 | 220 | 65.9% |

## 最大文件（按 Lean logical LOC）

| File | Group | Language | Physical LOC | Lean logical LOC |
|---|---|---|---:|---:|
| `load-tests/scripts/run-p2-capacity-profile.sh` | script | Shell | 1,385 | 1,271 |
| `batch-common/src/main/resources/messages.properties` | prod | Properties | 1,438 | 1,185 |
| `batch-common/src/main/resources/messages_zh_CN.properties` | prod | Properties | 1,436 | 1,185 |
| `deploy/docker/observability/prometheus-batch-rules.yml` | config | YAML | 1,228 | 1,027 |
| `helm/batch-platform/files/prometheus-batch-rules.yml` | infra-config | YAML | 1,228 | 1,027 |
| `load-tests/scripts/run-control-plane-worker-benchmark.sh` | script | Shell | 879 | 796 |
| `helm/batch-platform/values.yaml` | infra-config | YAML | 1,002 | 722 |
| `deploy/docker/compose/app.yml` | config | YAML | 732 | 650 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java` | prod | Java | 996 | 597 |
| `scripts/local/validate-seed-scenarios.sh` | script | Shell | 769 | 561 |
| `scripts/fix-fixture-xlsx.py` | script | Python | 979 | 559 |
| `scripts/ci/run-full-regression.sh` | script | Shell | 661 | 542 |
| `scripts/local/start-all.sh` | script | Shell | 645 | 518 |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationServiceTest.java` | test | Java | 814 | 503 |
| `pom.xml` | config | XML | 823 | 500 |
| `scripts/local/sim-harness.sh` | script | Shell | 635 | 492 |
| `scripts/local/be-acceptance.sh` | script | Shell | 614 | 481 |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java` | prod | Java | 1,031 | 460 |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceServiceTest.java` | test | Java | 793 | 438 |
| `scripts/local/pre-push-sdk-checks.sh` | script | Shell | 572 | 434 |

## 复跑

```bash
python3.12 scripts/dev/lean-loc-report.py --write docs/stats/loc-current-lean.md
```

## 注意

- 这个口径不是编译器 AST 精确复杂度，只是比 `wc -l` 更接近“维护体量”的工程统计。
- Java/Go/Rust/TypeScript 按分号、声明块和注解近似计数；链式调用和多行参数列表通常只算 1 个语句。
- Python 使用 `ast` 语句节点计数；SQL 按语句计数；YAML/XML 按配置项/标签计数。
- `docs/` 和 `db/migration/` 不进入主指标，避免文档、历史迁移和机器生成文件把代码体量撑大。
