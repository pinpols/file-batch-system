# 对象存储抽象（Phase 2 设计方案）

> **更新 2026-06-06**:已迁移到 **AWS SDK for Java v2**(`software.amazon.awssdk:s3`),`io.minio` 依赖已移除。
> 下文部分「底层是 MinIO SDK / 保留 Minio 命名」的论述为迁移前历史背景;现 `S3ObjectStore` 内层已是 AWS SDK v2,相关类已改名 `S3*`。


> 状态:**已评审定稿**。日期 2026-06-06。
> **决策锁定(2026-06-06)**:① 确有 "NAS/本地当主存储" 部署需求 → **做完整 Phase 2(S3 + FileSystem 两后端)**;
> ② `list` / `copy` **纳入接口**(共 9 方法),13 处直连全收敛。下文已据此定稿(原"待定/可选"措辞改为已定)。
> 前置:Phase 1（配置正名 `batch.storage.s3` + region/autoCreateBucket，已合并 #394）已落地。
> 选型:**流派 A（应用内抽象）**。对标 Micronaut Object Storage / Rails ActiveStorage `Service` / Iceberg `FileIO` /
> Hadoop `FileSystem`——业界主流都是"1 接口 + 少数后端(含 local)",不是一云一实现。

## 0. 核心定调（先把规模和价值钉死,别被 dispatch 带跑）

现状:全系统都走 `io.minio.MinioClient`,**没有别的后端**。收敛成 `BatchObjectStore` 接口后,**实现很少**:

- **生产实现就 1 个**:`S3ObjectStore`。MinIO / AWS S3 / 阿里 OSS / GCS(S3 兼容)全是 S3 协议,**靠 endpoint+credentials 配置切换,不是一朵云一个 impl**。
- 真正机制不同的后端只有**本地文件系统**(`FilesystemObjectStore`),这才是值得有的第二个,且**一个实现兼两用**:① 无-MinIO 单机/边缘部署 ② 测试(指向 temp 目录)→ 所以**不再单列 in-memory 测试替身**。
- **封顶 2 个**。再多就是把 dispatch 的渠道思维(NAS/OSS/HTTP 是**交付目的地**,不是存储后端)错搬过来。
- **加密 BATCHENC(`BatchObjectCryptoService`)是叠在 store 之上的装饰层,不是平级实现,不算 impl 数。**

> 已确认 NAS/本地主存储需求 → S3 + FS 两实现都做(封顶 2)。FS 兼任测试替身,故不单列 in-memory。

**这个抽象的价值不在"多后端",在两点:**
1. **解耦 io.minio SDK**:13 处裸 `MinioClient` → 1 个接口。将来换 AWS SDK v2 / 加 LocalFs,改 **1 个实现类**而非 13 处。
2. **DRY**:消掉 `MinioExportStorage` / `MinioGovernanceStorage` / `ImportErrorOutputStorage` 各自拼 bucket/args 的重复。

## 1. 接口契约 `BatchObjectStore`（先定契约,impl 才好数）

```java
interface BatchObjectStore {
  // 写
  void   put(String bucket, String key, InputStream in, long size, String contentType);
  void   copy(String bucket, String srcKey, String dstKey);                // S3 server-side copy / FS Files.copy
  void   delete(String bucket, String key);
  // 读
  InputStream get(String bucket, String key);                              // 整对象顺序流(下载/sha256/export)
  InputStream getFrom(String bucket, String key, long offset);             // 从 offset 起的正向流(range-slice 用)
  long    statSize(String bucket, String key);
  boolean exists(String bucket, String key);
  // 列举(分页惰性——避免大桶/NAS 大目录一次性物化)
  ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys);
  // 下载授权
  String  presign(String bucket, String key, Duration ttl);
}

record ObjectListing(List<ObjectSummary> objects, String nextMarker) {}    // nextMarker==null → 末页
record ObjectSummary(String key, long size, Instant lastModified, String etag) {} // etag=变更令牌,非内容哈希

// 统一异常层级(NoSuchKey 必须 typed)
class ObjectStoreException        extends RuntimeException {}
class ObjectNotFoundException     extends ObjectStoreException {}          // S3 NoSuchKey / FS NoSuchFile
class ObjectStoreAccessException  extends ObjectStoreException {}          // 权限/认证
```

