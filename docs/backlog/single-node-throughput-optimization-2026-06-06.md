# Backlog: 单机吞吐优化清单(读写双侧,benchmark 驱动,Citus 之前的必经路径)

> 状态:**待 benchmark 驱动决策**(零代码层先量,再决定改造)。
> 日期:2026-06-06　模块:batch-worker-import、batch-worker-export、batch-common(JVM/PG 配置)、docker(部署)
> 对照:[streaming-large-file-import-export-2026-06-06](../verifications/streaming-large-file-import-export-2026-06-06.md)(§5.3 扩展决策树 ①)
>
> **范围**:导入(写 DB)与导出(读 DB + 序列化 + 写文件 + 上传 MinIO)各成独立章节(§1 / §2),互不嵌套。
> §3 是两侧共用的主机/系统层调参,§4 是 step-by-step 执行剧本(怎么跑 benchmark),§5 是关联。

## TL;DR(30 秒结论)

1. **现状不是"PG 顶了",是"我们还没榨单机"** —— §0 列了 4 类非 PG 混淆因素。
2. **路径锁死:Tier-A(零代码) → Tier-B(代码) → Citus**,不能跳步。绝大多数场景止于 Tier-A。
3. **导入有数量级杀招(COPY,5-20×);导出没有**(瓶颈分散,只能 2-3×)。导出的横向加速(keyset 分片)已在 PR #393 落地。
4. **下一步动作**:按 §4 执行剧本跑 baseline + Tier-A,**别先改代码**。

## 章节速览

| 我想…… | 看这里 |
|---|---|
| 30 秒知道结论 | TL;DR(上方) |
| 知道为什么不直接上 Citus | §0 |
| 看**导入**怎么优化(完整) | §1(含 baseline / Tier-A/B/C / benchmark / 不做 / 触发) |
| 看**导出**怎么优化(完整) | §2(含 baseline / 可做 / 已落地 / 决策树 / 不做) |
| 看 Kafka / Redis / MinIO / OS 的小调参 | §3 |
| **看 step-by-step 怎么跑 benchmark**(Tier-A 改哪几行 / 三轮取中位数脚本) | **§4** |
| 找相关代码 / 配置文件位置 | §5 |

## 0. 背景与现状(导入/导出共用)

[streaming-large-file 实证](../verifications/streaming-large-file-import-export-2026-06-06.md) 量到 100 万行宽表单流导入 **182.5s ≈ 5500 行/s**;
4 文件并发(无冗余无冲突)也没提速 →「DB 写入封顶」。但**那次跑被一堆非 PG 因素人为压低**,
所以严格说:**有"瓶颈现象",还不是"PG 单机写吞吐天花板"的证据**。在跳到 Citus / 分库等重决策前,
**必须先把单机榨干**——这是决策树第 ① 步,绕不开。

可以肯定的混淆因素:
- worker JVM `-XX:+UseSerialGC -XX:TieredStopAtLevel=1`(快启残血,GC + C1 only)
- LoadStep `batchUpdate` 每批 500 行逐行 `INSERT ... ON CONFLICT`(UPSERT,比纯 INSERT 慢)
- 本地 PG + MinIO + Kafka + 8 个 JVM 同一台机抢 CPU/IO
- 默认 `postgresql.conf`(`wal_buffers`/`checkpoint` 全是保守值)

→ 真实 PG 单机写吞吐**未被测量**;数十万行/s 是常见量级,残血配置吃不到。

**两侧共用的取舍**:不引入 Citus / 不切 MySQL / 不上 BDR 多主复制 / 不在生产业务表用 UNLOGGED 或关 `synchronous_commit`。具体边界各章节再讲。

---

## 1. 导入吞吐优化(完整)

### 1.1 Baseline 与瓶颈定位

- **Baseline**:100 万行宽表单流导入 **182.5s ≈ 5500 行/s**,worker RSS ~180MB
- **瓶颈位置**:**集中在写 DB**(LoadStep `batchUpdate` 每批 500 行逐行 `INSERT ... ON CONFLICT`)
- **有数量级杀招吗**:✅ **有**——COPY 替代 batchUpdate 是 5-20× 量级,本章主线

### 1.2 Tier 总览(选哪档)

