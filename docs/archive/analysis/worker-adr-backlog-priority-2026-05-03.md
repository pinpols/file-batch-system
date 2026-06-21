# Worker ADR Backlog 必要性 + 优先级分析 — 2026-05-03

> **范围**:`docs/analysis/worker-vs-industry-2026-05-03.md` 中归到 backlog 的 3 个 ADR (ADR-014/015/016) 的"做不做、什么时候做"决策档。
> **目的**:让团队按部署规模 + 业务模式做判断,避免凭感觉立项。

---

## 1. 三项依赖关系

```
ADR-014 (CLAIM 幂等)  ←  基石,防 CLAIM 重复 + invocation_id 校验
   ↓ 必须先做
ADR-015 (worker outbox)  ←  防 REPORT 失败重复执行 (依赖 ADR-014 invocation 校验)

ADR-016 (batch renew)  ←  独立,纯性能优化 (规模问题)
```

---

## 2. ADR-014 CLAIM 幂等

### 触发场景

```
worker A 业务执行中
  → A GC pause / 网络隔离
  → lease 过期
  → PartitionLeaseReclaimScheduler 释放为 READY
  → worker B Kafka 重投递后 CLAIM 成功
  → B 业务执行
  → A 恢复继续 → 业务跑两次
```

### 评估表

| 维度 | 评估 |
|---|---|
| **真实频率** | **罕见 (月级)**。GC pause 长到 lease 过期 (默认 120s) 现实少见;Kafka rebalance 中等频率 |
| **当前 mitigations** | DB CAS `where partition_status='READY'` 防"同时"重复;业务侧自带幂等 (SqlTransformCompute ON CONFLICT / outbox uk_event_key / job_instance uk_dedup) 兜 ~80% 场景 |
| **不做代价** | 偶发业务重复执行;**不丢数据**(约束回退);用户感知:多余 file_record / pipeline_step_run 行 / outbox 重发 |
| **真窟窿** | **没自带幂等的业务路径**:file 解析、外部 API call、邮件发送 |
| **必要性** | **中**:罕见但真出事,业务路径越扩张窟窿越多 |

### 实施成本

- ~2-3 天
- migration:`job_partition` 加 `current_invocation_id` + `invocation_started_at`
- 协议 breaking change:CLAIM/RENEW/REPORT 全协议透传 invocation_id
- 向后兼容窗口:server 容忍老 worker 不带 invocation_id,过渡期共存

---

## 3. ADR-015 worker 端本地 outbox

### 触发场景

```
worker 业务执行成功
  → REPORT HTTP 失败 (网络抖动 / orch GC / orch 短暂 503)
  → catch ex
  → publishToDlqSafely → DLQ
  → 运维 replay
  → 重派 task → 业务跑两次
```

### 评估表

| 维度 | 评估 |
|---|---|
| **真实频率** | **周级** (网络抖动 + orch GC pause 比 Kafka rebalance 频繁) |
| **当前 mitigations** | HttpTaskExecutionClient 有 retry max-attempts;P1-8 熔断只防 renew 路径,**REPORT 路径无同等熔断**;DLQ + replay 路径**会**触发重复执行 |
| **不做代价** | REPORT 偶发失败 → 业务白干 + 重派 + 重复执行;**计算资源浪费 ~2x** |
| **关键依赖** | **若 ADR-014 先做**:invocation_id 校验拒收老 invocation 的 REPORT,防数据重复 → 此项降为"性能浪费"<br>**若 ADR-014 未做**:DLQ replay 直接业务重复 → **数据风险** |
| **必要性** | **中-低**(ADR-014 先做时) / **中-高**(ADR-014 未做时) |

### 实施成本

- ~2-3 天
- 引入 SQLite 嵌入式存储(worker host)
- K8s PVC / VM 持久卷部署调整
- 业务执行 + outbox 写入同事务

---

## 4. ADR-016 batch renew API

### 触发场景

```
100 worker × 50 task = 5000 active task
  → 每 10s renew 周期 = 500 QPS 单 endpoint
  → orch servlet thread pool / HikariCP 连接池 / DB CPU 被打满
```

### 评估表

