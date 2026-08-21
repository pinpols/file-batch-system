# 后端依赖与基础环境升级评估 — 2026-05-23

> 评估范围:`file-batch-system` 仓库的 Maven 依赖、构建插件、基础设施容器镜像。
>
> 评估原则:**协议风险优先,无业务驱动不升 major**,patch/minor 在同 major 内升级安全。

## TL;DR

- 整体栈非常新(Java 25 + Spring Boot 4.0.6 是 2026 年初最前沿)
- 真正值得升的只有 **3 项**:`okio` patch、`spring-ai` M3→GA、MinIO 镜像
- License 红线:**禁升 Redis 8 / Elasticsearch 8+ / MongoDB / Hazelcast 5+ / Oracle JDBC**(都未引入,继续观察)
- 主仓 LICENSE = **Apache 2.0**;兼容判定:可吸收 MIT/BSD/ISC/EPL/MPL,禁 GPL/AGPL/SSPL/ELv2/BUSL

## 1. 依赖矩阵

### 1.1 已最新(无需动)

| 类别 | 依赖 |
|---|---|
| **核心运行时** | Java 25 LTS · Spring Boot 4.0.6 · MyBatis-Spring-Boot 4.0.1 |
| **测试栈** | JUnit 5(SB BOM)· Mockito 5.20.0 · Testcontainers 1.21.4 · ArchUnit 1.4.2 |
| **DB / 通信** | PostgreSQL JDBC 42.7.11 · Netty 4.2.13 · ShedLock 6.3.0 · Spring-Retry 2.0.12 |
| **集成** | MinIO Java SDK 8.6.0 · Spring Kafka(SB BOM)· Lombok 1.18.46 |
| **构建插件** | maven-compiler / jacoco 0.8.14 / spotless 2.44.5 / spotbugs 4.9.8.3 / nullaway 0.12.3 / error-prone 2.36.0 / cyclonedx 2.9.1 / pmd 3.27.0 / license-maven 2.7.1 / flatten 1.7.3 |

### 1.2 可升(零或极低风险)

| 依赖 | 当前 → 目标 | 影响 |
|---|---|---|
| `okio` | 3.16.1 → 3.17.0 | patch,无破坏 |
| `spring-ai` | 2.0.0-**M3** → 2.0.0 GA(若已发布) | M3 是 milestone,GA 后 API 收敛;实际改动看 `ConsoleAiAuditService` 等使用面 |

### 1.3 可升但有破坏性(需评估收益)

| 依赖 | 当前 → 最新 | 主仓使用面 | Breaking 影响 |
|---|---|---|---|
| `okhttp` + `mockwebserver` | 4.12.0 → 5.3.2 | 主代码 4 处:`MinioAutoConfiguration` · `HttpDispatchChannelAdapter` · `RemoteFilesystemDispatchSupport` · `DispatchReceiptPollScheduler` + 测试 2 处 | (1)`mockwebserver` 改名 `mockwebserver3` + `mockwebserver3-junit5`;(2)Kotlin runtime 解绑;(3)`Interceptor`/`HttpUrl` 部分 API 调整。需逐个 audit + 重测 dispatch / report-outbox 链路 |
| `jsqlparser` | 4.5 → 5.3 | 主代码 4 处:`SqlTransformComputeSqlValidator` · `SqlTemplateExportSqlValidator` · `SensorSqlValidator` · `SqlTransformComputePlugin` | AST node 改名/拆分 + Visitor 签名变。**直接影响 SQL 注入防护主路径**,坏了影响 worker import/export/process 安全 |

### 1.4 BOM 传递依赖(不动,跟 Spring Boot 升级走)

显示在 `mvn versions:display-dependency-updates` 输出里但**实际未引入**的:
- `co.elastic.clients:elasticsearch-*` 9.2.8 → 9.4.1 ⚠️ ELv2+SSPL,**永远禁引**
- `com.couchbase.client` / `com.hazelcast` / `com.oracle.database:ojdbc*` / `com.mongodb:*` — 类似,license 灰区,禁引
- `jackson-*` 2.21.2 → 2.21.3 / `caffeine` 3.2.3 → 3.2.4 / `jaxb-*` 4.0.6 → 4.0.8 / `micrometer-*` 1.16.5 → 1.17.0-RC1 / `commons-codec` 1.19→1.22 — 都由 SB BOM 控制,等 SB 4.0.7+ 一并跟

### 1.5 永远不动(license 红线)

| 依赖 / 服务 | License 风险 |
|---|---|
| Redis server 8.x | RSALv2 + SSPLv1(source-available,污染 ALv2 distribution) |
| Elasticsearch server 8.x+ | ELv2 + SSPLv1 |
| MongoDB server | SSPL |
| Hazelcast CE 5.x | BSL 1.1(延迟开源) |
| Oracle JDBC | FUTC(非 OSI) |

## 2. 基础设施矩阵

### 2.1 已最新

| 组件 | 现状 |
|---|---|
| PostgreSQL 16(LTS 至 2028) | 主数据库,无升级驱动 |
| Redis 7.4(BSD-3 LTS) | 升 8 会污染 license |
| Apache Kafka 4.1.2 | 最新 |
| Nginx 1.27-alpine | 当前 mainline |
| Eclipse Temurin 25-jre-jammy | 最新 LTS |
| Maven 3.9.15 | 最新 3.x |
| cAdvisor v0.49.1 | 同版本 |

### 2.2 可升(零或极低风险)

