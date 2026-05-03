# 种子数据 + ADR-010 链路验证脚本 — 用法手册

> **脚本**: `scripts/local/validate-seed-scenarios.sh`
> **目的**: 一键回归 V82-V85 schema + ADR-010 异步链路 + 多租隔离 + 异常路径 + worker 链路覆盖
> **退出码**: 0 = 全过；非 0 = 至少 1 项 FAIL

---

## 0. 前置条件

| 依赖 | 检查 |
|---|---|
| Docker 全栈 healthy | `docker ps` — 7 个 batch-* 容器都是 healthy |
| 种子数据已加载 | platform_seed.sql + business_seed.sql + multi-tenant-seed.sql |
| Migration ≥ V86 | `SELECT max(version) FROM batch.flyway_schema_history` |

如果种子未加载：
```bash
# 1. platform 基础种子
docker cp scripts/db/test-seed/platform_seed.sql batch-postgres:/tmp/
docker exec batch-postgres psql -U batch_user -d batch_platform -f /tmp/platform_seed.sql

# 2. business 数据（biz.* 表）
docker cp scripts/db/test-seed/business_seed.sql batch-postgres:/tmp/
docker exec batch-postgres psql -U batch_user -d batch_business -f /tmp/business_seed.sql

# 3. 多租户种子（ta/tb/tc — STRICT=0 默认覆盖需要）
docker cp batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql batch-postgres:/tmp/
docker exec batch-postgres psql -U batch_user -d batch_platform -f /tmp/multi-tenant-seed.sql
```

---

## 1. 三个使用场景

### 1.1 默认 — 36 项快速回归（~4 分钟）

```bash
./scripts/local/validate-seed-scenarios.sh
```

覆盖 31 项基础 + 跑前 PRE_CLEANUP（默认开），不跑 advanced 段。

### 1.2 + advanced 段（orch 运维 API + console 鉴权）

```bash
ADVANCED=1 ./scripts/local/validate-seed-scenarios.sh
```

加 5 项 advanced（共 36 PASS / 1 SKIP）。

### 1.3 严格 SUCCESS 模式 — 必须 worker 真完成才 PASS

```bash
STRICT=1 ADVANCED=1 AWAIT_TIMEOUT=120 ./scripts/local/validate-seed-scenarios.sh
```

§7 改用 default-tenant 3 个 jobs 等真 SUCCESS。**会暴露 happy-path 配置缺陷**（见 §4 已知 STRICT 失败原因）。

---

## 2. 全部 36 项覆盖清单

| § | 项 | 默认跑 | ADVANCED=1 | 验证内容 |
|---|---|---|---|---|
| 0 | 探活 ×3 | ✅ | ✅ | trigger / orchestrator / postgres 可达 |
| 0.4 | PRE_CLEANUP | ✅ | ✅ | 清所有历史 seedval-* 残留 |
| 1 | Schema 落地 ×7 | ✅ | ✅ | V82-V85 flyway + 约束定义 + tenant_id 列 |
| 2 | 种子基线 ×2 | ✅ | ✅ | job_definition / workflow_node 行数 |
| 3 | V84 多租隔离 ×1 | ✅ | ✅ | 跨租户同 (wf_def, node_code) 共存 + 同租户唯一 |
| 4 | 同步 API + 双写 ×2 | ✅ | ✅ | trigger HTTP 200 + trigger_request + trigger_outbox_event 同事务 |
| 5 | ADR-010 异步链路 ×1 | ✅ | ✅ | TriggerOutboxRelay → Kafka → orch consumer → trigger_request 推 LAUNCHED |
| 6 | 异常路径 ×5 | ✅ | ✅ | 缺 jobCode / Idempotency-Key / X-Internal-Secret / 重发去重 / 跨租户拒绝 |
| 7 | Worker 链路覆盖 ×7 | ✅ | ✅ | 4 worker × 4 workflow_type fire 后 instance 创建 |
| 8 | 多租并发 ×1 | ✅ | ✅ | ta + tb 并行 fire 后 tenant_id 不串 |
| 9.1 | Outbox cleanup API | — | ✅ | POST /internal/outbox/cleanup → 200 |
| 9.2 | Outbox republish API | — | ✅ | POST /internal/outbox/republish → 200 + reset=0 |
| 9.3 | Compensate API | — | ✅ | POST /internal/compensations → 4xx 业务拒绝 |
| 9.4 | Console-api 鉴权 | — | ✅ | GET /api/console/job-definitions → 401 (gate 工作) |
| 9.5 | Trigger cron 真触发 | — | 🟡 SKIP | 时序不确定，建议手动改 schedule_type=FIXED_RATE 验 |
| 11 | 探针清理 | ✅ | ✅ | sweep `seedval-%` 13 张表 cascade 删 → 零残留 |

