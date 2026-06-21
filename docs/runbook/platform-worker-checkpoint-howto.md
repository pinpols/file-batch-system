# 平台 Worker 续跑位点 howto (ADR-038 / Phase 4.5)

> Status: **P1 + P2 + P3 已就绪**(P1/P2 = 2026-06-02;P3 Export GENERATE 续跑 = 2026-06-05)。

## 解决的问题

平台 worker 的 Import LOAD 当前是「全量重跑」模型:任务超时 / 进程崩 / lease 被回收后,
下一个 worker 从**第 0 行**重头读 staging 文件 + 重新调 `plugin.loadChunk`。
在百万行级真实任务下,业务库被反复 INSERT/UPDATE 风暴冲击,SLA 受损。

本特性引入持久化「续跑位点」表 `batch.pipeline_progress`,LoadStep 每完成一个 chunk
就 UPSERT 当前行号 + 已处理累计;重派时从最后一次 advance 的行号续跑,前面已处理的 chunk
不重做。

## 开关

| 配置 | 默认 | 含义 |
|---|---|---|
| `batch.worker.checkpoint.enabled` | `false` | ADR-038 续跑位点总开关。**关闭时行为与未引入本特性完全一致**(从 0 跑、不写位点)。 |

灰度建议:
1. dev 环境长期开 → 跑日常用例验证不破坏正路径
2. staging 灰度 1~2 周,看 `batch.pipeline_progress` 表行数 / 重派任务命中续跑次数
3. prod 按租户 profile 渐进打开;**先在百万行级业务的租户上开**,小数据量租户继续 false 即可

## 同事务约束(important)

ADR-038 §决策二要求「chunk 业务写 + 位点更新同事务」。实际实施:

- **Import LOAD**:业务数据写到**租户业务 DB**(`importBusinessDataSource`),位点写到**平台 DB**
  (`batch.pipeline_progress`)。**跨库无法 1PC 原子**。本实现采用「业务先 commit → 位点后 advance」+
  插件幂等(多租 `UNIQUE(tenant_id, ...)` + `ON CONFLICT`)回退。
- **崩溃窗口**:业务 commit 完但 advance 失败的瞬间崩溃,重派时位点落后 ≤ 1 chunk,
  会重做该 chunk;`strict-idempotency=true` 默认下 plugin 的 `INSERT ON CONFLICT DO NOTHING/UPDATE`
  保证数据一致,只浪费一次 chunk 的 CPU。
- **不可用场景**:plugin 未开 `strict-idempotency` + 业务表无唯一约束的场景,续跑会双写。
  **必须确认 plugin 是 `GenericJdbcMappedImportLoadPlugin` 且 `strict-idempotency=true` 再启用**。

## 前置校验(R3-3,自动拦截)

跨库无 1PC 决定了「续跑数据安全 100% 靠 plugin 幂等」。R3-3 起 LoadStep 在进入续跑路径前**强制**
校验 plugin 自报的幂等能力,声明不充分直接拒跑(`IMPORT_LOAD_CONFIG_INVALID`),避免运维忘记
确认就把开关打开导致双写。

`ImportLoadPlugin#idempotencyCapability()`(`batch-common`)default 返回 `UNKNOWN`,plugin
必须**显式 override** 才能在续跑开关下使用:

| 能力 | 含义 | 续跑开关行为 |
|---|---|---|
| `IDEMPOTENT_BY_UNIQUE_CONSTRAINT` | 业务表带多租 `UNIQUE(tenant_id, ...)` + `ON CONFLICT DO NOTHING/UPDATE` | 放行 |
| `IDEMPOTENT_BY_PLUGIN_LOGIC` | plugin 自身在 `loadChunk` 内查后写 / 业务键去重 | 放行 |
| `NONE` | 明确不幂等(裸 `INSERT`,无 ON CONFLICT) | 拒跑 |
| `UNKNOWN`(默认) | plugin 未声明 | 拒跑 |

平台自带 plugin:
- `GenericJdbcMappedImportLoadPlugin` → `IDEMPOTENT_BY_UNIQUE_CONSTRAINT`(依赖业务表多租 UNIQUE)

**新增 plugin 接入续跑清单**:
1. 确认业务表存在多租 UNIQUE 约束,或 plugin 内部做了行级去重
2. override `idempotencyCapability()` 返回 `IDEMPOTENT_BY_*`
3. 若达不到 1/2,保持 `UNKNOWN`(默认),续跑开关下自动拒跑 — 这是**期望的安全网**,不是 bug

## Export GENERATE 续跑(P3,2026-06-05)

Export 的「全量重跑」痛点与 Import 同源:GENERATE 写大文件途中崩溃 / lease 回收,重派从第 0 行重新拉数据 + 重写文件。
P3 用**单文件 + 字节位点截断**方案接入(spike `docs/spike/adr-038-p3-export-file-recovery.md` 原选 chunk-分片+STORE 拼接 Option A;
落地时改用更轻的单文件法 —— 崩溃安全性等价、无 concat 数据完整性风险、且与本配置 javadoc 既有的「续 cursor」语义一致)。

