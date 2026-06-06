# 百万级宽表长字段 流式导入/导出 实证(2026-06-06)

worker-import / worker-export **流式大文件**链路真实跑通验证:以 ~800MB、100 万行、含长字段的宽表 CSV,
直接突破旧的 **512 MiB `byte[]` 内存天花板**(`MAX_OBJECT_BYTES`),证明 import spool-stream 与 export keyset-cursor 两侧均不把整文件读进堆。

## 1. 结论速览

| 方向 | 结果 | 数据规模 | worker 堆内存峰值(RSS) | 旧 512MiB 上限 |
|---|---|---|---|---|
| **IMPORT** | ✅ SUCCESS | 845,781,334 B(**807 MiB**)/ 1,000,000 行 | **~180 MB** | 远超(536,870,912 B) |
| **EXPORT** | ✅ SUCCESS | 852,670,233 B(**813 MiB**)/ 1,000,001 行(含表头) | **~142 MB** | 远超 |

两侧 worker RSS 全程 < 200MB 而处理 800MB+ 文件 → **整文件从不进堆**,流式成立。
旧 `byte[]` 路径在 512 MiB 会 fail-fast 拒绝,本次直接吞下。

## 2. 测试数据

100 万行宽表,23 列:`row_key` + `c01..c15`(普通文本)+ `clong1/clong2`(**长字段**,300/299 字符)+ `n01..n05`(数值)。
- 生成脚本:`/tmp/gen-wide.sh`(awk 直出),`/tmp/wide.csv` = 807 MiB / 1,000,001 行(含表头)
- 落 MinIO:`batch-dev/ingress/ta/wide-1m-20260606.csv`(845,781,334 B)
- 目标表:`batch_business` 库 `biz.wide_demo`(tenant ta)

## 3. IMPORT 流式直载

### 3.1 触发(storagePath 自动加载,绕 Kafka 内联 payload)

```
POST localhost:18081/api/triggers/launch        # X-Internal-Secret: <.env.local: BATCH_INTERNAL_SECRET>
jobCode = TA_IMPORT_CUSTOMER                     # 复用 IMPORT pipeline 路由
params  = { templateCode: WIDE_DEMO_TPL,
            storagePath:  ingress/ta/wide-1m-20260606.csv,
            storageBucket: batch-dev,
            fileName: wide-1m-20260606.csv, fileFormatType: CSV }   # 不带 content → 走对象路径
```

`ReceiveStep` 直接 Jackson 反序列化 params → `ImportPayload`;`PreprocessStep` 命中流式分支:
**纯文本 CSV + 无变换(compress/encrypt/preprocess_pipeline 均空)+ 对象 size ≥ spool 阈值 16MB**。

### 3.2 流式证据(worker-import.log)

```
PreprocessStep - import preprocess streamed object to spool (no heap buffering):
  bucket=batch-dev, object=ingress/ta/wide-1m-20260606.csv, bytes=845781334,
  spool=/var/folders/.../batch-preprocess-obj-*.raw
```

- 走 `PreprocessStep.streamObjectToSpoolAndReturn`(`Files.copy(InputStream, Path)` 8K 缓冲),**不分配整文件 byte[]**
- PARSE 逐行流式解码 → VALIDATE → LOAD(`GenericJdbcMappedImportLoadPlugin` `batchUpdate` 每批 500 行 UPSERT)
- 阶段推进:RECEIVE→PREPROCESS→PARSE→VALIDATE→LOAD 全过,`run_status=SUCCESS`,`finished 11:07:14`

### 3.3 落库校验(batch_business.biz.wide_demo)

| 指标 | 值 |
|---|---|
| 总行数 | **1,000,000** |
| clong1 长度 min | 300 |
| clong2 长度 max | 299(长字段无截断) |
| 抽样 `WIDE-500000` | clong1=`0000...`(300位)/ n01=`0.50`(500000%1000=0 ✓)/ c15=`val-15-500000` |
| worker-import RSS | 处理中 ~180MB,收尾回落 38MB |

## 4. EXPORT 流式导出

### 4.1 模板与触发

`WIDE_DEMO_EXPORT_TPL`(克隆 TA_EXPORT_REPORT_TPL):
- `export_data_ref = sql_template_export`,`query_param_schema.sqlTemplateExport.cursorColumn = id`
- `default_query_sql`: `select id,row_key,c01..c15,clong1,clong2,n01..n05 from biz.wide_demo where tenant_id=:tenantId and (:batchNo is not null) order by id`
- `file_format_type=DELIMITED`,`streaming_enabled=true`,`page_size/fetch_size=1000`

```
POST localhost:18081/api/triggers/launch
jobCode = TA_EXPORT_REPORT                       # EXPORT pipeline
params  = { templateCode: WIDE_DEMO_EXPORT_TPL }
```

### 4.2 流式机制(keyset 游标分页)

