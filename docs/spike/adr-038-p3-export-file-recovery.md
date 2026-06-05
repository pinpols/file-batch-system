# Spike: ADR-038 P3 Export GENERATE 文件恢复方案对比

- **Status**: ✅ **已落地（2026-06-05）** —— 采用**单文件 + 字节位点截断**方案（详见下方「落地说明」），非本 spike 原推荐的 Option A（chunk 分片 + STORE 拼接）。
- **落地说明**: 本 spike 原推荐 Option A（每页写 `chunk-N` 文件、STORE 阶段按格式拼接）。落地时改走更轻的**单文件法**:GENERATE 仍写单文件,每页 `flush + FileDescriptor.sync()` 后把 `<byteOffset>@<typed-cursor>` 记进位点;续跑时 `FileChannel.truncate(byteOffset)` 砍掉崩溃残尾 + 从 cursor 续写;STORE **完全不改**。两者崩溃安全性等价(都靠 fsync 边界 durability + 续跑前先 truncate,不会重复/丢行),但单文件法省掉了整套 concat 机器与 4 套 per-format 拼接逻辑(数据完整性风险点大减),且与 `WorkerCheckpointProperties` javadoc 既有的「Export 续 cursor」描述一致。Excel 不可 append/truncate → 全量重跑兜底(同 Option C);cursor 类型不可序列化(如 UUID)→ 降级全量跑 + WARN。实现见 `batch-worker-export` 的 `GenerateCheckpoint` / `GenerateCursorCodec` / `ResumableExportFile` + `GenerateStep`;运维见 [runbook](../runbook/platform-worker-checkpoint-howto.md) §Export GENERATE 续跑。
- **原 Status**: Spike（方案对比 + 推荐落地草案，纯文档不动代码）
- **Related**: [ADR-038 Platform Worker Checkpoint & Resume](../architecture/adr/ADR-038-platform-worker-checkpoint-resume.md)
- **Runbook**: [platform-worker-checkpoint-howto](../runbook/platform-worker-checkpoint-howto.md)
- **Scope**: 仅评估 Export GENERATE 阶段的临时文件恢复策略；不包含 STORE 跨 worker 协调、不重谈 LOAD（已 P2 落地）
- **Out-of-scope**: Excel append、跨 worker 分布式 chunk 合并、对象存储 multipart upload 改造

---

## 1. 背景

ADR-038 已在 main 落地两个阶段：

| 阶段 | 范围 | 现状 |
| --- | --- | --- |
| **P1** | `ProcessingPosition` 模型 + `pipeline_progress` 持久化 + migration（含 archive 镜像） | ✅ 已合并 |
| **P2** | Import **LOAD**：`flushChunkWithPosition` 同事务推进位点 + 启动 skip 行号续跑 + `markCompleted` | ✅ 已合并 |
| **P3** | Export **GENERATE**：cursor 位点 + 单文件字节位点截断续跑 | ✅ 已落地（2026-06-05，单文件法,见顶部「落地说明」） |

P3 卡点不在"位点存哪儿"——P1 已经把模型铺好了——而在 **文件 I/O 是非事务性的**：
GENERATE 阶段把游标分页结果写到本地临时文件（`${tmpdir}/${pipelineInstanceId}.<ext>`），worker 崩溃时文件可能停在任意字节位置：

- JSON：可能停在 `,{` 之间，文件本身不再是合法 JSON 数组；
- Delimited / FixedWidth：可能停在某一行中段，最后一行 truncate；
- Excel：单 workbook 二进制结构，崩溃前未 `workbook.write()` 整个文件作废。

**单靠"位点回到 cursor-XYZ 续写"无法恢复**——位点合法，文件物理上已经错位/损坏。续跑要么把错位文件交给下游 STORE（用户拿到坏文件），要么直接整段重做（当前 P3 砍掉后的行为，不解决用户痛点）。

本 spike 比较三个方案，决定 P3 上线前先做哪条路径。

参考代码路径：

- `batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/GenerateStep.java`
- `batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/StoreStep.java`
- `batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/format/AbstractExportFormat.java#generatePaged`
- 四个 format：`JsonExportFormat` / `DelimitedExportFormat` / `FixedWidthExportFormat` / `ExcelExportFormat`

---