| Tier | 改什么 | 总收益 | 何时上 |
|---|---|---|---|
| 🥇 **A**(零代码) | JVM / batch / PG / DB 隔离 / JDBC URL | **2-5×**(182s → ~60s) | **立刻、无条件做** |
| 🥈 **B**(代码改造) | 多值 INSERT → 真 COPY | 累积 5-20× | A 跑完不达 §1.5 目标才上 |
| 🥉 **C**(场景化) | 临时去索引 / UNLOGGED / 表分区 | 因场景 2-10× | 仅特定场景,**业务表不能用** C2/C3 |

### 1.3 🥇 Tier-A:零代码改动,立刻可做

| # | 优化 | 怎么做 | 预期 | 代价/约束 |
|---|---|---|---|---|
| A1 | **生产 JVM 配置** | worker 启动参数:`-XX:+UseSerialGC -XX:TieredStopAtLevel=1` → `-XX:+UseG1GC`(去掉快启低优化档);`-Xmx` 给足(留 GC headroom) | **2-3×** | 改 systemd/Docker env,无代码 |
| A2 | **加大 batch 写入** | `BATCH_WORKER_IMPORT_CHUNK_SIZE` 500 → 5000;`BATCH_WORKER_IMPORT_FETCH_SIZE` 同步调 | 2-3× | 改 yml/env;留意 worker 堆 OOM 风险(每批 5000 个 Map 入参) |
| A3 | **PG 写参数调优** | `postgresql.conf`:`wal_buffers=64MB`、`max_wal_size=8GB`、`checkpoint_timeout=15min`、`checkpoint_completion_target=0.9`、`commit_delay=10000` | 1.5-2× | 改 docker postgresql.conf,需重启 PG |
| A4 | **物理隔离 DB(benchmark 期)** | benchmark 时把 PG 移到独立宿主(不跟 8 JVM 抢 IO)或独立 SSD/挂卷 | 量出真实能力 | 拉个独立机/独立云盘 |
| A5 | **JDBC `reWriteBatchedInserts=true`** | 业务库 JDBC URL 末尾 `?reWriteBatchedInserts=true`(console-api 副本已用,业务库未启用) | 1.5-2× | 改 application.yml/env;PG JDBC 原生支持,零风险 |

> **A1-A5 全做完,大概率把 182s 压到 < 60s。** 全部零代码改动。先量这一档,再谈下面的。

### 1.4 🥈 Tier-B:代码改动,收益最大

| # | 优化 | 怎么做 | 预期 | 代价/约束 |
|---|---|---|---|---|
| B1 | **多值 INSERT**(COPY 轻量替代) | LoadStep:单条 SQL `INSERT INTO ... VALUES (...),(...),(...)` 一次 1000-5000 行;UPSERT 仍走 `ON CONFLICT DO UPDATE` | **3-5×** | 改 LoadStep;UPSERT 语义、skip-on-error、RLS `SET LOCAL`、ADR-038 续跑 checkpoint 全保住;参数数量受 JDBC 限制(>32767 占位符要拆) |
| B2 | **真 COPY(终极)** | LoadStep:`COPY ... FROM STDIN` 灌**临时表** → `INSERT INTO target SELECT ... FROM tmp ON CONFLICT DO UPDATE`(两步法) | **5-20×** | 比 B1 重一倍;同样要保住 UPSERT/skip/RLS/checkpoint;PG JDBC `CopyManager` API |

> **B 之前先做完 A**。B1 是中间档,拿 COPY 一大半好处、代价小;**做完 A 仍不够再上 B1,B1 仍不够再上 B2**。

### 1.5 🥉 Tier-C:重场景才用(有约束)

| # | 优化 | 适用场景 | 预期 | 代价/约束 |
|---|---|---|---|---|
| C1 | **导入期临时禁/丢二级索引,导完重建** | 一次性大文件 ingestion,期间无读 | 2-10× | 表上有并发读 → 不能丢;重建索引也耗时;**只对**离线 ingestion 划算 |
| C2 | **UNLOGGED 表** | **仅**中间暂存(如 `process_staging`) | 2-3× | 崩溃数据丢失;**生产业务表绝不能用**;红线 |
| C3 | **临时关 `synchronous_commit`** | 受控的大批量入库 | 1.5-2× | 崩溃可能丢已 commit 事务;**多租 SaaS 不建议**;若用须 session-local 不污染其他事务 |
| C4 | **PG 表分区**(声明式 RANGE/HASH 分区) | 单表 > 几千万行 | 写入分散到分区,2-3× | DDL 改造 + 选好分区键(tenant_id / bizDate) |

