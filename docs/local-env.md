# 本地环境入口

> 基于 `.env.example` 默认值。如果你改过 `.env.local` 里的端口，以实际配置为准。
>
> 启动命令：
> ```bash
> # 基础服务
> docker compose --env-file .env.local up -d
> # 可观测性（Prometheus / Grafana / Jaeger / Loki）
> docker compose -f docker-compose.observability.yml --env-file .env.local up -d
> ```

---

## 应用服务

| 服务 | 地址 | 备注 |
|------|------|------|
| Console API | http://localhost:18080 | 批量调度控制台后端 |
| Trigger | http://localhost:18081 | 定时触发器 |
| Orchestrator | http://localhost:18082 | 调度编排器 |

---

## 基础设施

| 服务 | 地址 | 账号 | 密码 |
|------|------|------|------|
| PostgreSQL | localhost:15432 | `batch_user` | `batch_pass_123` |
| Redis | localhost:16379 | — | — |
| Kafka | localhost:19092 | — | — |
| MinIO API | http://localhost:19000 | `minioadmin` | `minioadmin123` |
| MinIO 控制台 | http://localhost:19001 | `minioadmin` | `minioadmin123` |

PostgreSQL 数据库名：`batch_platform`，业务库 schema：`biz`（`batch_business`）

---

## 可观测性

| 服务 | 地址 | 账号 | 密码 |
|------|------|------|------|
| Grafana | http://localhost:3000 | `admin` | `admin` |
| Prometheus | http://localhost:19090 | — | — |
| Jaeger UI | http://localhost:16686 | — | — |
| Loki | http://localhost:3100 | — | — （API only，通过 Grafana 查询）|

### Exporter 端口（Prometheus scrape，一般不需要直接访问）

| Exporter | 地址 |
|----------|------|
| Redis Exporter | http://localhost:19121/metrics |
| Postgres Exporter | http://localhost:19187/metrics |
| Kafka Exporter | http://localhost:19308/metrics |
| Node Exporter | http://localhost:19100/metrics |
| cAdvisor | http://localhost:19101/metrics |