`SqlTemplateExportDataPlugin.loadDetailPage` 把基础 SQL 包成 CTE,按游标列 keyset 翻页:
```sql
WITH base AS ( <配置SQL> )
SELECT * FROM base WHERE base.id > :__cursor ORDER BY base.id ASC LIMIT :__limit
```
GenerateStep 循环 `loadDetailPage`(每页 1000 行)→ 增量写出格式 → MinIO,**任意时刻只持有一页**,不全量进堆。
(安全:`forbidSelectStar`、schema 白名单、`explainCheckEnabled` 时 `maxEstimatedRows=5M` 预检——1M 通过。)

### 4.3 产物校验(MinIO)

| 指标 | 值 |
|---|---|
| 对象 | `batch-dev/outbound/TA_EXPORT_REPORT/2026-06-06/2026-06-06/v1/TA_EXPORT_REPORT_2026-06-06_2026-06-06.csv` |
| 大小 | **852,670,233 B(813 MiB)** |
| 行数 | **1,000,001**(1 表头 + 100万行) |
| 表头 | `id,row_key,c01..c15,clong1,clong2,n01..n05` ✓ |
| 阶段 | PREPARE→GENERATE→STORE→REGISTER→COMPLETE,`run_status=SUCCESS`,`finished 11:14:40` |
| worker-export RSS | 全程 ~142MB |

## 5. 性能 / 耗时

> 环境:本地单机 Docker(postgres/minio/kafka 同宿主),worker JVM `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`(快启低优化,**非性能调优档**),chunk/page=500/1000。数据为参考量级,非基准跑分。

| 方向 | 端到端耗时 | 行吞吐 | 字节吞吐 | 实例时间 |
|---|---|---|---|---|
| **IMPORT** | **182.5 s** | **~5,480 行/s** | **~4.63 MB/s** | 11:04:12 → 11:07:14 |
| **EXPORT** | **160.6 s** | **~6,230 行/s** | **~5.31 MB/s** | 11:12:00 → 11:14:40 |

**IMPORT 阶段拆分**(worker-import.log 时间标记):
- PREPROCESS 流式直载:MinIO 对象 845 MB → /tmp spool 文件,起始 11:04:12 → spool 完成 11:04:29,**≈17 s(≈50 MB/s)**
- 余下 PARSE(逐行解码)+ VALIDATE + LOAD(`batchUpdate` 每批 500 行 UPSERT)≈ 165 s,占大头(DB 写入为瓶颈,非内存/IO)

**说明**:
- 瓶颈在 DB 批量写/读(单机 PG + 序列化 GC),**不在流式 IO**;流式本身把 800MB 文件压在 ~17s spool + 常数内存。
- 旧 `byte[]` 路径在此规模直接 fail-fast(512MiB 拒收),**无可比耗时**——流式是「能不能做」而非「快多少」的突破。
- 提速空间(未做,YAGNI):调大 chunk/fetch、并行分片(export 已有 SizeBased 分片 + 4-worker 能力,本次单流验证未启用)、关序列化 GC。

### 5.1 分片对照实验(IMPORT 4 分区 vs 单流)

设 `job_definition.shard_strategy=STATIC`、launch `params.partitionCount=4` 重跑同一文件:

| 模式 | 墙钟 | 行数 | 正确性 | 结果 |
|---|---|---|---|---|
| 单流(NONE) | **182.5 s** | 1,000,000 | — | SUCCESS |
| **4 分区(STATIC)** | **469.9 s** | 1,000,000 | 100万唯一 row_key,无重无漏 | SUCCESS |

**结论:同一大文件场景,分片反而慢 2.6×。数据正确,但性能净亏。** 两个根因:

1. **单文件 fan-out 冗余**:`ParseStep` 的 `partition_aware_parse` 按 `lineNo % 4 == partitionNo-1` 过滤——
   每个分区都要**完整 stream + parse 全量 845MB**,各自只保留 25 万行(日志 `partition filter applied: partition=N/4, kept=250000/1000000`)。
   即 4× 重复下载 + 4× 重复解析,只省了 LOAD 的 1/4。
2. **共享 file_record 状态争用**:4 分区经 dedup 复用同一 `fileId=347`,并发推进其状态机 → 乐观锁 `state_conflict_detail`;
   partition 1 反复冲突重试 **8 次**,卡了约 250s 才开始 LOAD,严重串行化。

**适用边界**:分区分片**不适合把单个大文件拆并行**。单大文件仍应单流。(实验后已还原 `shard_strategy=NONE`。)

### 5.2 多文件并发对照(验证「多文件无冗余/无冲突」主张)

把同样 100 万行拆成 **4 个独立文件各 25 万行**(row_key 不相交,各 ~200MB),**4 个导入并发触发**(各自独立文件 → 各自 file_record/pipeline,单分区单流):

| 方案 | 墙钟 | state_conflict | 冗余解析 | 数据 |
|---|---|---|---|---|
| 单流(1 个 1M 文件) | **182.5 s** | 0 | 无 | 1M ✓ |
| 单文件 4 分区(STATIC) | **469.9 s** | **8** | 4×(每分区 parse 全量 1M,留 25万) | 1M ✓ |
| **4 独立文件并发**(各 25万) | **206 s** | **0** | **无**(各 parse 自己 25万) | 1M 唯一 ✓ |

