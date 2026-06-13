# 多租户并发洪峰 — 单机控制面吞吐天花板实测(2026-06-13)

> 对应 `docs/backlog/citus-introduction-plan-2026-06-06.md` §0 门槛①②(**单机榨干**)。
> 结论先行:**单机完成吞吐天花板 ≈ 20–22 jobs/s,瓶颈在控制面串行处理(有效并发被压到 ~7),
> 与 PG 写入能力无关、与 Citus 分片无关。** 故门槛③(Citus 甜点验证)在本地单主机无意义,
> 真正的 go/no-go 须放多主机;但门槛①② 给出明确结论:**先调控制面并发,远未到该上 Citus 的地步**。

## 为什么这场是"单机天花板"而非"A/B 对比"

本地 Citus 是**单台机器上的 3 容器**(`citus-coord`+`citus-w1`+`citus-w2` 共享同一 CPU/IO),
结构上无法展示横向扩展收益(无新增硬件,只多协调器网络跳)。所以本轮目标改为**压单机找拐点**
——这正是门槛①②要求的前置:单机榨干仍不达标,才轮到 Citus。

## 方法

- 驱动:`load-tests/scripts/peak_ceiling.py`(本轮新增)。对 ta/tb/tc 等比并发 launch 轻量
  `atomic_sql_demo`(纯控制面:trigger→orchestrator→Kafka→worker-atomic→report,执行体仅一条 SQL),
  按并发梯度抬升 offered load,测每级 launch 吞吐/p95 + 完成排空曲线 + **峰值持续完成率**(真天花板)。
- 全程真实系统链路,不走前台模拟;统计走 `trigger_request ⋈ job_instance`(单机原生 join)。
- 临时去背压:ta/tb/tc 配额抬到 100000(测后已删);未改任何代码。

## 结果

### 基线 ramp(默认配置:worker maxConcurrentTasks=4,outbox relay 100/5s)

| L | 并发 | total | launch_ok | launch p95(ms) | launch/s | success | reject | e2e/s | **峰值/s** |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 32 | 600 | 600 | 1844 | 76.8 | 600 | 0 | 12.6 | 19.6 |
| 2 | 64 | 600 | 600 | 519 | 267.8 | 600 | 0 | 15.8 | **21.3** |
| 3 | 128 | 600 | 600 | 648 | 344.6 | 600 | 0 | 16.0 | 20.8 |
| 4 | 256 | 600 | 600 | 1421 | 245.3 | 600 | 0 | 14.4 | 20.3 |
| 5 | 512 | 600 | 600 | 1501 | 311.3 | 600 | 0 | 16.0 | 22.7 |

**完成吞吐在 ~20–22/s 死死封顶,拐点在并发≈64;并发再翻 8 倍(64→512)吞吐不动。**
launch(纯 PG 写入)轻松到 150–344/s、p95 亚秒、0 拒绝 0 失败 → **PG 写入路径有 10–15× 余量。**

### 证伪实验(锁定瓶颈不在 PG/worker/relay)

| 改动 | 预期(若是该瓶颈) | 实测峰值/s | 结论 |
|---|---|---|---|
| worker `maxConcurrentTasks` 4 → 32(pool 40,kafka 并发 8) | 应升 ~8× | **21.9 / 22.5** | 不是 worker 并发 |
| outbox relay `100/5s` → `1000/1s`(理论 20→1000/s) | 应大升 | **21.8 / 20.0** | 不是 dispatch relay |

两个最可疑的节流点抬高后天花板**纹丝不动** → 瓶颈在更深的**控制面串行环节**。

### 单任务生命周期分解(1826 个任务采样)

| 阶段 | 均值 |
|---|---|
| created → started(排队/派发/认领) | **310 ms** |
| started → finished(实际执行) | **37 ms** |
| 总计 | 350 ms |

执行仅 37ms;310ms 全耗在 dispatch→claim→start 排队。20/s × 0.35s ⇒ 稳态**实际只有 ~7 个任务在跑**
(尽管 worker 允许 32)。即:**有效并发被控制面流水线压到 ~7**,而非被 worker / PG 限制。

## 结论与建议

1. **PG 不是瓶颈**:launch 写入 300+/s 无压力,完成端 20/s——差 15×。单机 PG 远未榨干。
2. **Citus 在此无意义**:Citus 解决的是单 PG **写入/存储**横向扩展;而当前瓶颈是控制面**处理串行**
   (dispatch/claim/report 流水线把有效并发压到 ~7),分片不会动这个数。门槛③(Citus 甜点)在
   单机榨干、且控制面并发拉满之前**不该触发**。
3. **下一步(提单机天花板,ROI 远高于上 Citus)**:
   - 查 orchestrator **report 消费 Kafka concurrency**(疑似默认=1,单线程处理 report→更新状态);
     与 claim/dispatch 路径的串行点。把有效并发从 ~7 拉到 worker 实际能力(32+)。
   - 之后再压,瓶颈大概率移到 PG 行锁/事务 —— 那才是评估 read-replica / 分区 / 最终 Citus 的信号。
4. **真 go/no-go 须多主机**:本地单主机给不出 Citus 横向扩展数字;门槛③ 的对比压测放到 2–3 台真机
   (各跑 coord/worker)再做,用本驱动改 `COLOCATED=1`(Citus join 补 tenant_id 共置)即可复用。

## 复现

```bash
# 单机(默认 join):
SECRET=<BATCH_INTERNAL_SECRET> LEVELS="32:600,64:600,128:600,256:600,512:600" \
  python3 load-tests/scripts/peak_ceiling.py
# Citus(多主机,共置 join):
SECRET=... PG_CONTAINER=citus-coord PG_USER=postgres COLOCATED=1 LEVELS="..." \
  python3 load-tests/scripts/peak_ceiling.py
```

> 临时改动已全部回滚:ta/tb/tc bench 高配额行已删;orchestrator / worker-atomic 已重启回默认配置。
