# 文件 IO 实现盘点与异步 IO 必要性评估（2026-05-03）

> 范围：`batch-worker-{import,export,dispatch,process,core}` + `batch-common` 文件 IO。
> 核心结论：**不需要重构成 NIO 2 / AsynchronousFileChannel**；现状为"同步 + 流式 + Kafka listener `concurrency=4` × `TaskExecutionPool=16` 平台线程"，已是合适的工业级方案；少数地方有可摘的 quick win（一次性全字节加解密路径 + DataHandler 改 InputStream）。**虚拟线程在本系统收益边缘，不建议主动切换**（详见 §4 / §5）。

---

## 1. IO 场景盘点

| Worker | 阶段/类 | 数据来源 | 数据量级 | 当前 IO 实现 | 是否流式 |
|---|---|---|---|---|---|
| IMPORT | `ReceiveStep` | rawPayload (JSON 串内 content / contentBase64) | ≤ `max-payload-size-mb=100MB` 且 ≤ heap×0.2，硬拒 | `String.length()` 校验，无文件 IO | n/a |
| IMPORT | `PreprocessStep` | byte[]（解密后） + `BatchObjectCryptoService.decrypt`（全字节读取） | 同上；解码后 ≥16MiB spool 到 `/tmp/batch-preprocess-*.raw` | `Files.write`(全字节) → `Files.newInputStream` + `InputStreamReader(decoder)` | **是**（spool 路径） / 否（≤16 MiB 直走 byte[]） |
| IMPORT | `ImportPreprocessPipeline` | UNZIP/GUNZIP/AES-GCM/CHARSET_TRANSCODE | 解压后受 `1 GiB` + `50× ratio` 双闸限制 | `ByteArrayInputStream`/`ZipInputStream`/`GZIPInputStream` + `boundedReadAll(BAOS)` | 否（按 cap 读全字节） |
| IMPORT | `ParseStep` + `format/*` | spool 文件 / payloadText | 行级流式 | `BufferedReader` + Univocity CSV / Jackson `JsonParser` 流式 token / **POI XSSF SAX (`XSSFSheetXMLHandler`)** | **是** |
| IMPORT | `ParseStep#applyPartitionFilter` | parsed NDJSON staging 文件 | 行级 | `BufferedReader/Writer` + `Files.move(REPLACE_EXISTING)` | 是 |
| IMPORT | `ValidateStep` / `LoadStep` | parsed/validated NDJSON | 行级 chunk | `BufferedReader` + `objectMapper.readValue(line)` 按 `chunk_size`(默认 500) 累批调插件 | 是 |
| IMPORT | `ImportErrorOutputStorage` | bad records 列表 | 通常 KB-MB | `StringBuilder` 拼 NDJSON → `byte[]` → `MinioClient.putObject` 整块 | 否（小） |
| EXPORT | `GenerateStep` + `format/*` | DB 分页(`pageSize=1000`) | 上限 `effectiveMaxExportRows=500_000` 行硬闸 | `Files.newBufferedWriter` → 按 `chunkSize=500` `writer.flush()` ；Excel 用 **SXSSFWorkbook(rowAccessWindow=100)** + 显式 `dispose()` | **是** |
| EXPORT | `StoreStep` + `MinioExportStorage` | 本地生成文件 | 100MB-GB | `Files.newInputStream` 流 → `MinioClient.putObject(stream, size, partSize)`；SHA-256 用 8KB buffer 流式 digest | **是** |
| EXPORT | `BatchObjectCryptoService.encrypt(Path,Path)` | 本地生成文件 | 同上 | `Files.newInputStream/Output` + `CipherOutputStream` + `transferTo` | **是** |
| DISPATCH | `DispatchFileContentResolver.openInputStream` | LOCAL 路径 / MinIO `getObject` | 整文件 | `Files.newInputStream` 或 `MinioClient.getObject` → 可选 `CipherInputStream` 解密 | **是** |
| DISPATCH | `SftpDispatchChannelAdapter` | InputStream | 整文件 | JSch `ChannelSftp.put(in, remotePath, OVERWRITE)` 流式上传 | **是** |
| DISPATCH | `RemoteFilesystemDispatchSupport.dispatchNas` | InputStream | 整文件 | `Files.copy(in, target, REPLACE_EXISTING)` | **是** |
| DISPATCH | `RemoteFilesystemDispatchSupport.dispatchOss` | InputStream | 整文件 | `MinioClient.putObject(stream, -1, MINIO_PART_SIZE=10MB)` 多 part 上传 | **是** |
| DISPATCH | `SmtpEmailDispatchChannelAdapter` | InputStream → temp file | ≤ `MAX_ATTACHMENT_BYTES=25MB` | `Files.copy` 落地 + `FileDataSource`（**整文件 jakarta.mail 处理**） | 半流式 |
| DISPATCH | `HttpDispatchChannelAdapter` | JSON 元信息（不带文件体） | < 1MB | OkHttp `RequestBody.create(jsonString)` | n/a |
| DISPATCH | `LocalDispatchChannelAdapter`/`LocalOutboxDispatchSupport` | JSON envelope | < 1MB | `Files.writeString` 整块 | n/a |
| PROCESS | 全部 stages | 仅 DB | n/a | 无文件 IO | n/a |

