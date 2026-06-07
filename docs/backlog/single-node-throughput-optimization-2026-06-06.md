# 单机吞吐优化决策手册(导入/导出)

> 状态:**已完成导入/导出 1000w 端到端补测;代码修复已完成,待部署复验;参数矩阵待继续**。
> 日期:2026-06-06　模块:batch-worker-import、batch-worker-export、batch-common(JVM/PG 配置)、docker(部署)
> 对照:[streaming-large-file-import-export-2026-06-06](../verifications/streaming-large-file-import-export-2026-06-06.md)(§5.3 扩展决策树 ①)
>
> **范围**:导入(写 DB)与导出(读 DB + 序列化 + 写文件 + 上传 MinIO)各成独立章节(§1 / §2),互不嵌套。
> §3 是两侧共用的主机/系统层调参,§4 是 step-by-step 执行剧本(怎么跑 benchmark),§5 是关联。

## TL;DR

1. **现状不是"PG 顶了",是"我们还没榨单机"** —— §0 列了 4 类非 PG 混淆因素。
2. **路径锁死:Tier-A(零代码) → Tier-B(代码) → Citus**,不能跳步。绝大多数场景止于 Tier-A。
3. **导入有数量级杀招(COPY,5-20×);导出没有**(瓶颈分散,只能 2-3×)。导出的横向加速(keyset 分片)已在 PR #393 落地。
4. **下一步动作**:导入侧继续测 PG/session/index/swap 矩阵;导出侧优先验证修复后的 keyset-range + multipart/并行分片。

## 阅读路径

| 我想…… | 看这里 |
|---|---|
| 30 秒知道结论 | TL;DR(上方) |
| 看做了哪些优化 | 已做优化清单 |
| 找测试报告、trace、结果 | 测试报告与证据 |
| 看哪些没做、为什么没做 | 未做 / 舍弃清单 |
| 知道为什么不直接上 Citus | §0 |
| 看**导入**怎么优化(完整) | §1(含 baseline / Tier-A/B/C / benchmark / 不做 / 触发) |
| 看**导出**怎么优化(完整) | §2(含 baseline / 可做 / 已落地 / 决策树 / 不做) |
| 看 Kafka / Redis / MinIO / OS 的小调参 | §3 |
| **看 step-by-step 怎么跑 benchmark**(Tier-A 改哪几行 / 三轮取中位数脚本) | **§4** |
| 找相关代码 / 配置文件位置 | §5 |

## 已做优化清单

| 方向 | 优化项 | 状态 | 说明 |
|---|---|---|---|
| 导入 | 大文件走 `storagePath` + `streaming_enabled=true` | 已验证 | 近 1GiB 对象不进 Kafka/JSON payload;PREPROCESS 不是瓶颈 |
| 导入 | `PARTITION_REPLACE_COPY` | 已验证 | 按 `tenant_id + biz_date` 整逻辑分区清理后 direct COPY 追加;1000w 真实链路 SUCCESS |
| 导入 | `chunk_size=10000` | 已验证 | LOAD 实际使用的大块写入参数;比 `page_size/fetch_size` 更关键 |
| 导入 | `shard_strategy=NONE` + 分片 fail-fast | 已修并验证负向 | 单大文件 replace-copy 不允许 worker 分片,避免每个分片清同一逻辑分区 |
| 导入 | `reWriteBatchedInserts=true` | 本地已启用 | 只影响 batch INSERT/UPSERT 类路径;replace-copy 主链路不是主要收益点 |
| 导入 | `FIXED_WIDTH` 的 PG jsonb `field_mappings` 解析 | 代码已修,单测已过 | 需要 worker 加载新 jar 后系统级复验 |
| 导出 | `page_size=5000`,`chunk_size=10000` | 已验证 | 1000w DELIMITED 单片真实链路使用;本地 profile 已标注 |
| 导出 | SQL template `fetch_size` 生效 | 代码已修,待部署复验 | 模板/worker 配置会传入分页查询的 `NamedParameterJdbcTemplate` |
| 导出 | `query_param_schema` PG jsonb 解析 | 代码已修,待部署复验 | cursorColumn/keyset 配置可从 PG jsonb 正确读取 |
| 导出 | `partition_keyset_range` 多落点读取 | 代码已修,待部署复验 | 支持顶层、`query_param_schema.partition_keyset_range`、`sqlTemplateExport.partitionKeysetRange` |
| 导出 | 1000w 4 分片正确性 | 已验证 | 4 文件无重无漏;但本地实际串行,性能未放大 |
| 导出 | JSON / FIXED_WIDTH / EXCEL 格式链路 | 已验证 | 三种格式 smoke 均走真实 worker 链路成功 |

## 测试报告与证据