## 2. 三方案对比

### 2.1 对比表

| 维度 | **A. 分片临时文件 + STORE 拼接** | **B. 单文件 APPEND + byte offset** | **C. 全 STORE 重做（兜底）** |
| --- | --- | --- | --- |
| **核心想法** | 每 page 写独立 `chunk-N.<ext>`；STORE 阶段顺序拼接产正式文件 | 单一临时文件以 `APPEND` 打开；位点存 byte offset，崩后 `truncate(offset)` 再续写 | 不引入文件级恢复；GENERATE 失败永远从头重做 |
| **续跑行为** | 扫 tmpdir 已落地 chunk → `lastSafeChunk = max(N)` → 删 > N（可能 partial）→ 从 chunk-N 对应 cursor 续写 chunk-(N+1) | 读位点 `(cursor, byteOffset)` → `truncate(byteOffset)` → seek 末尾 → 续写 | 删 tmpdir → cursor=null 从头 generate |
| **数据安全** | 高：每个 chunk 文件要么完整要么删除；分片粒度对齐 page 边界 | 中：依赖 truncate + offset 准确；JSON 数组语法 / Excel 二进制都需要"补尾"或"补头"逻辑；offset 错一字节整文件坏 | 高：但代价是用户 SLA 完全不达标 |
| **侵入性** | 中：`AbstractExportFormat.generatePaged` 改成"每页一个文件"；STORE 增加 concat 步骤 | 高：四个 format 各自要实现 truncate + 续写头/尾；Excel 改不了 | 低：基本只改 cursor 缓存 |
| **STORE 兼容** | 需要新增 `concatChunks(tmpdir) → finalFile` 步骤再走 `.part → copy promote`；正式文件 SHA 不变 | STORE 不变（仍单文件） | STORE 不变 |
| **格式适配** | JSON / Delimited / FixedWidth 天然适合；Excel **不适用**（单 workbook） | JSON 数组要在拼接时补 `]`；Delimited / FixedWidth 直接 append；Excel **不可行** | 全部适用，但等于不解决 |
| **实现量（LOC 估）** | ~600（含 STORE concat + 4 个 format 改造 + 单测） | ~900（4 个 format truncate 各自实现 + JSON 数组语法补丁 + 单测；Excel 仍要兜底回 C） | ~80（仅 cursor 缓存） |
| **失败模式** | chunk 文件中途崩溃 → 删该 chunk → cursor 退一格；fsync 时机要管 | offset 错位即整段污染，难以发现；Excel 只能放弃 | 大任务每次重跑分钟级延迟，**未解决 ADR-038 立项问题** |
| **是否解决 P3 痛点** | ✅ 完整 | ⚠️ 仅文本三种格式 | ❌ |

### 2.2 每方案 detail

#### A. 分片临时文件 + STORE 拼接

**实现量**：~600 LOC，分布：

- `AbstractExportFormat.generatePaged`：每 page 写到独立 `OutputStream`，page 结束 `fsync + close + 通知位点 advance`；
- `format/*ExportFormat`：JSON 需把"数组分片"语义记好（每个 chunk 自己合法或自己是片段——见下文取舍）；Delimited / FixedWidth 每个 chunk 独立带 header 与否需明确策略；
- `StoreStep`：新增 `concatChunks(pipelineInstanceId, format) → tempFinalFile`，按 chunk 序号顺序流式 concat（CSV 拼接时只保留第一个 chunk 的 header；JSON 包裹 `[ ... ]`）；
- `pipeline_progress.position_marker`：扩展为 `"chunk-12:cursor-XYZ"` 形式（或新增 `chunk_index` 列，二选一，落地 PR 决定）。

**4 format 适配难度**：

- **JSON**：每 chunk 写"裸数组元素列表"，不带 `[]`；STORE concat 时统一加 `[`、chunk 间 `,`、最后 `]`。难度低。
- **Delimited**：chunk-0 带 header，chunk-N(N≥1) 不带；concat 直接字节拼。难度低。
- **FixedWidth**：无 header 概念，纯行流，concat 字节拼。难度低。
- **Excel**：单 workbook 必须 `workbook.write()` 整体输出，**无法分片**。该 format **回退到 C 全 STORE 重做**，并在 P3 实施 PR 里显式声明。难度：放弃，文档化兜底。