**线程模型**：`spring.kafka.listener.concurrency=4` listener × 每条任务 `TaskExecutionPool` (`Executors.newFixedThreadPool(16, daemon=false)`) submit + `future.get(timeoutSeconds)`，listener 不阻塞。`max-concurrent-tasks=6` Semaphore 背压。所有 IO 都跑在 `worker-task-exec-N` 平台线程，**未启用虚拟线程**。

---

## 2. 评估矩阵

| 场景 | 合理性 | OOM 风险 | 线程模型健康度 |
|---|---|---|---|
| IMPORT Receive 大小预检 | ✅ heap-ratio 联动 + 硬上限 100MB | 已防 | 同步阻塞 0 时间 |
| IMPORT Preprocess byte[] 路径 (≤16 MiB) | ✅ 小文件全内存最快 | 已 cap | 阻塞 IO 跑 task-exec-pool；OK |
| IMPORT Preprocess spool 路径 (>16 MiB) | ✅ 流式 decode 避 String UTF-16 1.5-2× 放大 | 已防 | 同上 |
| IMPORT Pipeline UNZIP/GUNZIP `boundedReadAll` (BAOS) | ⚠️ 解压后仍全部 in heap，最高 1 GiB | 极端配置 cap=1GiB 单任务 = 6 并发 6 GiB；建议默认下调或 spool | 同上 |
| IMPORT Pipeline AES_GCM_DECRYPT / CHARSET_TRANSCODE | ⚠️ 同上 byte[] 全内存 | 同上链式风险 | 同上 |
| IMPORT Parse Excel (POI XSSF SAX) | ✅ 流式事件回调，500MB xlsx ~20MB heap | 已防 | OK |
| IMPORT Parse CSV (Univocity) | ✅ 流式 reader + `setMaxCharsPerColumn(-1)` 由上游兜底 | 已防 | OK |
| IMPORT Parse JSON (Jackson `JsonParser`) | ✅ 流式 token | 已防 | OK |
| IMPORT Validate/Load 行级 chunk (chunk=500) | ✅ NDJSON 行级 + 分块 flush 到 plugin | 已防 | OK |
| EXPORT Generate (Delimited / FixedWidth / Excel SXSSF) | ✅ 写端流式 + 读端 DB 分页 1000 行 | 已防（500k 行硬闸） | OK |
| EXPORT Store 上传 MinIO + SHA-256 | ✅ 流式 PUT + 8KB digest buffer | 无 | OK |
| EXPORT Store 加密 `encrypt(Path,Path)` | ✅ stream-to-stream | 无 | OK |
| DISPATCH SFTP `ChannelSftp.put(in,...)` | ✅ JSch 流式 | 无 | **D-1 已修**（disconnect 异步超时，避免线程长期停滞）|
| DISPATCH NAS `Files.copy` | ✅ JDK NIO copy 内部 8KB transfer | 无 | OK |
| DISPATCH OSS multi-part 10MB | ✅ MinIO SDK 自动分片 | 无 | OK |
| DISPATCH SMTP 落地 temp file → jakarta.mail | ⚠️ `FileDataSource` 整文件（cap 25MB 已防 OOM 但是 IO 翻倍） | 已 cap | OK |
| DISPATCH HTTP（OkHttp sync `execute()`） | ✅ 元信息 JSON，无文件体 | 无 | OK |
| IMPORT ErrorOutputStorage (StringBuilder + putObject byte[]) | ⚠️ bad records 数据量小，但 `StringBuilder` 全拼后再 `getBytes` 内存翻倍 | 低 | OK |
| MinioExportStorage `writeObject(byte[])` 10MB cap | ✅ 显式 cap + Path 流式版本作为大文件入口 | 已防 | OK |