| 类型 | 报告 / Trace | 覆盖内容 | 结果 |
|---|---|---|---|
| 导入 micro benchmark | §1.6 / `scripts/local/import-copy-worth-benchmark.sh` | batch UPSERT vs COPY stage+merge vs direct replace COPY | COPY+UPSERT merge 主路径收益有限;direct replace COPY 约 `1.50×` |
| 导入 1000w 端到端报告 | [import-partition-replace-copy-10m-system-2026-06-07](../verifications/import-partition-replace-copy-10m-system-2026-06-07.md) | API 触发 → Kafka → worker-import → MinIO → PG 分区表 | trace `ed8dda76e86944c683e67898bd7521cc`,instance `4006`,10,000,000 行 SUCCESS |
| 导入分片负向 | trace `be1d46...`,`2e9eff...` | 单大文件 + `PARTITION_REPLACE_COPY` + `partitionCount=2` | 旧行为半量;修复后 `IMPORT_LOAD_CONFIG_INVALID` fail-fast |
| 导入单测 | `ParseStepFixedWidthAndXmlTest` | FIXED_WIDTH/XML 解析与 PG jsonb field mapping | 已通过;系统级待 worker 新 jar 复验 |
| 导出 seed 链路 | trace `export-smoke-1780821600`,instance `4019` | `jdbc_mapped_export` / DELIMITED | SUCCESS |
| 导出 1000w 单片 benchmark | trace `export-wide-single-1780821897`,instance `4020` | SQL template / DELIMITED / 1000w | SUCCESS,`125.980s`,`1.407GB`,GENERATE `67.691s`,STORE `53.989s` |
| 导出 1000w 4 分片 | trace `export-wide-shard4-1780822058`,instance `4021` | 4 文件分片导出正确性 | SUCCESS,10,000,000 行无重无漏;本地串行 |
| 导出格式覆盖 | trace `export-json/fixed/excel-smoke-1780822336-*` | JSON / FIXED_WIDTH / EXCEL | 三种格式均 SUCCESS |
| 导出单测 | `SqlTemplateExportSpecTest`,`ExportKeysetRangePlannerTest` | PG jsonb config、fetch/keyset-range 读取 | 已通过,11 tests |

## 未做 / 舍弃清单

| 方向 | 项目 | 能不能做 | 我的决定 | 原因 / 后续 |
|---|---|---|---|---|
| 导入 | staging 新分区 COPY → 建索引 → attach/swap | 能做 | **建议做,导入下一轮 P0** | 这是让 COPY 真正吃满的主路径,且适配“按业务日整分区刷新”的批量模型 |
| 导入 | PG session 参数矩阵:`work_mem` / `maintenance_work_mem` | 能做 | **建议做,P1** | 不改变持久可靠性,适合作为 staging/swap benchmark 变量 |
| 导入 | PG session `synchronous_commit=off` | 能做但有风险 | **只允许 benchmark / 单任务 session 级验证,不进生产默认** | 会改变崩溃语义,不能作为多租户生产默认 |
| 导入 | load 前 drop index / load 后 rebuild | 能做 | **并入 staging/swap 方案一起测,不单独做** | 单独在现有业务分区上 drop index 风险大;新分区里做更干净 |
| 导入 | parallel COPY / 多连接写不同逻辑分区 | 能做 | **暂缓,P2** | 对单文件单业务日帮助有限;多租户/多业务日并行时再测 |
| 导入 | JVM/GC/heap/JIT 矩阵 | 能做 | **暂缓,P2** | 需要重启和固定窗口;收益不如 staging/swap 明确 |
| 导入 | COPY + UPSERT merge 立即改主路径 | 能做 | **不建议做** | micro benchmark 显示 merge/索引维护吃掉 COPY 收益;不值得优先投入 |
| 导入 | worker 分片处理单大文件 replace-copy | 技术上能硬做 | **不做** | 语义不兼容,每个分片会清同一逻辑分区;现在 fail-fast 是正确行为 |
| 导出 | 修复后 keyset-range/fetch_size 系统复验 | 能做 | **建议做,导出下一轮 P0** | 代码已修,只差 worker 加载新 jar 后用同一 API 复验 |
| 导出 | 真正并行消费 4 个 export task | 能做 | **建议做,P0** | 正确性已证明,性能没放大是本地串行消费;应查 topic partition/key/concurrency |
| 导出 | MinIO multipart upload | 能做 | **建议做,P1** | STORE 已占 `53.989s`;但最好等 `BatchObjectStore` 抽象合并后改,避免重复重构 |
| 导出 | 生产 JVM / GC 参数矩阵 | 能做 | **暂缓,P2** | 需要重启窗口;当前导出更明确的瓶颈是并行度和 STORE |
| 导出 | `page/fetch/chunk=5000/5000/10000` 生产全局默认 | 能做 | **不做全局默认,只做模板级覆盖** | 大宽表收益明确,但小租户/小内存场景可能放大单页内存 |
| 导出 | 导出版 COPY | 理论能做 | **不做** | 输出要经过业务格式器/Excel/校验和/MinIO,没有导入 COPY 那种收益 |
| 共用 | Citus / 分库 / MySQL / BDR / 生产业务表 UNLOGGED | 能做但代价大/风险高 | **本期不做** | 单机还没榨干;这些属于重架构决策或数据安全红线 |

**我的排序**:

