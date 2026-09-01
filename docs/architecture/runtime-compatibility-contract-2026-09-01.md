# 前后端运行时兼容约束

## 目的

本文件是 file-batch-system 后端、配套 `../batch-console` 前端、五语言 SDK、测试与运维脚本的运行时基线。版本升级必须同时检查本文件、根 `pom.xml`、前端 `package.json`、Compose 环境文件和 CI workflow，不能只改单个 README。

## 固定基线

| 组件 | 基线/范围 | 约束来源 | 说明 |
|---|---|---|---|
| 后端 JDK | Java 21 | 根 `pom.xml`、Docker、CI | `maven.compiler.release=21`，不支持 JDK 17 或更低版本 |
| 后端框架 | Spring Boot 4.1.0 | 根 `pom.xml` | 依赖版本由 BOM 和集中属性管理 |
| Maven | Maven Wrapper / Maven 3.x | `.mvn`、CI | 本地优先使用 `./mvnw`，避免宿主机 Maven 漂移 |
| 前端 Node.js | Node 22.x | `../batch-console/package.json`、`.nvmrc`、`.node-version`、CI | `engines.node` 为 `>=22 <23` |
| 前端包管理 | npm lockfile | `../batch-console/package-lock.json` | CI 使用 `npm ci`，禁止混用 yarn/pnpm lockfile |
| Python SDK | Python 3.12+ | `sdk/python/README.md`、CI | async-only，依赖由 SDK 自身工具链管理 |
| Go SDK | Go 1.25+ | `sdk/go/go.mod`、CI | core 与 Kafka nested module 分离 |
| Rust SDK | Rust 1.75+ | `sdk/rust/Cargo.toml`、CI | edition 2021；HTTP/Kafka 为可选 feature |
| TypeScript SDK | Node 22.x | `sdk/typescript/package.json`、CI | 使用 Node 22 原生 type-stripping |
| PostgreSQL | 本地/Compose 17；发布兼容下限需按 migration gate 证明 | `.env.example`、Compose、发布 runbook | 新能力以 PG 17 验证；不能把“理论兼容下限”当作当前实测基线 |
| Kafka | Compose 4.1.2；生产按兼容矩阵 | `.env.example`、Compose | 生产需确认 KRaft、topic replication 和客户端兼容性 |
| Valkey/Redis | Compose 8.1；Redis 协议兼容 | `.env.example`、Compose | quota、ShedLock、缓存和 Pub/Sub 依赖实际命令兼容性 |
| MinIO | 由 `.env.example` 集中管理 tag | Compose | 使用 S3 协议，不把 MinIO SDK 绑定为业务模型 |

## 运行方式

| 运行方式 | 地址规则 | 适用范围 |
|---|---|---|
| Docker Compose 应用 | PostgreSQL `postgres-primary:5432`、Kafka `kafka:29092`、MinIO `minio:9000` | Compose 内后端服务 |
| 宿主机 JVM | PostgreSQL `PGHOST/PGPORT`、Kafka `KAFKA_HOST_BOOTSTRAP`、S3 endpoint 环境变量 | `scripts/local/start-all.sh` 等裸 JVM 联调 |
| 宿主机测试/运维脚本 | 通过 `PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE` 直连 | `scripts/ops`、`load-tests` |
| Docker 客户端模式 | `BATCH_PG_CLIENT_MODE=docker`，使用 `PG_CONTAINER` 内客户端 | 无宿主机 `psql` 或保持容器内网络时 |
| Python DB fallback | `BATCH_PG_CLIENT_MODE=python`，安装 `scripts/requirements-postgres.txt` | 无宿主机 `psql` 且不使用 Docker 时 |

禁止把 Docker 网络名（如 `postgres-primary`、`kafka`、`minio`）写入宿主机 JVM 默认配置，也禁止把 `localhost` 写入 Compose 服务间默认配置。

## 测试边界

- 单元测试必须可在无 Docker、无 PostgreSQL 客户端的环境运行。
- Testcontainers IT/E2E 明确依赖 Docker；无 Docker 时按测试类配置跳过或立即给出明确错误。
- Sim 全链路和故障注入是 Docker-only，不伪装为纯宿主机测试。
- 运维和压测脚本不得静默跳过 SQL；无 `psql` 时使用 Python fallback 或明确失败。
- 前端 CI 固定 Node 22，执行 `npm ci`、typecheck、lint、unit、build；E2E 依赖后端可访问和 Playwright 浏览器。

## 版本漂移门禁

版本变更必须同时检查：

1. 根 `pom.xml`、所有 Dockerfile 和 Compose tag。
2. `../batch-console/package.json`、`.nvmrc`、`.node-version`、前端 Dockerfile 和 CI。
3. 五语言 SDK 的 manifest、README 和对应 CI workflow。
4. 连接地址环境变量、宿主机/容器模式和脚本公共入口。
5. 运行时镜像、Testcontainers 镜像与本地 Compose 的主版本。

本文件只记录已批准的基线和边界，不代表所有矩阵组合已经实跑；真实验证结果应追加到对应 `docs/verifications/` 报告。
