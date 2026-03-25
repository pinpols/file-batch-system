# 压测脚本与容量基线

## 概述

| 项目 | 说明 |
|---|---|
| 工具 | Gatling 3.12 Java DSL |
| 模块 | `load-tests/`（独立 Maven 模块，不加入主构建） |
| 目标 | 获取上线前容量基线，为 K8s 资源规格、HPA 阈值、数据库连接池上限提供实测数据 |

---

## 测试场景

| 模拟类 | 目标接口 | 用途 |
|---|---|---|
| `JobLaunchSimulation` | `POST /api/triggers/launch`（trigger:8081） | 写入路径吞吐/延迟基线 |
| `ConsoleQuerySimulation` | `GET /api/console/query/instances` 等（console:8080） | 查询路径吞吐/延迟基线 |
| `CapacityBaselineSimulation` | 写入 30 % + 查询 70 % 混合 | **生产前容量基线**（分级爬坡找饱和点） |

---

## 前置条件

### 1. 种子数据

目标环境必须预先存在以下数据（否则 launch 请求会因找不到 job_definition 而返回 404）：

```sql
-- 在 batch_platform 库中插入专用压测作业定义
INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, timezone, priority, queue_code, worker_group, trigger_mode,
    dag_enabled, shard_strategy, retry_policy, retry_max_count, timeout_seconds,
    enabled, version)
VALUES ('t1', 'E2E_IMPORT_LOAD', 'Load Test Import Job', 'IMPORT', 'LOAD_TEST',
    'MANUAL', 'UTC', 5, 'load-q', 'import', 'API', false, 'NONE',
    'NONE', 0, 0, true, 1);

INSERT INTO batch.workflow_definition (tenant_id, workflow_code, workflow_name,
    workflow_type, version, enabled)
VALUES ('t1', 'E2E_IMPORT_LOAD', 'Load Test WF', 'DAG', 1, true);

INSERT INTO batch.trigger_request (tenant_id, request_id, trigger_type, job_code,
    biz_date, dedup_key, request_status, trace_id)
VALUES ('t1', 'load-seed-001', 'API', 'E2E_IMPORT_LOAD',
    '2026-01-15', 'load-dedup-seed', 'ACCEPTED', 'load-trace');
```

SQL 文件：`docs/sql/load-test/load-test-seed.sql`

### 2. 依赖服务

- PostgreSQL、Kafka、MinIO 均已就绪
- batch-trigger（8081）和 batch-console-api（8080）已启动且 `/actuator/health` 返回 UP

---

## 快速执行

```bash
cd load-tests

# 单场景：写入路径（本地，20 并发，120 秒）
mvn gatling:test -Dsimulation=JobLaunchSimulation

# 单场景：查询路径
mvn gatling:test -Dsimulation=ConsoleQuerySimulation

# 容量基线（5 阶段爬坡，25→200 并发，每阶段 60 秒）
mvn gatling:test -Dsimulation=CapacityBaselineSimulation \
    -DjobCode=E2E_IMPORT_LOAD -DtenantId=t1

# Staging 环境，50 并发，300 秒
mvn gatling:test -Pstageing -Dsimulation=JobLaunchSimulation \
    -Dusers.peak=50 -Dduration.seconds=300

# 查看报告
open target/gatling-results/*/index.html
```

---

## 关键参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `trigger.baseUrl` | `http://localhost:8081` | batch-trigger 地址 |
| `console.baseUrl` | `http://localhost:8080` | batch-console-api 地址 |
| `tenantId` | `t1` | 压测租户 ID |
| `jobCode` | `E2E_IMPORT_LOAD` | 压测作业 code |
| `bizDate` | `2026-01-15` | 业务日期 |
| `users.peak` | `20` | 峰值并发用户数 |
| `duration.seconds` | `120` | 稳定压测时长（秒） |
| `ramp.seconds` | `30` | 爬坡时长（秒） |
| `slo.write.p95ms` | `500` | 写入 p95 阈值（ms） |
| `slo.read.p99ms` | `300` | 读取 p99 阈值（ms） |
| `slo.maxErrorPct` | `1.0` | 最大错误率（%） |
| `console.authToken` | `Bearer load-test-token` | console-api 鉴权 token |

---

## SLO 定义

| 接口 | 指标 | 目标 | 上线门禁 |
|---|---|---|---|
| `POST /api/triggers/launch` | p95 | < 500 ms | ✅ |
| `GET /api/console/query/instances` | p99 | < 300 ms | ✅ |
| 全局 | 错误率 | < 1 % | ✅ |
| `POST /api/triggers/launch` | 吞吐 | ≥ 50 req/s @ 100 并发 | ⚠️ 待实测填写 |
| DB 连接池 | 队列等待 | < 10 ms | ⚠️ 待实测填写 |

---

## 容量基线记录表（待填写）

运行 `CapacityBaselineSimulation` 后，将各阶段数据记录于此：

| 并发用户数 | 写 p50 | 写 p95 | 读 p50 | 读 p99 | 错误率 | 吞吐 (req/s) | 结论 |
|---|---|---|---|---|---|---|---|
| 25 | — | — | — | — | — | — | 待测 |
| 50 | — | — | — | — | — | — | 待测 |
| **100** | — | — | — | — | — | — | **基线目标** |
| 150 | — | — | — | — | — | — | 待测 |
| 200 | — | — | — | — | — | — | 待测 |

**饱和点**：\_\_\_ 并发用户（p95 首次超过 500 ms 或错误率首次超过 1 %）

---

## 资源规格建议（基线完成后填写）

| 服务 | CPU request | CPU limit | Memory request | Memory limit | 最大副本数（HPA） |
|---|---|---|---|---|---|
| batch-trigger | — | — | — | — | — |
| batch-orchestrator | — | — | — | — | — |
| batch-worker-import | — | — | — | — | — |
| batch-worker-export | — | — | — | — | — |
| batch-worker-dispatch | — | — | — | — | — |
| batch-console-api | — | — | — | — | — |
| PostgreSQL 连接池上限 | — | — | — | — | — |
| Kafka 分区数建议 | — | — | — | — | — |

---

## CI 集成建议

将 `JobLaunchSimulation`（20 并发，120 秒）加入 staging 流水线的"上线前验收"阶段：

```yaml
# .github/workflows/staging-gate.yml 片段
- name: Load test gate
  run: |
    cd load-tests
    mvn gatling:test -Pstageing \
        -Dsimulation=JobLaunchSimulation \
        -Dusers.peak=50 -Dduration.seconds=120
```

构建结果中 Gatling HTML 报告存为 artifact，供 review 使用。