---

## 3. 决断清单

### ✅ 保持现状（合理，**禁止瞎改**）

- **IMPORT Excel SAX**（`ExcelFormatParser.java:77-99`）— POI `XSSFReader` + `ReadOnlySharedStringsTable` + `XSSFSheetXMLHandler` 是业界处理大 xlsx 的标准方案。再优化只能换 SDK 不会更快。
- **IMPORT CSV Univocity**（`DelimitedFormatParser.java:58-114`）— 业界最快的 Java CSV 解析器之一，已开 `BufferedReader` 流式。
- **IMPORT JSON Jackson Streaming**（`JsonFormatParser.java:33-72`）— `JsonParser` token 流，单行 readTree。
- **IMPORT spool 路径 `BufferedReader` 流式 decode**（`FormatParseRequest.java:50-61`）— 配 `CodingErrorAction.REPORT` 严格解码 + 大文件按行流。
- **EXPORT SXSSFWorkbook(100)**（`ExcelExportFormat.java:38-69`）— window=100 滚动，已显式 `dispose()` 清 `/tmp` sheet-backing files (L-2 已修)。
- **EXPORT `BufferedWriter` + `chunkSize` 周期 flush**（`DelimitedExportFormat.java:43-65`、`FixedWidthExportFormat.java:42-68`）— 业界标准。
- **EXPORT MinIO 流上传**（`MinioExportStorage.java:93-115`）— 用 `Files.newInputStream` + `putObject(stream, knownSize, -1)`，知道大小所以不需 multipart，已是最优。
- **EXPORT SHA-256 8KB digest**（`StoreStep.java:235-252`、`MinioExportStorage.java:166-188`）— 不需要改。
- **DISPATCH SFTP 流上传**（`SftpDispatchChannelAdapter.java:207-209`）— JSch 已是 putfile 内部 32KB block；改 SSHJ/Mina-sshd 不会带来量级提升。
- **DISPATCH NAS `Files.copy`**（`RemoteFilesystemDispatchSupport.java:78-80`）— JDK 内部 8KB 缓冲流复制 + sandbox/symlink 防护已就位。
- **DISPATCH OSS multipart**（`RemoteFilesystemDispatchSupport.java:153-159`）— `MINIO_PART_SIZE=10MB` 让 SDK 自动分片，标准 S3 玩法。
- **DISPATCH HTTP OkHttp 同步**（`HttpDispatchChannelAdapter.java:107`）— 元信息 JSON 不带文件体，sync 完全合理。
- **`BatchObjectCryptoService.encrypt(Path,Path)` 流式**（`BatchObjectCryptoService.java:129-140`）— `transferTo` + `CipherOutputStream`，完美。
- **`TaskExecutionPool.newFixedThreadPool(16)`**（`TaskExecutionPool.java:39-49`）— 平台线程 + `future.get(timeout)` + `cancel(true)` 是 P0-1 修复的成果，**别改**。