### 1.6 Benchmark 计划 + 决策树

#### 通过门槛(pre-defined,不能事后调)

| 阶段 | 跑什么 | 目标值 | 不达标后续 |
|---|---|---|---|
| Baseline 导入 | 现状(残血 JVM + 500 chunk + 默认 PG + 单机) | 复现 ~182.5s | 基线锚点 |
| **过 A1-A5** | 100 万行宽表单流导入 | **< 60s**(3× 提升) | 若达到 → Citus 至少推迟一年 |
| **过 A1-A5 + 并发** | 4 独立文件(不同租户)并发 | < 60s 且**比单流不慢** | 若达到 → 单 PG 真够用 |
| **B1 多值 INSERT** | 单流 | **< 30s** | 若达到 → COPY 改造也不必 |
| **B2 真 COPY** | 单流 | **< 15s** | 若仍不够 → 这才是 Citus 的真实信号 |

#### 怎么跑(可执行)

1. **建独立 benchmark 表**(避开生产数据):`biz.bench_demo`(同 wide_demo schema)
2. **生成 100 万行 CSV** 落 MinIO(复用 `scripts/sim/wide-1m-20260606.csv`)
3. **三轮跑,取中位数**(避 cold cache + 抖动)
4. **指标**:
   - 总耗时(`pipeline_instance.finished_at - started_at`)
   - worker RSS 峰值(`ps -p <pid> -o rss=`)
   - PG `pg_stat_statements`:总 calls / 总 time / rows
   - PG `pg_stat_bgwriter`:checkpoint_write_time(看 wal/checkpoint 是否瓶颈)
5. **每阶段切换**:只动该阶段的旋钮,不混改;改完跑 3 轮记数。

#### 决策树(benchmark 直接驱动)

```
Baseline 182s
   ↓ Tier-A 全做完
< 60s  ──┬─→ 够用 → 停。Citus 推迟。
         └─→ 不够 → Tier-B1
                 < 30s ──┬→ 够用 → 停。
                         └→ 不够 → Tier-B2
                                 < 15s ──┬→ 够用 → 停。
                                         └→ 仍不够 → **才是 Citus 该上的真实信号**
                                                     带 benchmark 数字做决策
```

### 1.7 不做(YAGNI / 红线)

- **不做** Citus / 分库 / 分布式 DB 引擎切换(本期);本章就是为了**避免**这种重决策被"现象"驱动而非"数据"驱动。
- **不做** 多主/双写复制(BDR/pglogical);状态机强一致场景不适用(详见 streaming-large-file §5.3)。
- **不做** 切 MySQL/NoSQL;RLS/jsonb/裸 SQL 深耦合 PG by-design(详见 streaming-large-file)。
- **不做** Tier-C2/C3 落生产业务表(数据安全红线)。

### 1.8 触发条件(满足才进 Tier-B / 才考虑 Citus)

- **进 Tier-B**:Tier-A 全做完仍不达 §1.6 目标。
- **考虑 Citus**:Tier-A + Tier-B 全做完(单流 < 15s),仍不能满足**真实生产负载**;且**实测多租户并发洪峰**确认是 Citus 的甜点场景(单租户单流 Citus 不缩耗时,详见 streaming-large-file §5.3 / Citus 加速维度分析)。

---

## 2. 导出吞吐优化(完整)

### 2.1 Baseline 与瓶颈定位

- **Baseline**:100 万行宽表单流导出 **160.6s ≈ 6230 行/s**,worker RSS ~142MB(比导入更健康)
- **瓶颈位置**:**分散**在四段——读 DB(游标分页) + 序列化(CSV/JSON/Excel) + 写本地生成文件 + 上传 MinIO,各段都有,无明显热点
- **有数量级杀招吗**:❌ **没有**——导出本就是流式 keyset 游标分页,**单段优化最多 1.5-2×,无 10× 台阶**

#### 与导入的结构性差异(关键)