机制:
- **确定化文件路径**:续跑开启时生成文件落 `${java.io.tmpdir}/file-batch-export/inst-<pipelineInstanceId>.<ext>`(关闭时仍为随机 temp,行为不变)。同实例重派落同一文件,崩溃后能找回残文件。
- **页边界 fsync + 位点**:每完成一页(`loadDetailPage` 一批)就 `flush + FileDescriptor.sync()` 落盘,把 `<byteOffset>@<typed-cursor>` 记进 `batch.pipeline_progress`(stage=`GENERATE`)。位点**仅在还有后继页时记**;终页 cursor=null 不记(交由完成时补记总行数),保证存下的 cursor 永远是有效续跑起点。
- **续跑**:`FileChannel.truncate(byteOffset)` 截断崩溃残尾 → 数据插件从 `cursor` 续拉下一页 → append 续写。崩溃窗口最多重做「最后一个已记位点之后那一页」,因 truncate 先于重写故**不重复、不丢行**;JSON 收尾 `]}` 只在整体完成时写一次。
- **幂等跳过**:GENERATE 已 `completed` 且文件仍在(STORE 未消费)→ 重派不重生成,补齐下游 attribute 即可。

### 前置约束(对齐 Import 的幂等校验)

Import 续跑要求 plugin 自报幂等;Export 续跑的等价约束是 **cursor 必须可类型安全往返**:

| cursor 类型 | 续跑行为 |
|---|---|
| `Long/Integer/BigInteger/BigDecimal/Boolean/String/Timestamp/Date(LocalDate/LocalDateTime/Instant)` | 支持续跑(`GenerateCursorCodec` 带类型标签序列化,绑回 SQL 保类型) |
| `UUID` / 其他 | **不支持**(`uuid > text` 无操作符等)→ 自动**降级为不可续跑的全量跑** + 打一次 WARN(`resume disabled for instanceId=…`);生成本身不受影响 |

- **Excel(`EXCEL`)不参与续跑**:SXSSF 工作簿是 zip,无法 append/truncate。续跑开启时 Excel 仍走随机 temp + 全量重跑(每次重派从头生成),由本特性显式短路,不报错。可续跑格式:`JSON` / `DELIMITED` / `FIXED_WIDTH`。
- cursor 类型不可序列化只影响**续跑能力**,不影响**正确性**(降级后崩溃即从头重跑)—— 这是期望的安全网,不是 bug。

### 验证 Export 续跑

```sql
SELECT tenant_id, pipeline_instance_id, position_marker, processed_count, updated_at
FROM batch.pipeline_progress
WHERE stage = 'GENERATE' AND completed = false
ORDER BY updated_at DESC LIMIT 20;
```

`position_marker` 形如 `<byteOffset>@L|<cursorValue>`(类型标签:`L`=Long、`TS`=Timestamp、`S`=String…)。
观察残文件:`ls -l $TMPDIR/file-batch-export/inst-*.<ext>`。

## 反例(don't)

❌ **位点 advance 不能写到业务 DB** —— 跨库唯一约束 / RLS / 升级耦合,违反"位点是 worker 内部记录"
   (ADR-038 §范围边界)
❌ **不能改成 "位点先 advance → 业务后 commit"** —— 业务 commit 失败时位点已前进,
   重派会跳过未写入的 chunk = 数据丢失
❌ **不能用 `@Transactional` 把跨库写包成同事务** —— Spring 单 DataSource tx mgr 不支持;
   引入 XA / JTA 是 ADR-035 §决策 P3 明确否决的方向

## 操作

### 启用步骤

1. 验证 plugin 是 `GenericJdbcMappedImportLoadPlugin` 且 `strict-idempotency=true`
2. 配置 worker:`batch.worker.checkpoint.enabled=true`(application-<profile>.yml)
3. 重启 worker(配置非热加载)
4. 观察 `batch.pipeline_progress` 表行数增长 = 正常工作

### 验证续跑

```sql
-- 看进行中的 LOAD 位点
SELECT tenant_id, pipeline_instance_id, position_marker, processed_count, updated_at
FROM batch.pipeline_progress
WHERE stage = 'LOAD' AND completed = false
ORDER BY updated_at DESC LIMIT 20;

-- 看 24h 内完成的位点(归档前)
SELECT tenant_id, pipeline_instance_id, processed_count, completed_at
FROM batch.pipeline_progress
WHERE stage = 'LOAD' AND completed = true
  AND completed_at > current_timestamp - interval '24 hours'
ORDER BY completed_at DESC;
```

### 关闭 / 回滚

- 配置改回 `batch.worker.checkpoint.enabled=false` + worker 重启;**已写入的位点行不删除**
  (无害,下次开关再打开时,正在进行中的实例可续跑)
- 紧急回滚:删除 plugin 实现里的续跑逻辑代码无意义 —— 开关关掉即可,代码路径完全跳过。
  V164 migration 不可回滚(对 DB 结构无破坏),无需 backout 脚本。

## 未做项(follow-up)

| 项 | 跟进 PR |
|---|---|
| **阶段级续跑**(ADR-038 §决策四 P4) | 与 ADR-020 batch-day-replay 语义重叠,需对齐后再做。本 PR 暂不实现。 |
| **确定化残文件清理**(GENERATE 崩溃后从未重派的孤儿 `inst-*.<ext>`) | 走 tmp 目录,OS / 容器重启即清;若要主动清,后续可加按 mtime 的定期清扫(低优先,YAGNI)。 |

## 相关

- ADR-038(本特性的设计依据)
- ADR-020(batch-day-replay,与 P4 阶段级续跑的语义对齐对象)
- CLAUDE.md §archive 冷表对齐(V164 已配套 archive 镜像 + ArchiveSchemaDriftCheck 登记)