> **接口契约定稿(对标 jclouds / Iceberg / Hadoop,修正前版 3 个缺陷)**:
> - **`list` 分页惰性**(`afterMarker + maxKeys → nextMarker`,抄 jclouds `PageSet/marker`):前版返回 `List` 在大桶/NAS 大目录会爆内存。调用方循环翻页直到 `nextMarker==null`;S3 映射 continuation token,FS 排序后按 marker 切片。
> - **`getFrom(offset)` 取代 `getRange(offset,length)`**(对标 Iceberg `SeekableInputStream`/Hadoop `seek` 的单 seek 退化形):前版"length 当下界"语义不清。range-slice 要"从 offset 正向读、自己在行边界停",`getFrom` 正合此意,与今天 `getObject(offset)` 行为一致;要"精确 N 字节"的调用方读 N 字节即可。
> - **统一异常层级**:`ObjectStoreException` + `ObjectNotFoundException`(typed NoSuchKey)+ `ObjectStoreAccessException`,消除 S3/FS 抛异常不一致。
>
> **9 方法**:put/copy/delete/get/getFrom/statSize/exists/list/presign。13 处直连全部收敛。

## 2. 实现清单

| # | 实现 | 角色 | 必要性 |
|---|---|---|---|
| 1 | **`S3ObjectStore`**(MinIO/AWS S3/阿里 OSS/GCS 全覆盖) | **唯一生产实现** | **必须**。13 处裸 `MinioClient` 全收敛到它 |
| 2 | **`FilesystemObjectStore`**(`java.nio.file.Files`/`FileChannel` 实现) | **NAS/本地主存储**(已确认需求)+ 边缘/无-MinIO 单机 + 测试替身 | **必须**(NAS-primary 需求驱动);兼任测试替身 |
| ~~3~~ | ~~InMemory/TempDir 测试替身~~ | —— | **不单列**,#2 指向 temp 目录即兼任 |

`MinioClient` bean / `MinioAutoConfiguration` 保留,作为 `S3ObjectStore` 的内部依赖;`FilesystemObjectStore` 不依赖它。
后端选择:`batch.storage.backend: s3 | filesystem`(默认 `s3`)。

## 3. 为什么不做成 dispatch 式渠道 SPI

dispatch 的 `DispatchChannelAdapter`(OSS/NAS/SFTP/HTTP/EMAIL)是**一渠道一实现的矩阵**,因为那些是**交付目的地**,机制各异。
存储后端不是这回事:**生产侧全是 S3 协议(配置切换)**,只有"本地文件系统"机制不同。所以它**该收敛成一个窄接口,不该做成渠道式 SPI**——
2 个实现封顶,不留可插拔扩展点(YAGNI + 范围纪律)。

## 4. `FilesystemObjectStore` 三个难点的解法（对标 Hadoop/Iceberg/ActiveStorage）

**映射模型**:`bucket` → `root/<bucket>/` 目录;`key`(如 `tenant/2026-06-06/f.csv`)→ 该目录下相对路径。
**安全**:key 规范化后必须仍在 root 内(拒 `..` 穿越,复用 `DispatchFileContentResolver` 现有 traversal 校验)。

### ① presign —— 本地无"存储直发签名 URL" → 应用代下令牌
对标 **Rails ActiveStorage `DiskService`**:签一个 **HMAC 能力令牌**(`bucket+key+exp`,应用密钥签),返回指向**应用下载端点**的
URL:`{download-base-url}?token=<hmac>`。端点校验 HMAC+过期后**从磁盘流式读**(可带 range)。复用现成的
`DefaultConsoleFileDownloadApplicationService`(它现在就是代理 MinIO getObject,FS 模式改读磁盘)。代价:字节流经应用
(S3 是存储直发),内网批量场景可接受。