### ⚠️ 小改优化（quick win，按优先级排序）

1. **`ImportPreprocessPipeline.boundedReadAll`**（`ImportPreprocessPipeline.java:235-264`）— 默认 `MAX_DECOMPRESS_BYTES=1 GiB` × 6 并发 task = 6 GiB 堆压。建议：①默认下调到 256 MiB；②超过 SPOOL 阈值（如 32 MiB）时改 spool 到 `Files.createTempFile` 而不是 BAOS（与 `PreprocessStep.spoolLargePayload` 路径打通），让 PARSE 阶段直接消费 spool 文件。代价低收益高。
2. **`PreprocessStep.resolveRawBytes` + `BatchObjectCryptoService.decrypt(byte[])`**（`PreprocessStep.java:109-115`、`BatchObjectCryptoService.java:81-90`）— 当前 ①把 base64/raw 全 decode 到 `byte[]`；②`cryptoService.decrypt(rawBytes)` 又走 `ByteArrayInputStream → readAllBytes`。100 MB 输入 = 200-300 MB 中间峰值。建议：当 size > spool 阈值时直接走 `Files.createTempFile` + `decryptIfNeeded(InputStream) → transferTo(temp)`；不到阈值才走 byte[]。
3. **`ImportPreprocessPipeline.charsetTranscode`**（`ImportPreprocessPipeline.java:406-431`）— `new String(input, fromCs)` + `text.getBytes(toCs)` 一行 2× 放大，cap 兜底但内存峰值难看。GBK→UTF-8 大文件场景可改 `InputStreamReader(in, fromCs) → OutputStreamWriter(out, toCs) + transferTo`。优先级低（charset_transcode 实际命中场景少）。
4. **`SmtpEmailDispatchChannelAdapter.buildAttachment`**（`SmtpEmailDispatchChannelAdapter.java:203-221`）— `Files.copy` 落 temp file 后 `FileDataSource` 又会被 `jakarta.mail` 整块读到 SMTP socket。25MB cap 防了 OOM 但是 IO 翻 1 倍。可换成 `ByteArrayDataSource(InputStream, mimeType)` 直接桥接 `DispatchFileContentResolver.openInputStream`。优先级中（SMTP 不是热点）。
5. **`ImportErrorOutputStorage.writeErrorOutput`**（`ImportErrorOutputStorage.java:45-58`）— `StringBuilder` 全拼 → `getBytes` → `ByteArrayInputStream`，1 万 bad records 时双倍内存。建议改 `Files.createTempFile` + `BufferedWriter` 流写后 `putObject(Path, knownSize)`（参考 `MinioExportStorage.writeObject(Path)`），与 export 链路对齐。优先级低。

### 🔄 建议改虚拟线程

**目前一处都不建议改**。理由见 §4。

### 🚀 真的需要 AsyncIO（NIO 2 `AsynchronousFileChannel` / Reactive）

**没有**。本系统 IO 模型是"少量长任务"（几个到几十个并发，每任务跑几分钟到几十分钟），不是"海量短连接"，AsyncIO 的 epoll 多路复用收益不存在，反而引入回调地狱 + cancellation 困难 + 调试灾难。MinIO SDK / JSch / jakarta.mail / OkHttp 也都不支持非阻塞 IO，强行套异步只能"假异步真同步"。

---

## 4. 虚拟线程在本系统的适用面

**结论：不切换 / 不主动 enable `spring.threads.virtual.enabled=true`。**

虚拟线程的杀手场景是 **"线程数 ≫ CPU 核数 × N，但每个线程大部分时间在 syscall/socket 阻塞"**（典型：每请求 1 线程 web server，10k 并发）。本系统不符合：