1. 导出先做 `keyset-range/fetch_size` 部署复验 + 4 task 真并行,因为正确性已过,只差把性能放大跑出来。
2. 导入先做 staging 新分区 COPY → 建索引 → attach/swap,这是 replace-copy 的自然升级版。
3. MinIO multipart 排第三,等存储抽象合并后做,避免在旧 `MinioClient` 路径上返工。
4. JVM/GC、parallel COPY、PG session 扩展参数放后面;COPY+UPSERT merge、导出版 COPY、单大文件 replace-copy 分片、本期 Citus 不做。

### 维护规则

- 前半部分只放决策需要的结论、参数矩阵、舍弃原因。
- 端到端证据放在各自方向的“2026-06-07 补测”小节。
- 具体脚本和三轮 benchmark 模板放 §4;代码/配置入口放 §5。
- 不再新增同主题报告文件;避免和本文件互相重复。

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
| A2 | **加大 batch 写入** | `BATCH_WORKER_IMPORT_CHUNK_SIZE` 500 → 2000/5000;模板可用 `chunk_size=10000` | 2-3× | 改 yml/env;留意 worker 堆 OOM 风险(每批 Map 入参);`fetch_size/page_size` 对 LOAD 非关键 |
| A3 | **PG 写参数调优** | `postgresql.conf`:`wal_buffers=64MB`、`max_wal_size=8GB`、`checkpoint_timeout=15min`、`checkpoint_completion_target=0.9`、`commit_delay=10000` | 1.5-2× | 改 docker postgresql.conf,需重启 PG |
| A4 | **物理隔离 DB(benchmark 期)** | benchmark 时把 PG 移到独立宿主(不跟 8 JVM 抢 IO)或独立 SSD/挂卷 | 量出真实能力 | 拉个独立机/独立云盘 |
| A5 | **JDBC `reWriteBatchedInserts=true`** | 业务库 JDBC URL 末尾 `?reWriteBatchedInserts=true`;本地 import profile 已启用 | 1.5-2× | 改 application.yml/env;PG JDBC 原生支持,零风险 |

> **A1-A5 全做完,大概率把 182s 压到 < 60s。** 全部零代码改动。先量这一档,再谈下面的。

### 1.4 🥈 Tier-B:代码改动,收益最大

| # | 优化 | 怎么做 | 预期 | 代价/约束 |
|---|---|---|---|---|
| B1 | **多值 INSERT**(COPY 轻量替代) | LoadStep:单条 SQL `INSERT INTO ... VALUES (...),(...),(...)` 一次 1000-5000 行;UPSERT 仍走 `ON CONFLICT DO UPDATE` | **3-5×** | 改 LoadStep;UPSERT 语义、skip-on-error、RLS `SET LOCAL`、ADR-038 续跑 checkpoint 全保住;参数数量受 JDBC 限制(>32767 占位符要拆) |
| B2 | **真 COPY(终极)** | LoadStep:`COPY ... FROM STDIN` 灌**临时表** → `INSERT INTO target SELECT ... FROM tmp ON CONFLICT DO UPDATE`(两步法) | **5-20×** | 比 B1 重一倍;同样要保住 UPSERT/skip/RLS/checkpoint;PG JDBC `CopyManager` API |
| B2a | **整逻辑分区 replace + direct COPY** | `jdbcMappedImport.loadStrategy=PARTITION_REPLACE_COPY`:先按 `replacePartitionColumns` 清理 tenant/bizDate 等逻辑分区,再 `COPY FROM STDIN` 追加 | **适合全量分区刷新** | 只覆盖“本次文件就是该逻辑分区完整快照”的模板;不做 UPSERT merge;不与 line-based checkpoint 混用;分区表上要求清理谓词覆盖分区键 |

> **B 之前先做完 A**。B1 是中间档,拿 COPY 一大半好处、代价小;**做完 A 仍不够再上 B1,B1 仍不够再上 B2**。

### 1.5 🥉 Tier-C:重场景才用(有约束)

| # | 优化 | 适用场景 | 预期 | 代价/约束 |
|---|---|---|---|---|
| C1 | **导入期临时禁/丢二级索引,导完重建** | 一次性大文件 ingestion,期间无读 | 2-10× | 表上有并发读 → 不能丢;重建索引也耗时;**只对**离线 ingestion 划算 |
| C2 | **UNLOGGED 表** | **仅**中间暂存(如 `process_staging`) | 2-3× | 崩溃数据丢失;**生产业务表绝不能用**;红线 |
| C3 | **临时关 `synchronous_commit`** | 受控的大批量入库 | 1.5-2× | 崩溃可能丢已 commit 事务;**多租 SaaS 不建议**;若用须 session-local 不污染其他事务 |
| C4 | **PG 表分区**(声明式 RANGE/HASH 分区) | 单表 > 几千万行 | 写入分散到分区,2-3× | DDL 改造 + 选好分区键(tenant_id / bizDate) |

### 1.6 已验证结果与决策