| | 导入 | 导出 |
|---|---|---|
| Baseline | 182.5s / **5500 行/s** / RSS 180MB | 160.6s / **6230 行/s** / RSS **142MB**(更健康) |
| 真瓶颈 | **集中**:写 DB(`batchUpdate` UPSERT 逐行 ON CONFLICT) | **分散**:读 DB + 序列化 + 写本地文件 + 上传 MinIO,四段各占一段 |
| 数量级杀招 | ✅ `batchUpdate` → 多值 INSERT → COPY,**5-20×** | ❌ 无等价物 |
| 横向加速 | 同 §1.7 不做 Citus | ✅ **keyset 区间分片已在 PR #393 落地**(多租户并发洪峰场景接近 N×) |

**含义**:导入靠 §1.3-1.5 走"换写法快 10×"的路;导出靠 §2.2 多段小调参累积 2-3×,真要数量级提升只能靠 §2.3 已经做完的横向 keyset 分片(且仅多租户并发场景)。

### 2.2 剩余可做(单机维度)

| # | 优化 | 怎么做 | 预期 | 备注 |
|---|---|---|---|---|
| EX-A1 | **生产 JVM**(同导入 A1) | G1GC + 去掉 `TieredStopAtLevel=1` | 2-3× | **复用 §1.3-A1,全 worker 共享** |
| EX-A2 | **`fetch_size` 调大** | `BATCH_WORKER_EXPORT_FETCH_SIZE` 1000 → 5000;`page_size` 同步;减少 DB 网络往返 | 1.5-2× | 导出侧独有 |
| EX-A3 | **MinIO multipart upload** | 大产物(≥ 100MB)上传走 multipart 多 part 并发,替单流 `putObject` | 1.5-2×(上传段) | 改 `MinioExportStorage` 一处;**注意:与并行 session 的 storage 抽象(`BatchObjectStore`)合并后再做,避免冲突** |
| EX-A4 | **服务端游标 + `setAutoCommit(false)`** | JDBC 游标行级流式拉取,降低 driver 端缓冲 | 1.2-1.5× | 看实测 |
| EX-A5 | **物理隔离 DB**(同导入 A4) | benchmark 期 PG 独立机 | 量出真值 | 复用 §1.3-A4 |

**叠加预期**:160s → ~50-60s 量级。**没有 10× 台阶,合理预期就是 2-3×**。

### 2.3 已落地(无需重做)