---

## 3. 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `TRIGGER_PORT` | 18081 | trigger 端口 |
| `ORCH_PORT` | 18082 | orchestrator 端口 |
| `CONSOLE_PORT` | 18080 | console-api 端口 |
| `INTERNAL_SECRET` | `internal-secret` | trigger / orch /internal/* 共享密钥 |
| `PG_CONTAINER` | `batch-postgres` | PG 容器名 |
| `PG_USER` / `PG_DB` | `batch_user` / `batch_platform` | PG 连接 |
| `AWAIT_TIMEOUT` | 30 | §7 worker 等待秒数（STRICT=1 建议 ≥90） |
| `LOAD_SEED` | 0 | 1 = 跑前重载 platform/business seed |
| **`PRE_CLEANUP`** | **1** | 跑前先清所有 seedval-* 历史残留（**默认开**，杜绝积累） |
| `ADVANCED` | 0 | 1 = 跑 §9 advanced 段（5 项） |
| `STRICT` | 0 | 1 = §7 改用 default-tenant 严格 SUCCESS（含 IMPORT/EXPORT/WORKFLOW 3 项 + DISPATCH SKIP） |
| `PROBE_TAG` | `seedval-$(date +%s)` | 本次 run 的探针前缀（自动唯一） |

---

## 4. 已知 STRICT=1 失败原因（业务数据缺，非 schema bug）

STRICT=1 时 default-tenant 3 个严格 SUCCESS 测试当前会 FAIL，**均为 seed 数据不完整**：

| 测试 | 实际错误 | 修法 |
|---|---|---|
| IMPORT (default-tenant) | `IMPORT_PREPROCESS_AES_KEY_MISSING` | seed 里 import_customer_job 强制走 `import_customer_v1` 加密模板；要 SUCCESS 需在 launch params 里传 `aesKeyBase64`/`aesIvBase64` |
| EXPORT (default-tenant) | `EXPORT_GENERATE_FAILED: export_data_ref is required` | `export_settlement_v1` 模板缺 `jdbc_mapped_export` 或 `sql_template_export` 配置 |
| WORKFLOW PIPELINE | 90s 内 instance 卡 CREATED | `wf_probe_pipeline` 节点配置不全（缺 START → PROBE_TASK 边或类似） |

**这些是已知 seed 完整性问题，不阻塞 V82-V85 schema / ADR-010 链路验证**。修法是给种子加正确的 template / encryption key / workflow_node。如要 STRICT=1 全过，需先补这些 seed。

ta/tb/tc 的 jobs 在 STRICT 模式下**不可能 SUCCESS** — multi-tenant-seed 里那些租户的 worker_registry status 全是 OFFLINE，docker 不为它们启 worker 容器；STRICT 限定只跑 default-tenant 是设计妥协。

---

## 5. 清理保证（零残留）

脚本通过 **3 重防护** 保证不留脏数据：

| 时机 | 触发 | 范围 |
|---|---|---|
| 跑前 (PRE_CLEANUP=1, 默认) | 进 §0.4 | sweep `seedval-%` 全历史 |
| 跑后 (§11) | 正常退出 | 同上 |
| EXIT trap | 任何路径退出（含 Ctrl+C / kill） | 同上 |

清理覆盖 **13 张表**：
```
trigger_request, trigger_outbox_event,
job_instance, job_task, job_partition, job_step_instance, job_execution_log,
workflow_run, workflow_node_run,
pipeline_instance, pipeline_step_run,
file_record, file_dispatch_record,
outbox_event, batch.workflow_node (V84 SEEDVAL_PROBE)
```

按 FK 依赖序级联删（子表 → 父表 → trigger_request）—— 杜绝 FK violation 残留。

---

## 6. Troubleshooting

### 6.1 探活失败

```
🔴 FAIL trigger UP — port 18081 不响应
```
**修法**: `./scripts/docker/up-apps.sh` 等所有容器 healthy

### 6.2 种子基线 < 期望

```
🔴 FAIL job_definition 基线 — 仅 5 行,期望 >=3
```
**修法**: `LOAD_SEED=1 ./scripts/local/validate-seed-scenarios.sh` 或手动加载（见 §0）

### 6.3 ADR-010 异步链路超时（30s 未推 LAUNCHED）

```
🔴 FAIL TriggerOutboxRelay 推 LAUNCHED — 30s 未推到; trigger_request=ACCEPTED outbox=NEW/attempt=0
```
**根因**: TriggerOutboxRelay 没启动。常见 2 个原因：
- **Local profile** 启用了 `spring.main.lazy-initialization=true` → relay bean 不 eager 创建。docker 不受影响。
- **Kafka 不可达** → relay 可达但 Kafka send 失败，看 `docker logs batch-trigger | grep -i 'kafka\|outbox'`

### 6.4 §7 worker 链路 FAIL（reach_worker 模式）

```
🔴 FAIL IMPORT 链路 — 30s 未推 LAUNCHED, trigger_request=
```
**根因**: trigger_request 都没创建。检查：
- `docker logs batch-trigger` 看 fire 时是否有 ERROR
- jobCode 是否在 `batch.job_definition` 中 + tenant 匹配

### 6.5 §7 STRICT=1 FAIL — 见 §4 已知问题

### 6.6 §11 残留（清理 fail）

```
🔴 FAIL 探针清理 — 残留 N 行 trigger_request
```
**根因**: FK violation。常见是新加表没纳入 do_cleanup 函数。手动修：
```sql
SELECT id, request_id FROM batch.trigger_request WHERE request_id LIKE 'seedval-%';
-- 找 FK 引用方,删它,再删 trigger_request
```

---

## 7. 与 batch-e2e-tests 的边界

| 维度 | 本脚本 | batch-e2e-tests |
|---|---|---|
| 时间 | 4-15 分钟 | 25-30 分钟 |
| 部署 | 假设已 docker up | 自启 Testcontainers |
| 覆盖 | smoke + 链路 + 多租 + 异常 | 完整 worker SUCCESS happy + 失败补偿 + 重启恢复 |
| 何时跑 | 部署后 / 改 schema 后 | CI 全量回归 |
| 严格 SUCCESS | 仅 default-tenant 3 项（STRICT=1，且需补 seed） | 27 个 IT 全覆盖 |

要严格的 happy-path 端到端验证 → `mvn -pl batch-e2e-tests test`。本脚本是**轻量回归 + 部署后冒烟**。

---

## 8. CI 集成建议

```yaml
# .github/workflows/post-deploy-smoke.yml
- name: Validate seed scenarios
  run: |
    ADVANCED=1 ./scripts/local/validate-seed-scenarios.sh
    # 退出码 0 = 全过；非 0 = 部署回归
```

不建议直接接 `STRICT=1` 到 CI，除非先把 seed 数据补全（见 §4）。