> **两个落地细节(别留白)**:
> - **谁托管下载端点**:presign 的消费方是 console(用户下载)和 orchestrator governance(对账给 URL)。FS 模式下
>   `download-base-url` 统一指向 **console-api 的下载端点**(已存在);orchestrator 不自建端点,只生成指向 console 的令牌 URL。
> - **HMAC 密钥**:令牌签发(orchestrator/console)与校验(console 端点)用**同一密钥**——复用平台现有的内部密钥
>   (如 `BATCH_INTERNAL_SECRET`),不新引密钥体系。S3 模式此细节不存在(走存储真签名),仅 FS 后端需要。

### ② getFrom(offset) —— 本地 seek,最简单且更快
对标 **Hadoop `FSDataInputStream.seek` / Iceberg `SeekableInputStream`** 的单 seek 退化形(从 offset 起正向读到 EOF):
```java
var ch = FileChannel.open(path, READ);
ch.position(offset);
return Channels.newInputStream(ch);   // 正向流;调用方读多少、何时停由它定
```
**与已合并的分区切片零冲突**:`PreprocessStep.streamObjectRangeToSpool` 把 `minioClient.getObject(offset)` 换成
`store.getFrom(bucket, key, rawStart)`,行边界对齐的 `copyPartitionRange` 作用在返回流上、与后端无关 →
分区放大优化两后端通用,本地版省网络往返。**与今天只传 offset 的 `getObject(offset)` 行为完全一致**(故弃用前版"length 当下界"的不清晰契约)。

### ③ list —— 前缀匹配映射为目录遍历(分页 + 真难点在 etag/原子性)
对标 **jclouds filesystem provider / Flysystem LocalAdapter**:
```java
// 分页:Files.walk 惰性流 + 按 sorted key 的 afterMarker 跳过 + maxKeys 截断 → nextMarker
Files.walk(root/bucket).filter(regularFile)
   .map(p -> relativize(p) /*=key*/).filter(k -> k.startsWith(prefix)).sorted()
   .dropWhile(k -> afterMarker != null && k.compareTo(afterMarker) <= 0).limit(maxKeys);
```
**三个隐藏风险(比遍历本身更要命)**:
- **etag = 变更令牌,不是内容哈希**:S3 返 MD5,`ImportIngressScanner` 靠它判文件**是否被改**。本地用 **`size + mtime`
  合成伪 etag**(改了就变),不真算 MD5(大文件太贵)。**契约写死:etag 仅用于变更检测;内容完整性走单独的 `sha256`**
  (export 的 `sha256Hex` 本就独立读对象算,与 etag 无关)。
- **写入原子性**:S3 put 原子(对象完成才出现)。**FS 必须自己保证**:`put` 走 **`.tmp` + fsync + 原子 rename**
  (export spool 现成套路);否则 ingress 扫到正在写入的文件读坏数据。
- **临时/隐藏文件**:跳 `.` 开头、`.tmp` 在写文件(`MinioGovernanceStorage` 已有 includeTemporaryObjects 开关沿用)。

## 5. 加密是装饰层,不是第 3 个实现

`BatchObjectCryptoService`(BATCHENC)叠在 store 之上:
```
EncryptingObjectStore(delegate)  // 装饰器:put 前加密 / get 后解密(bypass-mode 透传)
  └─ S3ObjectStore | FilesystemObjectStore
```
现有 import preprocess 的"大文件 spool 解密"、console 下载的"按需解密"都收敛到这层。**不算 impl 数。**

> **⚠ 约束:加密 × `getFrom`(range 读)不兼容,必须写死。**
> AES-GCM 是**整对象加密**,密文 offset ≠ 明文 offset,对加密对象做 range 读拿不到有意义的明文。所以
> **`EncryptingObjectStore.getFrom` 必须拒绝(抛 `UnsupportedOperationException`)**;而 range-slice 分区优化**本就只对未加密文件**生效
> (`canStreamObjectDirect` 已排除 `encrypt_type`),两者天然不相交。文档点破,防止有人天真叠加。