| 场景 | 结果 | 决策 |
|---|---|---|
| COPY micro benchmark | direct replace COPY 约 `1.50×`;COPY+UPSERT merge 收益被 merge/索引维护吃掉 | 不把 COPY+UPSERT 作为导入主路径立即改造;继续保留为后续 B2 |
| 1000w `PARTITION_REPLACE_COPY` | 真实系统链路 SUCCESS,job `240.4s`,LOAD `123.595s`,目标表 `10,000,000` 行 | 分区整批替换 + direct COPY 方向成立 |
| 单大文件 + worker 分片 | 旧行为只剩半量;修复后 fail-fast | `PARTITION_REPLACE_COPY` 必须 `shard_strategy=NONE` |
| 参数调优 | `storagePath + streaming_enabled + chunk_size=10000 + reWriteBatchedInserts=true` 已标注 | `page_size/fetch_size` 对导入 LOAD 降级为非关键项 |

<details>
<summary>详细证据 A:COPY micro benchmark</summary>

#### 2026-06-07 补测: COPY 是否值得立即上

新增脚本:`scripts/local/import-copy-worth-benchmark.sh`。它不跑完整 pipeline,只用专用表
`biz.import_copy_worth_bench` 对比三条 LOAD 写入路径:

- 当前近似路径:PG JDBC `PreparedStatement.batchUpdate` + `INSERT ... ON CONFLICT DO UPDATE`
  (`reWriteBatchedInserts=true`)
- COPY 路径:`CopyManager.copyIn` 到临时表,再 `INSERT ... SELECT ... ON CONFLICT DO UPDATE` merge 到目标表
- 分区整批替换近似路径:`TRUNCATE` 专用目标表后 `CopyManager.copyIn` 直接追加,模拟"整分区清理后重灌"

本地结果(2026-06-07,当前调优已包含 `chunk-size=2000`、业务库 JDBC URL
`reWriteBatchedInserts=true`):

| rows | batch size | batch UPSERT | COPY stage | merge | COPY+merge total | direct replace COPY | speedup(direct) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100,000 | 2,000 | 3.885s / 25,739 rows/s | 1.050s | 1.096s | 2.146s / 46,596 rows/s | 未测 | - |
| 300,000 | 5,000 | 9.418s / 31,853 rows/s | 2.607s | 5.569s | 8.176s / 36,694 rows/s | 未测 | - |
| 300,000 | 5,000 | 7.506s / 39,969 rows/s | 4.419s | 3.933s | 8.352s / 35,920 rows/s | 4.992s / 60,098 rows/s | **1.50×** |

**结论**:在当前必须保留 UPSERT/幂等语义的主路径上,COPY 不值得立即上。COPY 本身很快,但为了支持
`ON CONFLICT`,仍要先 COPY 到临时表再 merge;merge 阶段继续维护唯一索引并执行冲突处理,吃掉了大部分收益。
当前优先级应保持:先跑完整端到端 Tier-A baseline;若 A1/A2/A5 后仍不达 `<60s`,先考虑 B1 多值 INSERT 或更细的
LOAD 阶段 profile,不要直接跳 B2 COPY。

**分区整批替换语义另算**:如果业务是"按 tenant/bizDate 分区整批重灌",即先清理目标分区再追加,可以绕开
`ON CONFLICT`。本地 direct COPY 对 30 万宽表约 **1.50×**;方向成立,但在"已有主键索引的分区上直接 COPY"时仍要维护索引,
不是 10× 杀招。要让 COPY 真正吃满,需要进一步测更强形态:新建空 staging/新分区 → COPY → 建索引 → attach/swap,
用分区级 replace 保证幂等与原子可见。

</details>

<details>
<summary>详细证据 B:1000w PARTITION_REPLACE_COPY 端到端</summary>

#### 2026-06-07 端到端补测:1000w / 近 1GiB PARTITION_REPLACE_COPY

证据文档:[import-partition-replace-copy-10m-system-2026-06-07](../verifications/import-partition-replace-copy-10m-system-2026-06-07.md)。

本轮补测不是 micro benchmark,而是真实系统链路:

`POST /api/triggers/launch` → trigger → orchestrator → Kafka → worker-import → MinIO `storagePath` → PREPROCESS → PARSE → VALIDATE → LOAD `PARTITION_REPLACE_COPY` → PostgreSQL 分区表。

测试对象:

| Item | Value |
|---|---:|
| Source object | `batch-dev/ingress/ta/import-10m-near-g.csv` |
| Object size | `1,008,890,025 bytes` |
| Rows | `10,000,000` |
| Target table | `batch_business.biz.wide_10m_copy` |
| Physical partition | `biz.wide_10m_copy_20260607` |
| Template | `WIDE_10M_COPY_TPL` |
| Job instance | `4006` |
| Trace ID | `ed8dda76e86944c683e67898bd7521cc` |
| Final status | `SUCCESS` |

端到端指标:

| Metric | Value |
|---|---:|
| Job start → finish | `240.4s` |
| Pipeline start → finish | `237.5s` |
| Rows/sec(job window) | `~41.6k rows/s` |
| Rows/sec(pipeline window) | `~42.1k rows/s` |
| MiB/sec(job window) | `~4.00 MiB/s` |
| MiB/sec(pipeline window) | `~4.05 MiB/s` |

Stage 拆解:

