# 租户自托管 Worker 样例工程

每个语言一个**可运行**的最小 worker 样例,演示用对应 SDK 接入平台:实现 echo handler → register(`workerGroup=sdk-self-hosted`)→ 订阅 node-direct 派单 topic → claim → execute → report。SDK 总入口见 [`sdk/README.md`](../../sdk/README.md)。

| 样例 | 用的 SDK | 备注 |
|---|---|---|
| [`sample-tenant-worker-java/`](sample-tenant-worker-java/) | `sdk/java/core` | 纯 core,`maven-jar-plugin` + `lib/` classpath(非 fat-jar) |
| [`sample-tenant-worker-java-spring/`](sample-tenant-worker-java-spring/) | `sdk/java/spring` | Spring Boot starter 自动装配 |
| [`sample-tenant-worker-python/`](sample-tenant-worker-python/) | `sdk/python` | async,3.12+,依赖 `batch-worker-sdk` |
| [`sample-tenant-worker-go/`](sample-tenant-worker-go/) | `sdk/go` | kafka adapter;参照实现 |
| [`sample-tenant-worker-typescript/`](sample-tenant-worker-typescript/) | `sdk/typescript` | kafkajs adapter,Node ≥ 25 |
| [`sample-tenant-worker-rust/`](sample-tenant-worker-rust/) | `sdk/rust` | `reqwest` transport + `rdkafka`(`http` + `kafka` feature) |

## 怎么跑

最省事是用本地全链路脚本(真栈起好后自动 seed key / 建 echo job / 建 topic / 起样例 / 逐阶段断言 / 清理):

```bash
bash scripts/local/sdk-e2e-local.sh <go|python|typescript|java|rust>
```

前置(真栈):orchestrator :18082 + trigger :18081 + postgres :15432 + kafka,平台库已迁 schema,`atomic_shell_demo` 种子已 load(`scripts/data/load-system-test-data.sh`)。覆盖矩阵与已知 wire 坑见 [`docs/sdk/local-e2e-coverage.md`](../../docs/sdk/local-e2e-coverage.md)。

## 手动起单个样例

各样例 README 有自己的环境变量(`BATCH_BASE_URL` / `BATCH_API_KEY` / `BATCH_TENANT_ID` / `BATCH_WORKER_CODE` + Kafka bootstrap;Python 用 `BATCH_SDK_*`,Java 用 `BATCH_KAFKA`)。进对应目录看 README。

> 这些是**演示骨架**,不是生产模板:生产 handler、错误分类、幂等/重试装饰、SASL 凭据管理按各 SDK README 的 P1 章节自行接。