| 维度 | 评估 |
|---|---|
| **真实频率** | **持续性** (不是抖动)。只要 active task 数达到阈值就稳定发生 |
| **当前 mitigations** | P1-8 熔断只防"orch 不可达时 worker hammer",**不防"orch 可达但被 N+1 打满"** |
| **临界点** | **~1000 active task / 集群** 是性能拐点 (经验值,需压测确认) |
| **不做代价** | 中小规模 (< 1000 task) 无影响;大规模 endpoint 被打满 → **跨租户级联** (orch 单点) |
| **必要性** | **规模相关**:中小规模可缓;大规模 (10k+ task) 必做 |

### 实施成本

- MVP (仅省 HTTP roundtrip,SQL 仍 N 条) ~2-3h
- 完整版 (batch SQL with RETURNING + worker 侧 batch 调用 + fallback 逐条) ~半天

---

## 5. 综合优先级矩阵

| 部署规模 | ADR-014 | ADR-015 | ADR-016 | 备注 |
|---|---|---|---|---|
| **小** (<1k active task) | 可缓 | 可缓 | 可缓 | 业务侧幂等 + DLQ 偶发 replay 可接受 |
| **中** (1k-10k active task) | **该做** | 配 014 做 | 监控 endpoint p99,按需 | 014 是基石先排 |
| **大** (10k+ active task) | **必做** | **必做** | **必做** | 三件套缺一个都会出事 |

---

## 6. 触发条件 (按 production 信号)

不阻 ship,但出现以下信号立即排上议程:

| 信号 | 行动 |
|---|---|
| 生产偶发"业务重复执行"投诉 (file 重复入库 / 外部 API 重复调) | **排 ADR-014** (基石优先) |
| orch `/internal/tasks/.../renew` endpoint p99 > 500ms 或 thread pool 接近上限 | **排 ADR-016** |
| DLQ 大量 replay 且观察到数据问题 (重复 partition_run 等) | **排 ADR-015** (前提 014 已做) |
| 多租户 SaaS + 重要客户 SLA 违约风险 | **排 ADR-014** + ADR-015 一起 |

---

## 7. 当前阶段 honest 推荐

假设你**不知道生产部署规模**:

- **不阻 ship**:三个都不做也能上生产,业务侧幂等 + DLQ 回退覆盖 ~80% 场景
- **若立即决策一个先做**:**ADR-014** 优先级最高 (基石,影响 ADR-015 设计)
- **若按 ROI 排**:
  1. **ADR-016 MVP** (~3h) — 投入最小,大规模时收益最大,无 schema 改动风险
  2. **ADR-014** (~2-3 天) — 基石型架构升级,影响面大但收益最深
  3. **ADR-015** (~2-3 天) — 必须 014 先做,且依赖部署侧 PVC

---

## 8. 与已落地工作的协同

本次 (2026-05-03 commit `fc3cb654` + `261ae885`) 已落地:

| 已落地 | 与未做 ADR 的协同关系 |
|---|---|
| **P1-8 熔断** (LeaseRenewer) | 减轻 ADR-015 紧迫性 (REPORT 路径仍未保护,但 renew 路径不会 hammer orch) |
| **P2-12 currentLoad 写入** | 让 orch 端 least-loaded 派发自动接通 — 间接降低 ADR-016 紧迫性 (调度更均匀,单 endpoint 负载更分散) |
| **P2-13 软隔离** | 与 ADR-014 协同:tenant 隔离 + invocation_id 双层防护 |
| **ADR-013 OTel tracing** | 排障 trace 串联 → 看到 ADR-014/015/016 真出事时能快速 root-cause |

---

## 9. 复审节奏

- **每季度复审**:对比 production active task / DLQ replay / endpoint p99 等指标
- **触发立即复审**:任何一条 §6 信号出现
- **死线**:若任何 ADR 标 Proposed 超过 6 个月仍未实施,重新评估是否 archive 或拆解

---

## 10. 相关

- [ADR-014 CLAIM 幂等](../architecture/adr/ADR-014-claim-idempotency.md)
- [ADR-015 worker 端 outbox](../architecture/adr/ADR-015-worker-side-outbox.md)
- [ADR-016 batch renew API](../architecture/adr/ADR-016-batch-renew-lease-api.md)
- [worker audit 主报告](worker-vs-industry-2026-05-03.md)
- [orch audit 主报告](orchestrator-vs-industry-2026-05-03.md)