| Stage | Duration | Rows/sec | MiB/sec | 判断 |
|---|---:|---:|---:|---|
| RECEIVE | `47 ms` | n/a | n/a | 元数据登记,非瓶颈 |
| PREPROCESS | `6.535s` | `~1.53M rows/s` | `~147.2 MiB/s` | MinIO → spool 很快,非瓶颈 |
| PARSE | `67.774s` | `~147.5k rows/s` | `~14.2 MiB/s` | 第二大耗时 |
| VALIDATE | `39.437s` | `~253.6k rows/s` | `~24.4 MiB/s` | 有成本,但不是最大瓶颈 |
| LOAD | `123.595s` | `~80.9k rows/s` | `~7.8 MiB/s` | 最大耗时,后续调优重点 |
| FEEDBACK | `24 ms` | n/a | n/a | 非瓶颈 |

正确性:

| Metric | Value |
|---|---:|
| rows | `10,000,000` |
| distinct row_key | `10,000,000` |
| min row_key | `RK00000001` |
| max row_key | `RK10000000` |
| partition rows | `biz.wide_10m_copy_20260607 = 10,000,000` |

本轮实际使用并验证的导入调优参数:

| Area | Parameter | Value | Status | Reason / Effect |
|---|---|---:|---|---|
| Trigger payload | `params.storagePath` | `batch-dev/ingress/ta/import-10m-near-g.csv` | 已做 | 近 1GiB 大文件不走 inline `content`,避免 Kafka/JSON payload 承载大内容 |
| File template | `streaming_enabled` | `true` | 已做 | 允许对象流式处理;PREPROCESS 实测不是瓶颈 |
| File template | `chunk_size` | `10000` | 已做 | `LoadStep` 实际按该值分块,减少 LOAD 批次切换成本 |
| File template | `page_size` | `10000` | 非关键 | 本轮导入 LOAD 不按分页读 DB,不是主要调优项 |
| File template | `fetch_size` | `10000` | 非关键 | 本轮导入 LOAD 不走 JDBC 游标读,不是主要调优项 |
| Load plugin | `load_target_ref` | `jdbc_mapped` | 已做 | 走通用 JDBC mapped load plugin |
| Load strategy | `jdbcMappedImport.loadStrategy` | `PARTITION_REPLACE_COPY` | 已做 | 整逻辑分区替换,绕开逐行 UPSERT |
| Replace predicate | `replacePartitionColumns` | `tenant_id,biz_date` | 已做 | 清理范围精确落到单租户单业务日逻辑分区 |
| Runtime binding | `systemBindings.biz_date` | `${bizDate}` | 已做 | 由调度上下文注入业务日期,避免用户 payload 自带分区键 |
| Job sharding | `job_definition.shard_strategy` | `NONE` | 已做 | 单大文件 replace-copy 禁止 worker 分片 |
| Safety guard | `partitionCount > 1` | fail-fast | 已做 | 分片时直接 `IMPORT_LOAD_CONFIG_INVALID`,防止半量写入 |
| Target schema | PG partition | `wide_10m_copy_20260607` | 已做 | 符合批量系统按业务日整分区刷新模型 |

本轮未做的调优项:

| Area | Parameter / Option | Status | Reason |
|---|---|---|---|
| PostgreSQL session | `synchronous_commit=off` | 未启用 | 只在配置里有示例注释;本轮不改变可靠性语义 |
| PostgreSQL session | `work_mem` / `maintenance_work_mem` | 未调 | 未做 PG session 参数矩阵 |
| Index strategy | load 前 drop / load 后 rebuild | 未做 | 保留真实业务主键/分区约束成本 |
| Staging partition swap | 新分区 COPY → 建索引 → attach/swap | 未做 | 这是下一轮验证项;本轮只测直接写既有分区 |
| Parallel COPY | 多连接写不同逻辑分区 | 未做 | 本轮只测单文件单逻辑分区 replace-copy |
| Worker partitioning | `partitionCount=2+` | 明确禁用 | 已验证语义不兼容,不是性能调优方向 |
| JVM / GC | G1/ZGC/heap/JIT 矩阵 | 未作为变量 | 本轮未做 JVM 参数矩阵 |

分片负向结果:

| Trace ID | expected_partition_count | Result | 结论 |
|---|---:|---|---|
| `be1d46fe36694297b5d0280e6c6d2687` | 2 | 旧行为失败,最终只剩 `5,000,000` 行 | replace-copy 与单文件 line-mod 分片语义不兼容 |
| `2e9eff6b5ae3487da02d2638cc4c6054` | 2 | 修复后 `IMPORT_LOAD_CONFIG_INVALID` fail-fast | 不再进入清分区/COPY,保护已有数据 |

**更新结论**:

- 对“按 `tenant_id + biz_date` 整逻辑分区重灌”的导入模板,`PARTITION_REPLACE_COPY` 已有 1000w / 近 1GiB 真实链路证据,方向成立。
- 本轮真正影响结果的调优项是:`storagePath + streaming_enabled=true + chunk_size=10000 + PARTITION_REPLACE_COPY + shard_strategy=NONE`。
- LOAD 仍是最大耗时;下一轮若继续压榨单机,应优先做 PG session / 索引策略 / staging 分区 swap 矩阵,而不是再尝试 worker 分片。