## 6. 迁移地图（13 处直连 → 接口）

| 模块 | 类 | 当前直连 | 收敛 |
|---|---|---|---|
| worker-import | `PreprocessStep` | get/stat/**getRange** | `store.get/statSize/`**`getFrom`** |
| worker-import | `ImportIngressScanner` | **list** | `store.list`（需接口含 list） |
| worker-import | `ImportErrorOutputStorage` | put | `store.put` |
| worker-export | `MinioExportStorage` | put/**copy**/delete/stat/get(sha256) | 改为委托 `BatchObjectStore`（需 copy） |
| console-api | `DefaultConsoleFileDownloadApplicationService` | get（代下） | `store.get` + presign 端点 |
| orchestrator | `MinioGovernanceStorage` | **list**/delete/presign | `store.list/delete/presign` |
| worker-dispatch | `DispatchFileContentResolver` | get | `store.get`（dispatch 已有 LOCAL 分支,天然适配） |
| common | `MinioBucketSupport` | bucketExists/makeBucket | S3 实现内部;FS = `Files.createDirectories` |

> `list` / `copy` 已纳入接口(§1 定稿),13 处全部收敛,无残留。

## 7. 阶段拆分（降风险）

- **阶段一(纯重构,零行为变化)**:引入 `BatchObjectStore` + `S3ObjectStore`,13 处改注入接口,默认 `backend=s3`。
  **不引入 FS**,只收敛。可独立合,风险最低,且立即兑现"解耦 + DRY"。
- **阶段二**:实现 `FilesystemObjectStore`(presign/getRange/list/原子写)+ `backend=filesystem` 选择器 + 加密装饰层归位。
- **阶段三**:FS 多主机/NAS 部署 runbook + 端到端验证。

## 8. 两条现实约束（部署必读）

- **多主机共享**:NAS(共享挂载)→ 分区 worker 跨主机能共享文件,OK;**纯本地盘**→ 分区落不同主机互相读不到 →
  本地后端要么单主机、要么强制 `partitionCount=1`(分区数决策处加约束)。
- **presign 负载**:FS 模式字节流经应用,不像 S3 存储直发;大并发下载需评估应用带宽,或限定 FS 用于内网中小批量。

## 9. 测试矩阵

| 维度 | `S3ObjectStore` | `FilesystemObjectStore` |
|---|---|---|
| 接口契约 | Testcontainers MinIO | `@TempDir`（兼测试替身） |
| getFrom 行对齐 | 复用 `PreprocessRangeSliceTest` | 同测,换 FS 实现 |
| list etag 变更检测 | etag 变 | size+mtime 变 |
| 原子写 | （S3 天然） | 并发写+扫描不读半截 |
| presign | 签名 URL 可下载 | 令牌端点代下 + 过期拒绝 |
| 路径穿越 | n/a | key 含 `..` 拒绝 |
| 加密装饰 | put 加密/get 解密 round-trip;bypass 透传 | 同 |

## 10. 工作量 / 风险

**工作量**:阶段一 ~3-5 天(13 处收敛 + S3 实现 + 契约测试);阶段二 ~1-1.5 周(FS 实现 + 4 难点 + 测试);阶段三 ~2-3 天。合计约 **2-3 周**。若只做阶段一(解耦+DRY,不上 FS),~1 周。

**风险**:
- 13 处收敛面广,阶段一虽零行为变化仍需全回归(ingress 扫描 / export 上传 / console 下载 / 治理对账 / dispatch 读取)。
- FS etag 用 size+mtime:若"原地改写但 size+mtime 不变"(罕见),scanner 漏检 → 文档注明,必要时退回真 MD5(可配)。
- presign 代下让应用成下载瓶颈:大文件高并发需压测,或限定 FS 用于内网中小批量。