**失败模式**：

- chunk-N 写到一半崩溃 → 重启发现 chunk-N 文件存在但位点未推进到 N → 删 chunk-N → 从 chunk-N 对应 cursor 重写。
- tmpdir 整目录被外部清掉 → 等同 C，从头。
- 续跑前必须 fsync(chunk file) → fsync(parent dir) → 才更新位点；任一步未完成视为该 chunk 不存在。

**STORE 阶段改动**：在 `.part` 上传前插入 `concatChunks` 输出到单一本地 final file，后续 SHA-256 与 PUT/promote 流程不变。STORE 完成后清 tmpdir 整目录。

#### B. 单文件 APPEND 模式

**实现量**：~900 LOC。

- format 改造比 A 复杂：JSON 需要在每次 append page 前回退一个字节（覆盖上一次的 `]` 占位）或始终不写 `]`、由 STORE 补尾；
- 续跑时 `RandomAccessFile.setLength(byteOffset)` 后续写——byte offset 必须与"上一次成功 fsync 的位置"完全一致，否则要么多字节(坏 JSON)要么少字节(吞数据)；
- Excel **完全不可行**：POI 不支持 append open，workbook 是结构化二进制；
- byte offset 维护要随每 chunk fsync 推进，跨 OS（mac / Linux）的 fsync 语义要测；
- 单点失败：一次 offset 推进 bug → 整文件污染 → 续跑无法检测（与 A 的"chunk 文件存在/缺失"二元状态相比信噪比差很多）。

#### C. 全 STORE 重做（当前 P3 砍掉后的行为）

**实现量**：~80 LOC，只优化 plugin cursor 查询缓存（位点不写文件，崩了 cursor=null 重头分页）。

- 不解决 ADR-038 立项时记录的"百万行级 cursor 重头分页是 SLA 风险"；
- 适合作为 Excel **兜底**，但不能作为 P3 的总策略。

---

## 3. 推荐：A 方案（分片 + STORE 拼接）

理由：

1. **正交于格式细节**：分片粒度 = page，文件状态二元（存在/不存在），不依赖 byte 级 offset 准确性；
2. **Excel 显式回退**：Excel 路径降级到 C 是合理的——Excel 通常用于小报表（< 10 万行），重跑成本可接受；ADR-038 SLA 风险来自 JSON / CSV 百万行级，A 覆盖了主力场景；
3. **STORE 不破坏 `.part → copy promote` 不变量**：concat 输出本地 final file，后续上传链路零改动，与 R2-P1-6 的 deterministic `.part` 命名兼容；
4. **位点语义清晰**：`chunk-12:cursor-XYZ` 自解释，运维 / 排障比"byte offset 12345678"友好得多；
5. **失败半径小**：单 chunk 重写最多丢一页（chunkSize 默认 500 行），B 方案一次 offset bug 可能整文件污染。

B 否决：Excel 死路 + JSON 语法补丁逻辑分散在四处。
C 否决：未解决用户痛点，仅作为 Excel 兜底嵌入 A。

---

## 4. A 方案落地草案

### 4.1 数据模型

复用 P1 的 `pipeline_progress` 表，`position_marker` 列字符串语义扩展：

| 阶段 | 旧含义 (P2) | 新含义 (P3) |
| --- | --- | --- |
| LOAD | 行号 `"row-123456"` | 不变 |
| GENERATE | `"cursor-XYZ"`（之前未实现） | `"chunk-12:cursor-XYZ"`（chunk 序号 + 下一页起始 cursor） |

不新增列，避免 migration。解析在 `ProcessingPosition.parseForGenerate` 集中处理。

### 4.2 临时文件布局

```
${java.io.tmpdir}/file-batch-export/${pipelineInstanceId}/
  ├── chunk-0.json
  ├── chunk-1.json
  ├── chunk-2.json
  └── ...
```

- 子目录 per pipelineInstanceId 隔离，重跑同实例不冲突；
- 文件扩展名跟随 format；
- STORE 完成后整目录 rm -rf。

### 4.3 GenerateStep 续跑流程