| 维度 | 本系统现状 | VT 受益门槛 | 判断 |
|---|---|---|---|
| 单实例并发任务数 | `max-concurrent-tasks=6` Semaphore + `kafka concurrency=4` listener + `TaskExecutionPool=16` | ≥ 数百并发阻塞线程 | **不达标**，平台线程绰绰有余 |
| IO 占比 | 高（文件 + DB + Kafka + HTTP），但任务粒度大（分钟级） | 高 IO 占比 + 短任务 | 部分匹配 |
| 第三方 SDK 兼容 | JSch/POI/MinIO/OkHttp/Jackson 均含 `synchronized` 块或 native | VT pin 触发会回退到 carrier 线程 | **风险点**——SXSSFWorkbook、JSch、jakarta.mail 都是高度 `synchronized`，pinning 后体验等同平台线程但调试更难 |
| ThreadLocal 占用 | Slf4j MDC + Spring SecurityContext + Hikari Connection 都重 | VT 大量 TL 反而拖慢 | 中性偏负 |
| 调试/profile | 现有 thread dump 工具链熟悉 `worker-task-exec-N` | VT 在 jstack/profiler 里需要新的认知 | 短期负 |

**唯一值得评估**的位置：`SftpDispatchChannelAdapter.DISCONNECT_EXECUTOR`（`SftpDispatchChannelAdapter.java:233-245`）—— scheduledThreadPool(2) 给 disconnect 兜底，单进程几百次 dispatch 同时撞上 SFTP 半关闭可能塞满 2 线程；改 `Executors.newVirtualThreadPerTaskExecutor()` 简单且这里没有 `synchronized` pin 风险。但实际触发率极低，**收益不足以单独 PR**，可作为某次清理的搭车改动。

---

## 5. 不建议改异步 IO 的反例（避免 over-engineering）

1. **不要把 `BufferedReader.readLine()` 改 `AsynchronousFileChannel.read(ByteBuffer, position, attachment, handler)`**。本地 SSD 的 `read()` 系统调用 < 100µs，回调式状态机带来的代码复杂度 100% 不值；JDK 的 `AsynchronousFileChannel` 在 Linux 实际上还是 worker thread pool 模拟，不是真正 io_uring。
2. **不要把 export `BufferedWriter.write(line)` 换成 `WritableByteChannel`**。`BufferedWriter` 8KB 默认缓冲就是最优 page-aligned；`WritableByteChannel.write(ByteBuffer)` 字符串编码、缓冲、刷新都要自己管，容易写出更慢的版本。
3. **不要为 SFTP/MinIO 上传引入 reactive (`reactor-netty`)**。MinIO Java SDK 没有非阻塞 API（`putObject` 内部就是 OkHttp 同步），JSch 更没有，硬包 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 等于把同步 IO 套了个壳，背压能力是假的。
4. **不要把 `TaskExecutionPool` 换成 `Executors.newCachedThreadPool` 期待"自动伸缩"**。`max-concurrent-tasks=6` 上游已限流，cached pool 在异常突刺下会暴增线程；`newFixedThreadPool(16)` + Semaphore 是更可控的双层保护。
5. **不要给 PROCESS worker（纯 DB）"对齐"加任何 IO 抽象**。它没文件 IO 就是干净的，强行统一会引入死代码。

---

## 总结

- IO 现状已是工业级流式实现：CSV Univocity / Excel POI SAX / JSON Jackson 流式 / SXSSF 输出 / MinIO multi-part / JSch ChannelSftp，OOM 防线（payload cap、heap-ratio、decompress ratio、max rows、max columns）齐备。
- 真正能摘的 quick win 集中在 **IMPORT preprocess 那条 byte[] → byte[] → byte[] 链路**（解密、解压、charset_transcode）超过 spool 阈值时改 stream-to-temp-file，把单任务峰值从 GB 级降到 MB 级。
- 不需要 AsyncIO；**不主动切虚拟线程**，本系统的并发模型与 VT 适用面错位。
- 切勿把"现代化 = 异步化"作为重构理由——业界一线（Apache Beam、Spring Batch、Flink TaskManager）的文件 IO 主路径全是同步流式 + 线程池，结论一致。