</details>

### 1.7 Benchmark 计划 + 决策树

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

### 1.8 不做(YAGNI / 红线)

- **不做** Citus / 分库 / 分布式 DB 引擎切换(本期);本章就是为了**避免**这种重决策被"现象"驱动而非"数据"驱动。
- **不做** 多主/双写复制(BDR/pglogical);状态机强一致场景不适用(详见 streaming-large-file §5.3)。
- **不做** 切 MySQL/NoSQL;RLS/jsonb/裸 SQL 深耦合 PG by-design(详见 streaming-large-file)。
- **不做** Tier-C2/C3 落生产业务表(数据安全红线)。
- **不做** 单大文件 worker 分片 + `PARTITION_REPLACE_COPY`:每个 worker partition 都会清同一逻辑分区,已验证会产生半量/失败态;现在代码 fail-fast。
- **不把** `page_size/fetch_size` 作为导入 LOAD 主调优项:本轮 LOAD 读的是本地 validated staging,不是 JDBC cursor;真实影响项是 `chunk_size`、COPY 策略、索引/PG session。
- **暂不做** COPY + UPSERT merge 主路径:micro benchmark 显示 COPY 阶段快,但 merge 仍要维护唯一索引和冲突处理,收益被吃掉;仅整逻辑分区 replace 场景继续推进。

### 1.9 触发条件(满足才进 Tier-B / 才考虑 Citus)

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
| 横向加速 | 同 §1.8 不做 Citus | ✅ **keyset 区间分片已在 PR #393 落地**(多租户并发洪峰场景接近 N×) |

**含义**:导入靠 §1.3-1.5 走"换写法快 10×"的路;导出靠 §2.2 多段小调参累积 2-3×,真要数量级提升只能靠 §2.3 已经做完的横向 keyset 分片(且仅多租户并发场景)。

### 2.2 剩余可做(单机维度)

| # | 优化 | 怎么做 | 预期 | 备注 |
|---|---|---|---|---|
| EX-A1 | **生产 JVM**(同导入 A1) | G1GC + 去掉 `TieredStopAtLevel=1` | 2-3× | **复用 §1.3-A1,全 worker 共享** |
| EX-A2 | **`fetch_size/page_size` 调大** | 本地 profile:`page_size=5000`,`fetch_size=5000`;大导出模板可覆盖;SQL template 代码已支持模板 `fetch_size` | 1.2-2× | 导出侧独有;生产全局默认仍保守 |
| EX-A3 | **MinIO multipart upload** | 大产物(≥ 100MB)上传走 multipart 多 part 并发,替单流 `putObject` | 1.5-2×(上传段) | 改 `MinioExportStorage` 一处;**注意:与并行 session 的 storage 抽象(`BatchObjectStore`)合并后再做,避免冲突** |
| EX-A4 | **服务端游标 + `setAutoCommit(false)`** | JDBC 游标行级流式拉取,降低 driver 端缓冲 | 1.2-1.5× | 看实测 |
| EX-A5 | **物理隔离 DB**(同导入 A4) | benchmark 期 PG 独立机 | 量出真值 | 复用 §1.3-A4 |

**叠加预期**:160s → ~50-60s 量级。**没有 10× 台阶,合理预期就是 2-3×**。

### 2.3 已落地与已验证