```
1. 启动 generate：
   a. 读 pipeline_progress.position_marker
   b. 若为空 → 全新任务，从 chunk-0 / cursor=null 开始
   c. 若为 "chunk-N:cursor-XYZ"：
      - 扫 tmpdir 已存在 chunk-K（K=0..M）
      - 删 K > N 的所有 chunk（可能 partial）
      - 从 cursor-XYZ 开始写 chunk-(N+1)
2. 每 page 处理：
   a. 写 chunk-(N+1).<ext>（独立 OutputStream）
   b. flush + fsync(file) + fsync(parent dir)
   c. UPDATE pipeline_progress SET position_marker = "chunk-(N+1):cursor-NEXT"
      （事务边界仍是单条 UPDATE，文件 fsync 已在前）
3. 游标耗尽：
   a. markCompleted(stage=GENERATE)
   b. 进入 STORE
```

崩溃恢复关键不变量：**chunk 文件 fsync 完成 ≤ 位点 UPDATE 提交**。
位点提交后崩溃 → 续跑看到 chunk 存在 + 位点指向它 → 跳过；
位点提交前崩溃 → 续跑看到 chunk 存在但位点未推进 → 删 chunk 重写。

### 4.4 STORE 拼接

`StoreStep` 在生成 `.part` 之前新增本地 concat：

```
File finalLocal = concatChunks(pipelineInstanceId, format);
// 后续走原有 .part 上传 + SHA-256 + copy promote
```

`concatChunks` 按 format 分支：

- **JSON**：写 `[`，按 chunk 序号 stream 拼接，chunk 间 `,`，末尾 `]`；
- **Delimited**：chunk-0 全文（含 header）→ chunk-N(N≥1) 跳过 header 字节流拼；
- **FixedWidth**：纯字节流顺序拼；
- **Excel**：concat 路径不走，GenerateStep 写单文件（A 不适用 → 退化为当前实现 + cursor 缓存，等同 C）。

### 4.5 关键边界

- **chunk 大小** = workerConfiguration.chunkSize()（默认 500），与 LOAD 一致；
- **fsync** 在 chunk close 时强制；写 chunk 失败重写代价 = 1 page；
- **STORE 崩溃**：concat 阶段失败仍可重做（chunk 文件还在），STORE 完成 promote 后再清 tmpdir；
- **冷热数据隔离**：tmpdir 异常清理走现有 `pipeline_progress` archive 镜像扫描 + 孤儿文件 GC（runbook 已有）。

---

## 5. 不做项（明确划出范围）

1. **Excel append**：POI 限制，不投入；Excel 走 C 兜底并在用户文档标注"Excel 导出不支持续跑，崩溃将重做"。
2. **STORE 跨 worker 协调**：当前 STORE 仍由单 worker 完成，分片只是本地 tmpdir 优化，不引入多 worker 合并 chunk。
3. **chunk 文件加密 / 校验和**：单 chunk 损坏靠 fsync + 删除策略覆盖，不引入 per-chunk SHA。
4. **chunkSize 动态调整**：保持现有 config 取值，spike 范围内不调。
5. **JSON streaming 优化**（如 ndjson）：维持现 JSON 数组语义，不变更对外契约。

---

## 6. 下一步：P3 拆 PR

| PR | 范围 | 依赖 |
| --- | --- | --- |
| **PR1** | 模型扩展（`position_marker` 解析）+ JSON / Delimited 分片 + STORE concat 主路径 + 单测 + e2e 续跑 fixture | P1 / P2 已合 |
| **PR2** | FixedWidth 分片 + concat | PR1 |
| **PR3** | Excel 全 STORE 兜底文档化 + cursor 缓存优化 + runbook 更新（"Excel 不支持续跑") | PR1 |

每 PR 单独走 strict-verify（local + dispatch），不进 main push 门禁（沿用项目约定）。

---

## 7. 决策记录

- **作者**：Round-3 #12 spike
- **结论**：推荐 A 方案；B 因 Excel + offset 脆弱性否决；C 仅作 Excel 兜底
- **影响范围**：`batch-worker-export` 内部 GENERATE / STORE 边界，不影响 plugin SDK、不影响 console、不影响 STORE 对外 `.part → copy` 契约
- **回滚策略**：A 方案的 chunk 目录可空可存在，feature flag `export.recovery.chunked=false` 时退化为单文件（即 P3 砍掉前行为），无 schema 风险