**主张验证通过**:多文件 → 各自 file_record → **0 状态冲突、无冗余解析**,比单文件 4 分区**快 2.3×**。

**但暴露更真实的瓶颈——单机 Postgres 写入封顶**:4 文件并发(206s)**并不比单流(182.5s)快**,反略慢。
总写入量相同,单 PG 吞吐到顶,并发只增锁/IO 争用不增吞吐——4 实例完成时间明显错开(56/98/155/204 s),**没有真并行,被 DB 串着喂**。

**完整结论**:
- **单文件拆分区** = 净亏(冗余 + 共享 file_record 争用),别用。
- **多文件并发** = 结构正确(无冗余、无冲突),但**单机受 DB 限不提速**;真正并行加速需**横向扩展**(多 worker 节点 + 分库 / DB 写扩容),那时多文件才显威力。
- **单机部署**:无论导入导出,**单流最优**。

> EXPORT 未做分片:`sql_template_export` 路径不分区感知(整个 worker-export 无 partition 数据切分),强行分 N 片会各跑全量 query 产生 N× 重复。该路径维持单流 160.6s。

### 5.3 性能扩展决策树(单机 → 分库 → 多节点)

实验确立的核心因果:**DB 写入吞吐是闸门,worker 节点数不是。** §5.2 已证「4 并发 writer + 1 PG = 不提速」——
光加 worker 节点只是排队抢同一个 DB 的写锁;worker 多节点只有在「DB 不再是瓶颈」之后才线性有效。
因此扩展按以下顺序,**绝大多数场景在第 1 步即止**:

**① 先榨单机(零分布式复杂度,优先做)**
当前 LOAD 用的是最慢姿势:`batchUpdate` 每批 500 行、逐行 `INSERT ... ON CONFLICT`(UPSERT)+ PK 索引维护 + SerialGC。
同一台机、不加硬件即可大幅提速:

| 手段 | 量级 | 代价 |
|---|---|---|
| `COPY` 替代逐行 INSERT | 单 PG 数十万行/s | 改 LOAD 写法 |
| 加大 batch(500 → 5000+) | 2~5× | 调参 |
| 导入期临时去索引、导完重建 | 2~10× | 流程改造 |
| `UNLOGGED` 表 / 关 `synchronous_commit` | 2~3× | 视可靠性要求 |
| 生产 JVM(G1/并行 GC + C2,替代 SerialGC+TieredStopAtLevel=1) | 数成 | 改启动参数 |

> 预期:单流 182s → **30~60s**(百万级夜批足够),无需分布式。

**② 不够再扩 DB 写**
目标表按 `hash(row_key)` 做 **PG 原生分区**(单 PG 内降低单表锁/索引争用,允许更多并发 writer)→
仍不够再 **分库**(每 worker 写不同 DB shard,真正横向写扩容)。

**③ DB 能吃下并发了,再加 worker 节点 + 多文件并发**
此时 §5.2 的多文件并发(各自 file_record、0 冲突、无冗余)才转化为**线性加速**——
多文件并发是「终局」的入场券,但前置条件是 ①②。

**一句话**:「多 worker + 分库」是终局,但先别急着上节点;90% 的场景在第 ① 步(单机写法优化)就停了。

## 6. 过程中排查/修正

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | IMPORT LOAD 报 `no ImportLoadPlugin registered for id: IMPORT_LOAD_JDBC_MAPPED` | `WIDE_DEMO_TPL.load_target_ref` 误填**常量名** `IMPORT_LOAD_JDBC_MAPPED`,而 `require()` 查的是常量**值** `jdbc_mapped`(`WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED="jdbc_mapped"`) | `set load_target_ref=null`(对齐 TA_IMPORT_CUSTOMER_TPL,空→默认 `jdbc_mapped`)。**非代码 bug,模板数据错误** |
| 2 | 误以为 `biz` schema 丢失 | 业务表在**独立库 `batch_business`**(端口 15432,同 postgres 容器),非 `batch_platform`;平台元数据才在 `batch_platform` | 查业务行数须 `-d batch_business` |

## 7. 环境/复现要点

- 业务数据库 `batch_business`(15432)与平台库 `batch_platform` 分离;`biz.*` 在前者
- import 模板 `load_target_ref` 必须 NULL 或 `jdbc_mapped`(常量值),勿填常量名
- MinIO 容器内 `mc`,alias `local`,bucket `batch-dev`
- 关键代码:`PreprocessStep.streamObjectToSpoolAndReturn` / `LoadStep.executeStreaming`(import);`SqlTemplateExportDataPlugin.loadDetailPage` + `GenerateStep`(export)
- 配置开关:`batch.worker.import.max-object-bytes`(默认 512MiB,仅约束**回退 byte[] 路径**;流式路径不受限,仅受 /tmp 磁盘)