- **横向加速 keyset 区间分片**(PR #390 + #393):激活时每分片只读游标列一段值区间走索引区间扫,放大 N× → ~1×。
  详见 `docs/backlog/export-partition-keyset-range-2026-06-06.md`(§10 实现记录)。
  覆盖多租户并发洪峰场景;单租户单流耗时不缩(同 Citus 限制)。
- 流式 keyset 游标分页(原有):任意时刻只持一页,worker RSS 常数,无 OOM 风险。

| 场景 | 结果 | 决策 |
|---|---|---|
| seed `jdbc_mapped_export` | DELIMITED 真实链路 SUCCESS | 现有 settlement 类模板链路正常 |
| 1000w DELIMITED 单片 | `125.980s`,输出 `1.407GB`;GENERATE `67.691s`,STORE `53.989s` | 优先做 STORE multipart 与 GENERATE/fetch/keyset 调优 |
| 1000w DELIMITED 4 分片 | 4 文件无重无漏;本地 wall `167.667s` | 正确性成立,但本地 worker 串行;需部署后查并行度 |
| JSON / FIXED_WIDTH / EXCEL | 三种格式 smoke SUCCESS | 格式覆盖完成 |
| 参数/代码修复 | `fetch_size`、`query_param_schema` jsonb、`partition_keyset_range` 已修 | 待 worker 部署/重载后复验生效 |

<details>
<summary>详细证据 C:1000w 导出 + 格式覆盖</summary>

#### 2026-06-07 端到端补测:1000w 导出 + 格式覆盖

本轮补测走真实系统链路,不重启服务、不走前台模拟:

`POST /api/triggers/launch` → trigger → orchestrator → Kafka → worker-export → business PG cursor 查询 → 本地生成文件 → MinIO → `file_record` 注册。

前置说明:

- `biz.wide_10m_copy` 的 1000w 导入数据已被后续 12 行 smoke replace 覆盖;本轮为导出 benchmark 在同一业务分区重新生成 1000w 行夹具。
- 夹具:`default-tenant / biz_date=2026-06-07`,行数 `10,000,000`,物理分区 `biz.wide_10m_copy_20260607`,分区总大小 `2747 MB`。
- 为导出游标加本地 benchmark 索引:`idx_wide_10m_copy_bench_n01(tenant_id,biz_date,n01)`。
- 导出模板:`export_wide_10m_copy_v1`,插件:`sql_template_export`,格式:`DELIMITED`,模板 `page_size=5000`, `chunk_size=10000`。
- SQL 暴露 `w.n01 as id` 作为默认 cursor,避免 `query_param_schema.cursorColumn` 在 PG jsonb 映射上失效时退到不存在的 `id` 列。

覆盖结果:

| 场景 | Trace | Instance | Partitions | Status | Rows | File bytes | Run time | 备注 |
|---|---|---:|---:|---|---:|---:|---:|---|
| seed `jdbc_mapped_export` / DELIMITED | `export-smoke-1780821600` | `4019` | 1 | SUCCESS | 3 | 318 | ~4.1s | 现有 settlement 模板链路 |
| 宽表 1000w 单片 / DELIMITED | `export-wide-single-1780821897` | `4020` | 1 | SUCCESS | 10,000,000 | 1,407,777,822 | 125.980s | benchmark 主样本 |
| 宽表 1000w 4 分片 / DELIMITED | `export-wide-shard4-1780822058` | `4021` | 4 | SUCCESS | 10,000,000 | 1,407,777,906 | 167.667s | 多文件覆盖,本地实际串行消费 |
| SQL template / JSON | `export-json-smoke-1780822336-14078` | `4023` | 1 | SUCCESS | 100 | 17,940 | 5.026s | 格式 smoke |
| SQL template / FIXED_WIDTH | `export-fixed-smoke-1780822336-7100` | `4024` | 1 | SUCCESS | 100 | 4,141 | 5.141s | 格式 smoke |
| SQL template / EXCEL | `export-excel-smoke-1780822336-29052` | `4025` | 1 | SUCCESS | 100 | 8,181 | 11.970s | 格式 smoke |

单片 1000w stage 拆解:

| Stage | Duration | 判断 |
|---|---:|---|
| PREPARE | 8 ms | 非瓶颈 |
| GENERATE | 67.691s | DB 读 + CSV 序列化 + 本地写文件,最大单段 |
| STORE | 53.989s | MinIO 上传第二大段 |
| REGISTER | 23 ms | 非瓶颈 |
| COMPLETE | 6 ms | 非瓶颈 |

单片吞吐:

| Metric | Value |
|---|---:|
| Rows/sec(job window) | ~79.4k rows/s |
| Output size | ~1.31 GiB |
| MiB/sec(job window) | ~10.66 MiB/s |
| GENERATE MiB/sec | ~19.83 MiB/s |
| STORE MiB/sec | ~24.87 MiB/s |

4 分片结果:

| Partition | Rows | File bytes | Partition duration |
|---:|---:|---:|---:|
| 1 | 2,501,899 | 352,210,473 | 40.565s |
| 2 | 2,499,651 | 351,895,977 | 38.672s |
| 3 | 2,498,044 | 351,669,724 | 42.912s |
| 4 | 2,500,406 | 352,001,732 | 43.525s |
| **Total** | **10,000,000** | **1,407,777,906** | **167.667s instance wall** |

4 分片正确性校验:

| Partition | Business hash expected | Exported recordCount | Match |
|---:|---:|---:|---|
| 1 | 2,501,899 | 2,501,899 | yes |
| 2 | 2,499,651 | 2,499,651 | yes |
| 3 | 2,498,044 | 2,498,044 | yes |
| 4 | 2,500,406 | 2,500,406 | yes |

对象存储校验:

- MinIO 下存在单片 `1.3GiB` 对象:`EXPORT-WIDE-10M-SINGLE-20260607164457/...csv`。
- MinIO 下存在 4 个分片对象,每个约 `335-336MiB`。
- JSON / FIXED_WIDTH / EXCEL 三个 smoke 对象均存在并已登记 `file_record`。

本轮实际使用并验证的导出调优参数:

| Area | Parameter | Value | Status | Reason / Effect |
|---|---|---:|---|---|
| File template | `page_size` | `5000` | 已做 | SQL template 每页 5000 行,减少分页轮次 |
| File template | `chunk_size` | `10000` | 已做 | CSV 写入按更大块 flush,降低 writer flush 频率 |
| Cursor / index | cursor column | `id = n01` | 已做 | 数值单调游标,单片 keyset 翻页稳定 |
| PG index | `(tenant_id,biz_date,n01)` | 已做 | 支撑 `id > cursor` 的范围读取 |
| Job sharding | `partitionCount=4` | 已验证 | 4 文件无重无漏,但本地 worker 实际串行消费 |
| Format strategy | JSON / DELIMITED / FIXED_WIDTH / EXCEL | 已验证 | 4 种 format 均走真实 worker 链路成功 |

本轮未做 / 未生效项:

| Area | Parameter / Option | Status | Reason |
|---|---|---|---|
| JVM | EX-A1 生产 JVM | 未做 | 用户要求不重启,本轮沿用当前后台 worker |
| MinIO | EX-A3 multipart upload | 未做 | 当前 STORE 仍是单段上传;STORE 已占 53.989s,仍值得后续做 |
| JDBC fetch | `fetch_size=5000` | 已修待部署复验 | `sql_template_export` 已按模板/worker 配置创建带 fetchSize 的分页查询 |
| Keyset-range opt-in | `partition_keyset_range=true` | 已修待部署复验 | planner 已支持顶层、`query_param_schema.partition_keyset_range`、`sqlTemplateExport.partitionKeysetRange` |
| Worker 并发 | 4 partition 并行 | 未发生 | 日志显示同一 `export-task-consumer-0-C-1` 顺序处理 task 4227-4230;本地 4 分片比单片慢 |

发现项:

1. **Keyset-range 配置落点缺口(已修待部署)**:原实现要求 `templateConfig["partition_keyset_range"]`,但当前 DB schema 无该列;真实模板只能存进 `query_param_schema`。本轮已让 `ExportKeysetRangePlanner` 读取 `query_param_schema.partition_keyset_range` 和 `sqlTemplateExport.partitionKeysetRange`。
2. **本地 4 分片未并行**:`expected_partition_count=4`,但 worker 日志显示单 consumer thread 顺序处理 4 个 task。若目标是单 job 内并行导出,需继续查 Kafka topic partition / consumer concurrency / worker backpressure / task dispatch 分配。
3. **导出瓶颈更新**:单片 1000w 下 GENERATE 67.691s + STORE 53.989s,两段合计占绝大多数;下一步最值得做的是 STORE multipart 与真正并行分片,不是寻找 COPY 类方案。

</details>

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
- **暂不把** `page/fetch/chunk=5000/5000/10000` 设为生产全局默认:大宽表收益明确,但小内存/小租户场景会放大单页 Map 内存;生产建议模板级覆盖。
- **暂不把** Kafka listener 改异步 ACK:能解决单 partition 串行问题,但会改变 offset 提交/失败重投语义;先查 topic partition、producer key、consumer concurrency。
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

> §1.7 / §2.4 给的是「**测什么 / 通过门槛**」(what + why);本章给的是「**怎么测**」(how)——
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

COPY 是否值得上的微基准脚本已落地:

```bash
ROWS=300000 BATCH_SIZE=5000 bash scripts/local/import-copy-worth-benchmark.sh
```

完整 pipeline 的三轮取中位数仍按下方模板跑。

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
- [x] **8a. 导出真实链路覆盖 + 1000w benchmark**(见 §2.3 的 2026-06-07 补测;DELIMITED/JSON/FIXED_WIDTH/EXCEL + 单片/4 分片)
- [x] **8b. 导出参数/配置 bug 修复**:`query_param_schema` cursor/keyset + SQL template `fetch_size` 已修,待部署复验
- [ ] **8c. 导出版同样跑一遍 EX-A1~A5 矩阵**(差异点见 §2.2;本轮未重启,未做 JVM/multipart/fetch 矩阵)
- [ ] **9. 据 §1.7 / §2.4 决策树**:够用即停;不够继续 §1.4 Tier-B(B1 多值 INSERT)

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

- `batch-worker-export/.../plugin/SqlTemplateExportDataPlugin.java`(SQL template `fetch_size` 生效)
- `batch-worker-export/.../plugin/ExportKeysetRangePlanner.java`(`query_param_schema` keyset-range opt-in)
- `batch-worker-export/.../sql/SqlTemplateExportSpec.java`(PG jsonb `query_param_schema` 解析 cursorColumn)
- `batch-worker-export/.../plugin/GenericJdbcMappedExportDataPlugin.java`(jdbc_mapped keyset/hash 分片)
- `batch-worker-export/.../infrastructure/MinioExportStorage.java`(EX-A3 multipart;等存储抽象)
- `batch-worker-export/src/main/resources/application-local.yml`(本地 benchmark 推荐 page/fetch/chunk)
- `batch-worker-export/src/main/resources/application.yml`(生产保守默认;大导出建议模板级覆盖)

### 5.3 共用配置/部署

- `docker/postgres/conf/postgresql.conf`(A3 调优点;若无独立配置文件 → 走环境变量/cmdline)
- 启动参数:`docker-compose*.yml` / `scripts/local/*.sh`(A1 / EX-A1 JVM 切换)

### 5.4 相关文档

- 实证:[streaming-large-file-import-export-2026-06-06](../verifications/streaming-large-file-import-export-2026-06-06.md) §5.3 扩展决策树
- 导出 keyset 分片实现:[export-partition-keyset-range-2026-06-06](./export-partition-keyset-range-2026-06-06.md)

### 5.5 分支纪律

- 业务/配置改动 → `feature/<topic>` 标准分支 → PR → main
- 不涉及部署分支(本文优化均在业务模块或 docker 配置层)