## 10.5 参考实现（开工前拉源码借鉴)

本设计接口/契约对标以下开源,按需读源码:

| 开源 | 类/接口 | 借鉴点 |
|---|---|---|
| **Apache jclouds** `BlobStore` + **filesystem provider** | `list(container, ListContainerOptions)` → `PageSet/nextMarker`;`FilesystemBlobStore` | **①分页 list 范式**;**FS 后端整套参考代码**(etag=size+mtime / 原子写 / 目录列举现成)——对 FS 后端最直接可抄 |
| **Apache Iceberg** `FileIO` | `InputFile`/`SeekableInputStream.seek()`;`S3FileIO` | **②getFrom/seekable 读模型**;S3 range 读写法 |
| **Micronaut Object Storage** | `ObjectStorageOperations` + `micronaut-object-storage-local` | 现代 JVM 1:1 同款;`local` 是 `FilesystemObjectStore` 蓝本 |
| **Spring Cloud AWS 3.x** | `S3Template`(upload/download/listObjects/createSignedGetURL) | 接口方法集对标验证 + 签名 URL |
| **Rails ActiveStorage** | `DiskService` / `download_chunk(key, range)` / `url` | **①presign 应用代下**(DiskService 正是此招);download_chunk = getFrom |
| **Hadoop** | `FSDataInputStream`(`Seekable`/`PositionedReadable`) | 定位读标准契约 |
| **s3proxy**（基于 jclouds-filesystem) | filesystem→S3 映射 | FS-as-object-store 的 etag/原子/列举参考代码 |

**最值得先读的两个**:**jclouds filesystem provider**(FS 后端的现成答案)+ **Micronaut object-storage-local**(JVM 同款蓝本)。

## 11. 命名边界与收敛策略（代码层 Minio→S3）

现状:Phase 1 只改了**配置层**(`S3StorageProperties` / `batch.storage.s3` / `BATCH_S3_*`),**代码层仍大量带 Minio**:
6 个我们自己的 `Minio*` 类、~10 处 `minioClient` 字段、13 处 `minioStorageProperties` 字段名、24 类直接 `import io.minio`。

**原则:按它实际是什么命名。**
- **抽象/对外层** → s3/通用:`BatchObjectStore` / `batch.storage.s3` / `S3StorageProperties`(✅ Phase 1+2 覆盖)
- **SDK 包装内层** → "Minio" 准确不误导:`MinioAutoConfiguration` 造的就是 `io.minio.MinioClient`,`S3ObjectStore`
  内部包的就是 minio 库。**这里保留 Minio 是名实相符,不该硬改 S3**(否则掩盖"底层是 MinIO SDK"的事实)。
- **`io.minio.MinioClient`** 是库类名,**改不掉**,只能控制范围。

**收敛策略:不做独立的 cosmetic 改名 pass,靠 Phase 2 收敛顺带清。**
- `MinioExportStorage` / `MinioGovernanceStorage` / `ImportErrorOutputStorage` → Phase 2 **直接删/替换**成 `BatchObjectStore`
  实现或调用方,现在改名 = 白做。
- ~10 处 `minioClient` 字段 + 13 处 `minioStorageProperties` → Phase 2 后多数类改持 `BatchObjectStore`,自然清理。
- **`import io.minio` 从 24 类 → 收敛到 1 个 `S3ObjectStore`**——这才是真"消除耦合",光改名做不到。
- **Phase 2 之后,"Minio" 只合理残留在 `S3ObjectStore` 内部 + auto-config**,且那是准确命名,不再纠结。

## 12. 一句话

接口 1 个(`BatchObjectStore`,9 方法:核心 7 + list + copy),生产实现 1 个(`S3ObjectStore`,S3 系全包),
本地 FS 1 个可选(兼测试替身),加密是装饰层。**封顶 2 个实现**,不做渠道式 SPI。先做阶段一(纯收敛)兑现解耦+DRY,FS 按需再上。