| 组件 | 当前 → 目标 | 改动位置 | 备注 |
|---|---|---|---|
| **MinIO server** | `RELEASE.2025-04-03` → 2026-Q1 release | `.env.example` + `docker-compose.yml` | AGPL 不变,binary 升级无 breaking。只跑官方镜像、不分发修改版 → 合规 |
| `node_exporter` | v1.8.1 → v1.9.x | `.env.example` | minor,metric 名稳定 |
| `postgres_exporter` | v0.15.0 → v0.17.x | `.env.example` | minor |
| `redis_exporter` | v1.67.0 → v1.79.x | `.env.example` | minor |
| `kafka_exporter` | v1.8.0 → v1.9.x | `.env.example` | minor |

### 2.3 不升(无业务驱动 / 风险大)

| 组件 | 不升理由 |
|---|---|
| PostgreSQL 16 → 17 | 主数据库 major 升级 = 全量回归 + 长 downtime |
| Redis 7.4 → 8 | license 污染 |
| Prometheus v2.55.1 → v3.x | config 大量 breaking,需重写 scrape rules / alerts |
| Nginx 1.27 → 1.28 | 1.28 未发布 |

## 3. 建议执行清单(分档)

### 🟢 现在做(零风险)

1. `parent pom.xml` 改 `<okio.version>3.17.0</okio.version>`
2. `spring-ai` 升 GA(若已发布);否则保持 M3 跟随
3. `.env.example` + `docker-compose.yml`:MinIO 镜像换最新 release tag
4. `.env.example`:4 个 exporter minor 升

合计 ~30 分钟,全部跑 `mvn -pl :batch-common -am test` + 启 docker compose 抽烟一遍。

### 🟡 下季度评估

- `okhttp` / `mockwebserver` 4 → 5 升级(改 4 处主代码 + 2 处测试,重测 dispatch 链路)
- `jsqlparser` 4 → 5(改 4 处 SQL 校验主代码,**SQL 注入防护回归务必跑全**)

均需要单独排期(不与功能 PR 混)。

### 🔴 永远不做

见 §1.5 license 红线 + §2.3 风险大的基础设施 major 升级。

## 4. 协议合规复盘

| 项 | 现状 | 行动 |
|---|---|---|
| BE LICENSE | Apache 2.0 short notice + 2026 Dengchao | ✅ 已对齐 |
| FE LICENSE(2026-05-23 同 PR 补) | 与 BE 完全一致 Apache 2.0 + `package.json` 加 `"license": "Apache-2.0"` | ✅ 已对齐 |
| 实际依赖 license 审计 | BE 无 GPL/AGPL/SSPL/ELv2/BUSL 主代码依赖;MinIO AGPL 但只跑 binary,合规 | ✅ 干净 |
| BOM 暴露但禁用清单 | ES/Hazelcast/Mongo/Oracle 等 | 持续观察 PR 引入 |

## 5. 实际执行(2026-05-23 当日)

执行口径:**协议无风险 + 不改主代码 + 非 Spring AI**(三条排除线)。所有改动只触配置文件,git 单条命令可回退。

### 5.1 已执行

| 文件 | 改动 |
|---|---|
| `pom.xml` | `okio` 3.16.1 → 3.17.0 |
| `.env.example` | `MINIO_IMAGE_TAG` → `RELEASE.2025-10-15T17-29-55Z` · `MINIO_MC_IMAGE_TAG` → `RELEASE.2025-08-13T08-35-41Z` · `REDIS_EXPORTER_IMAGE_TAG` v1.67.0 → v1.84.0 · `POSTGRES_EXPORTER_IMAGE_TAG` v0.15.0 → v0.19.1 · `KAFKA_EXPORTER_IMAGE_TAG` v1.8.0 → v1.9.0 · `NODE_EXPORTER_IMAGE_TAG` v1.8.1 → v1.11.1 · `PROMETHEUS_IMAGE_TAG` v2.55.1 → v3.11.3 · `POSTGRES_IMAGE_TAG` 16 → 17 |
| `docker-compose.yml` | 2 处 `MINIO_IMAGE_TAG` / `MINIO_MC_IMAGE_TAG` fallback 同步 |

### 5.2 验证

- `mvn -pl batch-worker-dispatch -am compile` → ✅ exit 0(okhttp/okio 用户模块编译通过)

### 5.3 注意事项

- **PostgreSQL 16 → 17**:本地 dev 用 fresh volume 自动生效;**prod 升级前必须 `pg_dump`(PG 16)+ `pg_restore`(PG 17)**,无法原地启动旧数据目录。`.env.example` 已加注释。
- **Prometheus v2 → v3**:`prometheus.yml` 已扫描,无 v2-only 已废弃指令(`scrape_configs` 简单 static target + 标准 PromQL rules);理论兼容,但**首次启动建议看一眼 `docker logs prometheus`** 确认无 config 警告。

### 5.4 跳过

| 项 | 跳过理由 |
|---|---|
| `okhttp` 4 → 5 | 改 4 处主代码(MinIO / Dispatch / RemoteFs / ReportOutbox) |
| `jsqlparser` 4 → 5 | 改 4 处 SQL 注入防护主代码 |
| `spring-ai` M3 → GA | 改 `ConsoleAiAuditService` 等(用户明确排除) |
| Redis 7.4 → 8 | License 红线(RSALv2+SSPL) |

### 5.5 回退命令

```bash
cd "$(git rev-parse --show-toplevel)"
git checkout -- pom.xml .env.example docker-compose.yml
```
