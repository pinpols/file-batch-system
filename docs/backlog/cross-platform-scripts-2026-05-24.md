# 跨平台脚本治理 — 中长期 backlog

日期:2026-05-24
状态:已识别 + 决策延后

## 背景

2026-05-24 排查"BE / FE 脚本在 Linux 上能不能跑"时,识别到 8 个潜在不兼容点。**5 个已修 + 3 个保留**:

| # | 项 | 已修? |
|---|---|---|
| 1 | BSD `stat -f` (start-all.sh / restart.sh) | 早就有 `\|\| stat -c` fallback ✅ |
| 2 | `/bin/sh` shebang (init-kafka / init-minio) | 真 POSIX,无需改 ✅ |
| 3 | docker-path.sh 只搜 macOS 路径 | ✅ 加了 Linux / WSL / Rancher Desktop / NixOS 等 8 个候选 |
| 4 | run-full-regression.sh 写死 macOS 路径 | ✅ docker + kubectl resolve 都扩到 8/6 候选 |
| 5 | `/Users/dengchao` 个人路径 (be / fe acceptance) | ✅ 改 `$ROOT_DIR/../sibling-repo` 相对路径 |
| **6** | docker 容器名硬写(`batch-postgres-primary` 等) | ⏸ **本文档备忘**,见下方 D-2 |
| **7** | `localhost:PORT` 在 ops 脚本 | ⏸ **本文档备忘**,见下方 D-1 |
| 8 | `lsof -ti tcp:PORT` BSD 风味 | macOS / Linux 都接受,真兼容 ✅ |

## D-1:ops/* 脚本 `localhost:PORT` 硬编码 → env-var driven

### 现状

- `scripts/ops/inspect-observability.sh` `BATCH_OBSERVABILITY_KAFKA_BOOTSTRAP_SERVERS=localhost:19092`(注释默认)
- `scripts/ops/inspect-all.sh` 同款
- `scripts/ops/heal-zombie-pipelines.sh` 默认 `localhost:15432`
- `scripts/local/gen-default-tenant-excel.py` `localhost:12222` / `localhost:1025`(写入 Excel 模板,不算运行时)

### 为什么没修

实际上 ops 脚本**大多已是 env-override 模式**(注释明确说明可 `export` 覆盖)。问题不是 hardcode,是**分散且无统一 env naming convention**:有的用 `BATCH_OBSERVABILITY_*`,有的用 `PG_HOST`,有的写在注释里看不到。

### 治根方案(若有真需求才做)

模仿 `scripts/local/health-check-infra.sh` 顶部的 env-var 表:每个 ops 脚本顶部统一注释 + 默认值 fallback。参考:

```bash
# === env-var 配置(优先级:命令行 > 环境变量 > 默认值)===
PG_PRIMARY_HOST="${PG_PRIMARY_HOST:-localhost}"
PG_PRIMARY_PORT="${PG_PRIMARY_PORT:-${POSTGRES_PORT:-15432}}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:${KAFKA_HOST_PORT:-19092}}"
```

**何时做**:同一份脚本要跑本地 + staging + CI 三种 host:port 时,集中改一次。

### 涉及文件(估计)

- `scripts/ops/inspect-all.sh`
- `scripts/ops/inspect-observability.sh`
- `scripts/ops/inspect-orchestrator-leasing.sh`(待审)
- `scripts/ops/heal-*.sh`(7 个)
- `scripts/ops/trigger-compensation.sh`(待审)

工程量:每个文件 ~10 行,总计 1-2 小时。

## D-2:docker 容器名硬写(`batch-postgres-primary` 等)

### 现状

- `scripts/local/start-all.sh:227` `docker exec batch-kafka /opt/kafka/bin/kafka-topics.sh ...`
- `scripts/local/restart.sh:61` `docker exec batch-postgres-primary ...`(stat-f 同行)
- `scripts/local/validate-seed-scenarios.sh:34` `docker exec batch-postgres ...`
- 多处 `docker exec batch-postgres-primary psql ...`

### 为什么没修

容器名 = `${COMPOSE_PROJECT_NAME}-${SERVICE}` 格式,默认 project name 是仓库目录名(=`file-batch-system`)→ 容器名前缀 `batch-`(因为 docker-compose 服务名是 `postgres-primary` / `kafka` 等)。

**只要不改 `COMPOSE_PROJECT_NAME`**(默认 `batch-platform`,见 `start-all.sh:32`),容器名一直对得上。换 K8s / project name 才挂。

### 治根方案

把所有 `docker exec batch-X` 改成两种之一:

**方案 A**:用 docker compose 解析(local 友好)
```bash
docker compose -f docker-compose.yml --env-file .env.local exec -T postgres-primary psql ...
# 或:
PG_CONTAINER=$(docker compose ps -q postgres-primary)
docker exec "$PG_CONTAINER" psql ...
```

**方案 B**:协议层探测(跨平台彻底,跟 health-check-infra.sh 一致)
```bash
psql -h "$PG_PRIMARY_HOST" -p "$PG_PRIMARY_PORT" -U "$PG_USER" -d "$PG_DB" -c "..."
```

A 损耗小、贴合现状;B 让脚本能在 K8s 没 docker 的环境也跑(但要求本机装 psql / kafka-cli)。

**何时做**:要在 K8s exec 容器里跑这些 ops 脚本,或要把脚本独立跑在 staging admin 机上时。

### 涉及文件

- `scripts/local/start-all.sh`(~10 处 docker exec)
- `scripts/local/restart.sh`(~2 处)
- `scripts/local/validate-seed-scenarios.sh`(1 处)
- `scripts/ops/*.sh`(部分,跟 D-1 重叠)

工程量:中等-大,涉及核心 start/restart 流程,要小心回归。建议**有真用例**再动(无用例时收益 0)。

## 决策

**保留现状**。把本文档留作:

1. 后续真要做 D-1 / D-2 时的起点
2. PR review 时质问"为什么不修"的答疑
3. 跟 [`docs/runbook/jvm-tuning-and-profiling.md`](../runbook/jvm-tuning-and-profiling.md) 类似的"已识别 + 决策延后"备忘范本

跨平台核心已修(3、4、5),无 hard block 的常用场景。