- **横向加速 keyset 区间分片**(PR #390 + #393):激活时每分片只读游标列一段值区间走索引区间扫,放大 N× → ~1×。
  详见 `docs/backlog/export-partition-keyset-range-2026-06-06.md`(§10 实现记录)。
  覆盖多租户并发洪峰场景;单租户单流耗时不缩(同 Citus 限制)。
- 流式 keyset 游标分页(原有):任意时刻只持一页,worker RSS 常数,无 OOM 风险。

### 2.4 Benchmark 计划 + 决策树

#### 通过门槛

| 阶段 | 跑什么 | 目标值 | 不达标后续 |
|---|---|---|---|
| Baseline 导出 | 现状(残血 JVM + 默认 page/fetch + 单机) | 复现 ~160.6s | 基线锚点 |
| **过 EX-A1-A5** | 100 万行宽表单流导出 | **< 60s**(2-3× 提升) | 若达到 → 单机榨干,无需后续 |
| **过 EX-A1-A5 + 多租户并发** | 4 文件(不同租户)并发(利用已落地 keyset 分片) | < 60s 且与单流接近 | 若达到 → 横向 keyset 已足够 |

#### 决策树(独立于导入)

```
Baseline 160.6s
   ↓ EX-A1-A5 全做完
< 60s  ──┬→ 够用 → 停。
         └→ 不够 → **接下来唯一加速路径是横向(Citus + 多租户并发场景)**
                  单机维度的杀招已用尽(无 COPY 等价物);
                  若实测仍不够 → 转去看 streaming-large-file §5.3 Citus 决策
```

> **关键提示**:导出 benchmark 不达标,**不要去找"导出版 COPY"**——不存在。
> 接下来只能去 Citus(多租户并发场景)或更换硬件(磁盘/网络带宽)。

### 2.5 不做(YAGNI / 越界)

- **不做** export-side COPY 等价物(`COPY (SELECT ...) TO STDOUT`):序列化产物仍要走业务格式器(CSV/JSON/Excel/校验和),拿不到 COPY 的零拷贝好处,改造大且收益小。
- **不做** 跨 plugin 复用单一 fetch 路径(`SqlTemplate` vs `JdbcMapped`):两者源不同(用户 SQL vs 物理双表),抽象成本 > 收益。
- **不做** 输出格式池化/对象复用:GC 已被 G1 (EX-A1) 治理,二次优化空间小。
- **共用**:§0 提到的两侧共用边界(Citus / MySQL / BDR / UNLOGGED 生产表)同样适用,不重复列。

---

## 3. 主机/系统层 Tier-A 补充(Kafka / Redis / MinIO / OS,导入导出共用)

零散小调参归一,不为单组件另起文档。**单项收益都小**(几个百分点到 1.5×),但加起来吃干净;每条都属于"反正该做、做了不亏"。

### 3.1 Kafka(控制面,非热点)

| # | 调参 | 怎么做 | 预期 | 备注 |
|---|---|---|---|---|
| K1 | producer `compression.type=zstd` | trigger/orchestrator/worker 共享配置 | 减少 broker 网络/磁盘 ~30% | 控制面消息小,影响小但零代价 |
| K2 | consumer `fetch.min.bytes` 调大(默认 1 → 16KB) | yml | 减少 fetch 次数 | 小幅,降轮询开销 |
| K3 | 消息大小上限 | `RecordTooLargeException` 已在 streaming-large-file §L6 实证;大 payload 走 storagePath 而非内联 | 已治理 | 不重复 |

### 3.2 Redis(用得克制,无热点)

| # | 调参 | 怎么做 | 预期 | 备注 |
|---|---|---|---|---|
| R1 | Lettuce 连接池 `max-active` 按 worker 并发数 | yml,每模块按 worker concurrency 对齐 | 避免连接抢锁 | 默认通常够,实测有等待再调 |
| R2 | pipelining(批量幂等键写入) | 仅在 IdempotencyInterceptor 高并发热点出现时考虑 | 小幅 | YAGNI,无实测信号不做 |
| R3 | ShedLock provider 回退 | 已有 `BATCH_SHEDLOCK_PROVIDER=jdbc` 开关 | — | 不优化,只确认回退路径可用 |

### 3.3 MinIO / S3(存储抽象合并后做)

> ⚠️ **阻塞**:并行 session 正在落地 `BatchObjectStore` 抽象(`feature/be-storage-abstraction-design`)。
> 任何 MinIO 优化**等抽象合并入 main 后**再做,避免在裸 `MinioClient` 上改,合并时全部冲突。

| # | 调参 | 怎么做 | 预期 | 备注 |
|---|---|---|---|---|
| M1 | **Multipart upload**(大产物) | 导出 `MinioExportStorage` ≥ 64MB 走 multipart | 1.5-2× 上传段(导出 813MB 产物) | 等抽象合;与 §2.2 EX-A3 同 |
| M2 | Connection pool 调大 | `okhttp` 池 + timeout 配置 | 小幅 | 高并发上传场景才有差 |
| M3 | Range GET(已落地) | 导入 #390 已实现 | — | 不重复 |

### 3.4 OS / 内核(部署期一次性)

| # | 调参 | 怎么做 | 预期 | 备注 |
|---|---|---|---|---|
| O1 | 文件描述符上限 | `ulimit -n 65535`(systemd `LimitNOFILE`) | 防长跑后耗尽 | 8 JVM + Kafka + PG 同主机时尤其要 |
| O2 | TCP 缓冲区 | `net.core.{r,w}mem_max` 调大 | 高吞吐场景小幅提升 | sysctl,部署期一次 |
| O3 | 磁盘 IO 调度 | NVMe 用 `none`/`mq-deadline` | 小幅 | 现代发行版默认通常已对 |

### 3.5 总判断

- **K/R/O 总收益**:个位数百分比 ~ 几十百分比,**没有数量级提升**。属于"卫生"层级,不是"杀招"层级。
- **真正的杀招**在 §1(导入 COPY / 大 batch / JVM)与 §2.3(导出 keyset 分片已落)。
- **触发**:这些小调参**不立即做**;等 §1 / §2 主体 benchmark 跑完,**有空闲就一次性扫一遍**,无空闲就只挑 K1 / O1 这种零成本两条做。

---

## 4. 执行剧本(导入/导出共用,step-by-step)

> §1.6 / §2.4 给的是「**测什么 / 通过门槛**」(what + why);本章给的是「**怎么测**」(how)——
> 真要跑 benchmark 时,按这章一步步执行,半天跑完。
> **复用已有命令**:测试数据 / 触发 / 校验**全部沿用** [streaming-large-file §2-§5](../verifications/streaming-large-file-import-export-2026-06-06.md);本章只补「Tier-A 5 项怎么改 + 三轮取中位数 + 回滚」。

### 4.1 前置:基线复现(0.5 小时)

完全沿用已实证流程,无需重写命令:

| 准备项 | 引用 |
|---|---|
| 100 万行宽表 CSV 生成 | streaming-large-file §2 「测试数据」|
| MinIO 上传 (`mc cp`) | streaming-large-file §2 |
| `biz.wide_demo` 建表 + 模板配置 | streaming-large-file §2 / §3.1 |
| 导入触发 curl + payload | streaming-large-file §3.1 「触发」 |
| 导出触发 curl + payload | streaming-large-file §4.1 「模板与触发」 |
| 行数 / RSS / 耗时校验 | streaming-large-file §3.3 / §4.3 / §5 |

**复现 baseline 的目标值**(必须先打到 ±5% 内才能继续测优化):
- 导入单流:**≈ 182.5s**(残血 JVM + 500 chunk + 默认 PG)
- 导出单流:**≈ 160.6s**

### 4.2 Tier-A 5 项的具体改动(step-by-step)

每项独立,改完即测,**测完立即回滚**(见 §4.5),避免互相干扰。

> **分支纪律提醒**(CLAUDE.md):A1 / A3 改 `docker-compose` / `postgresql.conf` 属**部署改动**,
> 若想永久落地需走 `feature/docker-deploy` 分支 PR(**不进 main**);A2 / A5 改 yml 属业务改动,走 `feature/<topic>` PR → main。
> Benchmark 阶段**临时改、不入 git** 是最干净的——拿到数字再决定要不要永久落。

#### A1:生产 JVM 配置

**改动**(`docker-compose.yml` / `scripts/local/start-workers.sh`,看 worker 启动入口):

```yaml
# 之前(worker JVM args)
JAVA_TOOL_OPTIONS: -XX:+UseSerialGC -XX:TieredStopAtLevel=1

# 之后
JAVA_TOOL_OPTIONS: -XX:+UseG1GC -Xms2g -Xmx4g
```

**生效**:重启全部 8 worker JVM。**预期**:2-3×。

#### A2:加大 batch chunk_size(导入侧)

```bash
# 之前(默认 env / yml)
BATCH_WORKER_IMPORT_CHUNK_SIZE=500
BATCH_WORKER_IMPORT_FETCH_SIZE=1000

# 之后
BATCH_WORKER_IMPORT_CHUNK_SIZE=5000
BATCH_WORKER_IMPORT_FETCH_SIZE=5000
```

**生效**:重启 worker-import。**预期**:2-3×。**风险**:堆 OOM(每批 5000 个 Map 入参,留意 RSS)。

导出侧同步:`BATCH_WORKER_EXPORT_FETCH_SIZE` 1000 → 5000、`PAGE_SIZE` 同步。

#### A3:PG 写参数调优

**改动**(`docker/postgres/conf/postgresql.conf`,无则启动期 `-c` 注入):

```conf
wal_buffers = 64MB
max_wal_size = 8GB
checkpoint_timeout = 15min
checkpoint_completion_target = 0.9
commit_delay = 10000          # 单位 μs
```

**生效**:`docker restart batch-postgres-primary`。**预期**:1.5-2×。

#### A4:物理隔离 DB(benchmark 期)

**本地仿真**(不必拉新机器):

```bash
# 限 PG 容器 CPU + memory,降低与 8 JVM 抢资源的烈度
docker update --cpus=4 --memory=8g batch-postgres-primary
# 或:用独立 docker compose 把 PG 提到另一个 host network 段
```

**预期**:量出"PG 真实能力",而不是"全栈混跑的下限"。

#### A5:JDBC `reWriteBatchedInserts=true`

**改动**(`batch-worker-import/src/main/resources/application*.yml`,业务库 URL):

```yaml
# 之前
url: jdbc:postgresql://localhost:15432/batch_business?stringtype=unspecified

# 之后(末尾加 &reWriteBatchedInserts=true)
url: jdbc:postgresql://localhost:15432/batch_business?stringtype=unspecified&reWriteBatchedInserts=true
```

**生效**:重启 worker-import。**预期**:1.5-2×。**风险**:零(PG JDBC 原生支持)。

### 4.3 三轮取中位数 bash 模板

每项改完跑这个,自动 3 轮 + 取中位数:

```bash
#!/usr/bin/env bash
# bench-run.sh — 跑 N 轮导入,输出每轮耗时 + 中位数
set -euo pipefail
ROUNDS=${ROUNDS:-3}
SECRET="${BATCH_INTERNAL_SECRET:-batch-platform-internal-secret-2026}"
PSQL_BUSI() { docker exec batch-postgres-primary psql -U batch_user -d batch_business -t -A -c "$1"; }
PSQL_PLAT() { docker exec batch-postgres-primary psql -U batch_user -d batch_platform -t -A -c "$1"; }

echo "[bench] rounds=$ROUNDS"
declare -a TIMES
for i in $(seq 1 $ROUNDS); do
  PSQL_BUSI "truncate biz.wide_demo" >/dev/null         # clean slate
  RID="bench-r$i-$(date +%s)"
  T0=$(date +%s)
  curl -s -X POST http://localhost:18081/api/triggers/launch \
    -H "Content-Type: application/json" -H "X-Tenant-Id: ta" \
    -H "X-Internal-Secret: $SECRET" -H "Idempotency-Key: $RID" -H "X-Request-Id: $RID" \
    -d "{\"tenantId\":\"ta\",\"jobCode\":\"TA_IMPORT_CUSTOMER\",\"triggerType\":\"API\",\"bizDate\":\"2026-06-06\",\"requestId\":\"$RID\",\"params\":{\"templateCode\":\"WIDE_DEMO_TPL\",\"storagePath\":\"ingress/ta/wide-1m-20260606.csv\",\"storageBucket\":\"batch-dev\",\"fileName\":\"wide-1m-20260606.csv\",\"fileFormatType\":\"CSV\"}}" >/dev/null

  # 轮询完成
  while :; do
    sleep 5
    ST=$(PSQL_PLAT "select run_status from batch.pipeline_instance where trace_id is not null and started_at > now()-interval '15min' order by id desc limit 1")
    case "$ST" in SUCCESS|FAILED) break;; esac
  done
  T1=$(date +%s)
  SECS=$((T1 - T0))
  TIMES+=("$SECS")
  echo "[bench] round $i: ${SECS}s status=$ST rows=$(PSQL_BUSI 'select count(*) from biz.wide_demo')"
done

# 中位数(N 奇)
SORTED=$(printf '%s\n' "${TIMES[@]}" | sort -n)
MID=$(( (ROUNDS + 1) / 2 ))
MEDIAN=$(echo "$SORTED" | sed -n "${MID}p")
echo "[bench] times=${TIMES[@]} median=${MEDIAN}s"
```

**说明**:用中位数避 cold cache / 抖动。导出版同样模板,改 jobCode + truncate 目标即可。

### 4.4 指标采集(每轮跑完看哪些数)

```sql
-- 1. 端到端耗时
SELECT run_status, started_at, finished_at,
       round(extract(epoch from (finished_at-started_at))::numeric, 1) AS secs
FROM batch.pipeline_instance
WHERE trace_id = ':TRACE_ID';

-- 2. PG 视角 — pg_stat_statements 看实际 SQL 耗时(确认是不是写 DB 顶了)
SELECT query, calls, total_exec_time, rows
FROM pg_stat_statements
WHERE query ILIKE '%biz.wide_demo%'
ORDER BY total_exec_time DESC LIMIT 10;

-- 3. PG checkpoint / WAL 压力(看 A3 PG 参数有没有效)
SELECT * FROM pg_stat_bgwriter;
```

**worker 内存**:
```bash
ps -p $(jps -l | grep worker-import | awk '{print $1}') -o rss= | awk '{printf "%.0fMB\n",$1/1024}'
```

**注意**:跑前先 `SELECT pg_stat_statements_reset();` 清零,否则数字混入历史。

### 4.5 回滚命令(每项独立,可单测)

| 项 | 回滚 |
|---|---|
| A1 | 把 JVM args 改回 `-XX:+UseSerialGC -XX:TieredStopAtLevel=1` + 重启 worker |
| A2 | env / yml `BATCH_WORKER_IMPORT_CHUNK_SIZE` 改回 500 + 重启 worker |
| A3 | 删 postgresql.conf 新增 5 行 + `docker restart batch-postgres-primary` |
| A4 | `docker update --cpus=0 --memory=0 batch-postgres-primary`(取消限制) |
| A5 | yml URL 去掉 `&reWriteBatchedInserts=true` + 重启 worker-import |

**全局快速回滚**:`git checkout -- docker-compose.yml docker/postgres/conf/ batch-worker-*/src/main/resources/`(临时改若没 commit 时)。

### 4.6 完整运行清单(checkbox,半天跑完)

按顺序勾,每项 30-60 分钟:

- [ ] **0. 环境就绪**:docker 全栈起来、wide-1m CSV 在 MinIO、`biz.wide_demo` 表空、jar 是 main 最新
- [ ] **1. Baseline(必须先打)**:导入单流 3 轮取中位数,目标 ±5% 复现 ~182.5s
- [ ] **2. A1 JVM**:改启动参数 → 重启 worker → 3 轮 → 记数 + 立即回滚
- [ ] **3. A2 chunk_size**:改 env → 重启 worker → 3 轮 → 记数 + 回滚
- [ ] **4. A3 PG 参数**:改 conf → 重启 PG → 3 轮 → 记数 + 回滚
- [ ] **5. A4 DB 隔离**:`docker update` → 3 轮 → 记数 + 回滚
- [ ] **6. A5 JDBC URL**:改 yml → 重启 worker-import → 3 轮 → 记数 + 回滚
- [ ] **7. A1+A2+A3+A5 累加跑**:5 项同时开 → 3 轮 → 看叠加值(目标 < 60s)
- [ ] **8. 导出版同样跑一遍 EX-A1~A5**(差异点见 §2.2)
- [ ] **9. 据 §1.6 / §2.4 决策树**:够用即停;不够继续 §1.4 Tier-B(B1 多值 INSERT)

**每项 3 轮 ≈ 10-15 分钟,9 项约 4 小时**(含决策与切换)。

### 4.7 报告模板(数据沉淀格式)

跑完每项填:

| 项 | 中位数 (s) | 三轮 (s) | 相对 baseline | worker RSS 峰值 | PG checkpoint_write_time | 备注 |
|---|---|---|---|---|---|---|
| Baseline | | | 1.0× | | | |
| A1 JVM | | | | | | |
| A2 chunk | | | | | | |
| A3 PG | | | | | | |
| A4 隔离 | | | | | | |
| A5 JDBC | | | | | | |
| **累加** | | | | | | |

跑完整张表填了,Tier-A 决策有数据支撑。

## 5. 关联

### 5.1 导入侧代码/配置

- `batch-worker-import/.../stage/LoadStep.java`(B1/B2 改造点)
- `batch-worker-import/src/main/resources/application.yml`(chunk-size/fetch-size 默认值)
- 业务库 JDBC URL(A5 `reWriteBatchedInserts=true` 接入点)

### 5.2 导出侧代码/配置

- `batch-worker-export/.../plugin/{SqlTemplateExportDataPlugin,GenericJdbcMappedExportDataPlugin}.java`(fetch_size / 服务端游标)
- `batch-worker-export/.../infrastructure/MinioExportStorage.java`(EX-A3 multipart;等存储抽象)
- `batch-worker-export/src/main/resources/application.yml`(page_size/fetch_size 默认值)

### 5.3 共用配置/部署

- `docker/postgres/conf/postgresql.conf`(A3 调优点;若无独立配置文件 → 走环境变量/cmdline)
- 启动参数:`docker-compose*.yml` / `scripts/local/*.sh`(A1 / EX-A1 JVM 切换)

### 5.4 相关文档

- 实证:[streaming-large-file-import-export-2026-06-06](../verifications/streaming-large-file-import-export-2026-06-06.md) §5.3 扩展决策树
- 导出 keyset 分片实现:[export-partition-keyset-range-2026-06-06](./export-partition-keyset-range-2026-06-06.md)

### 5.5 分支纪律

- 业务/配置改动 → `feature/<topic>` 标准分支 → PR → main
- 不涉及部署分支(本文优化均在业务模块或 docker 配置层)
