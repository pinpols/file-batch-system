# MinIO Java SDK → AWS SDK for Java v2 迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐 Task 执行。步骤用 `- [ ]` checkbox。

**Goal:** 把全仓对象存储客户端从 `io.minio:minio:8.6.0` 全量换成 `software.amazon.awssdk:s3` v2(同步 + apache-client);MinIO **服务器不动**,只换客户端 SDK。

**Architecture:** S3ObjectStore 是唯一 SDK 收敛点(9 方法换 AWS SDK v2);client 构造从 MinioClient 单 bean 变为 S3Client + S3Presigner 双 bean(v2 presign 独立);6 处漏网生产代码 1 处收敛 BatchObjectStore、5 处换 SDK;Minio* 类改名 S3*;配置 key 不动。

**Tech Stack:** Java 21、Spring Boot AutoConfiguration、AWS SDK for Java v2(`s3` + `apache-client` + `s3` presigner)、JUnit5 + AssertJ、Testcontainers `MinIOContainer`(server 仍是 MinIO)。

**设计依据:** 本对话已批准的 3 决策(收敛边界 / 改名 / 配置不动)。

---

## 关键 API 映射(MinIO SDK → AWS SDK v2,全 Task 共用)

| 操作 | MinIO SDK | AWS SDK v2 |
|---|---|---|
| 建 client | `MinioClient.builder().endpoint().credentials().httpClient(okhttp).region()` | `S3Client.builder().endpointOverride(URI).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ak,sk))).region(Region.of(r)).forcePathStyle(true).httpClientBuilder(ApacheHttpClient.builder().connectionTimeout().socketTimeout())` |
| presign | `minioClient.getPresignedObjectUrl(...)` | **独立** `S3Presigner.builder()....build()` → `presigner.presignGetObject(req).url().toString()` |
| put | `putObject(PutObjectArgs...stream(in,size,-1))` | `s3.putObject(PutObjectRequest.builder().bucket().key().contentType().build(), RequestBody.fromInputStream(in, size))` |
| get | `getObject(GetObjectArgs)` → InputStream | `s3.getObject(GetObjectRequest.builder().bucket().key().build())` → `ResponseInputStream<GetObjectResponse>`(是 InputStream) |
| getFrom(offset) | `GetObjectArgs...offset(off)` | `GetObjectRequest.builder()...range("bytes=" + off + "-").build()` |
| statSize | `statObject(...).size()` | `s3.headObject(HeadObjectRequest.builder().bucket().key().build()).contentLength()` |
| exists | statObject + catch ErrorResponseException code | `headObject` + `catch NoSuchKeyException → false` / `catch S3Exception(404) → false` |
| list | `listObjects(...recursive(true))` | `s3.listObjectsV2(ListObjectsV2Request.builder().bucket().prefix().startAfter().maxKeys().build())` → `resp.contents()` 列 `S3Object`(key/size/lastModified/eTag) |
| copy | `copyObject(...)` | `s3.copyObject(CopyObjectRequest.builder().sourceBucket().sourceKey().destinationBucket().destinationKey().build())` |
| delete | `removeObject(...)` | `s3.deleteObject(DeleteObjectRequest.builder().bucket().key().build())` |
| bucketExists | `bucketExists(BucketExistsArgs)` | `headBucket(HeadBucketRequest)` + `catch NoSuchBucketException → false` |
| makeBucket | `makeBucket(MakeBucketArgs)` | `s3.createBucket(CreateBucketRequest.builder().bucket().build())` |
| 异常取 code | `ErrorResponseException.errorResponse().code()` | `S3Exception.awsErrorDetails().errorCode()`;`statusCode()` 取 HTTP 码;`NoSuchKeyException` / `NoSuchBucketException` 是 S3Exception 子类 |

> AWS SDK v2 的 `ResponseInputStream` **是** `InputStream` 子类,直接 return 兼容 `BatchObjectStore.get()` 签名。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| 根 `pom.xml` | awssdk BOM + 移除 minio.version | Modify |
| `batch-common/pom.xml` 等 8 个 | minio → awssdk s3 + apache-client | Modify |
| `S3ObjectStore.java` | 9 方法换 v2;新增 `S3Presigner` 字段 | Modify |
| `MinioAutoConfiguration.java` → `S3AutoConfiguration.java` | 建 `S3Client` + `S3Presigner` bean(替 MinioClient) | Rename+Modify |
| `MinioBucketSupport.java` → `S3BucketSupport.java` | headBucket/createBucket | Rename+Modify |
| `MinioHealthIndicator.java` → `S3HealthIndicator.java` | headBucket 探活 | Rename+Modify |
| `BatchObjectStoreAutoConfiguration.java` | 注入 S3Client+S3Presigner 建 S3ObjectStore | Modify |
| `RemoteFilesystemDispatchSupport.java` | put/stat/remove 收敛到 `BatchObjectStore` | Modify(收敛) |
| 5 处漏网 | MinioClient→S3Client / 改 import | Modify |
| 12 测试文件 | 验证客户端 MinioClient→S3Client | Modify |
| `docs/runbook/object-storage-s3-backends.md` | 「MinIO Java SDK」→「AWS SDK v2」 | Modify |

---

## Task 1: pom — 加 AWS SDK v2 依赖(暂留 minio,保证中途可编译)

**Files:** Modify 根 `pom.xml` + 8 个模块 pom(batch-common / batch-console-api / batch-trigger / batch-orchestrator / batch-worker-import / batch-worker-export / batch-worker-dispatch + 用到的)

- [ ] **Step 1: 根 pom 加 awssdk BOM + 版本属性**

`pom.xml` `<properties>` 加:
```xml
<awssdk.version>2.31.78</awssdk.version>
```
`<dependencyManagement><dependencies>` 顶部加 BOM(import scope):
```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>bom</artifactId>
  <version>${awssdk.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```
**暂不删** `minio.version` 与 minio 的 dependencyManagement(Task 8 再删)。

- [ ] **Step 2: batch-common/pom.xml 加 awssdk(与 minio 并存)**

在现有 `io.minio:minio` 依赖**旁边**加:
```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>s3</artifactId>
</dependency>
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>apache-client</artifactId>
</dependency>
```
> `s3` 含 S3Client + S3Presigner;`apache-client` 是同步 HTTP 实现。版本由 BOM 管,不写 version。

- [ ] **Step 3: 其余 7 个模块 pom 同样加 awssdk s3 + apache-client(与 minio 并存)**

逐个模块在 `io.minio:minio` 处旁加上述两依赖。

- [ ] **Step 4: 编译验证(minio + awssdk 并存可编译)**

Run: `mvn -q -pl batch-common -am clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**
```bash
git add pom.xml batch-*/pom.xml
git commit -m "build(s3): 加 AWS SDK v2 (s3+apache-client) 依赖,暂与 minio 并存"
```

---

## Task 2: S3ObjectStore 换 AWS SDK v2(核心,9 方法 + presigner)

**Files:** Modify `batch-common/src/main/java/io/github/pinpols/batch/common/storage/S3ObjectStore.java`;Test 新建/改 `S3ObjectStoreIntegrationTest`(若已存在则改,继承现有 MinIOContainer IT 模式)

- [ ] **Step 1: 先看现有 S3ObjectStore 测试**

Run: `find batch-common/src/test -name 'S3ObjectStore*'`
读现有测试(若有);无则参照 `batch-common/src/test/.../storage/` 下同类 IT 写。

- [ ] **Step 2: 改 S3ObjectStore — 字段 + 构造**

把 `private final MinioClient minioClient;` 改为:
```java
  private final software.amazon.awssdk.services.s3.S3Client s3Client;
  private final software.amazon.awssdk.services.s3.presigner.S3Presigner presigner;
  private final S3StorageProperties properties;
```
> 实现时正式 import,勿用 FQN(CLAUDE.md 规则#1);此处只示意。

- [ ] **Step 3: 9 方法逐个换(按上方映射表)**

完整成品(实现者照此写,正式 import):
```java
  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    ensureBucket(bucket);
    try {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
          RequestBody.fromInputStream(in, size));
    } catch (Exception ex) { throw mapException("put", bucket, key, ex); }
  }

  @Override
  public void copy(String bucket, String srcKey, String dstKey) {
    try {
      s3Client.copyObject(CopyObjectRequest.builder()
          .sourceBucket(bucket).sourceKey(srcKey).destinationBucket(bucket).destinationKey(dstKey).build());
    } catch (Exception ex) { throw mapException("copy", bucket, srcKey, ex); }
  }

  @Override
  public void delete(String bucket, String key) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception ex) { throw mapException("delete", bucket, key, ex); }
  }

  @Override
  public InputStream get(String bucket, String key) {
    try {
      return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception ex) { throw mapException("get", bucket, key, ex); }
  }

  @Override
  public InputStream getFrom(String bucket, String key, long offset) {
    try {
      return s3Client.getObject(
          GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=" + offset + "-").build());
    } catch (Exception ex) { throw mapException("getFrom", bucket, key, ex); }
  }

  @Override
  public long statSize(String bucket, String key) {
    try {
      return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
    } catch (Exception ex) { throw mapException("statSize", bucket, key, ex); }
  }

  @Override
  public boolean exists(String bucket, String key) {
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException ex) {
      return false;
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) return false;
      throw mapException("exists", bucket, key, ex);
    } catch (Exception ex) { throw mapException("exists", bucket, key, ex); }
  }

  @Override
  public ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys) {
    List<ObjectSummary> summaries = new ArrayList<>();
    try {
      ListObjectsV2Response resp = s3Client.listObjectsV2(
          ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
              .startAfter(afterMarker).maxKeys(maxKeys).build());
      for (S3Object item : resp.contents()) {
        if (summaries.size() >= maxKeys) break;
        summaries.add(new ObjectSummary(item.key(), item.size(), item.lastModified(), item.eTag()));
      }
    } catch (Exception ex) { throw mapException("list", bucket, prefix, ex); }
    String nextMarker = summaries.size() >= maxKeys && !summaries.isEmpty()
        ? summaries.get(summaries.size() - 1).key() : null;
    return new ObjectListing(List.copyOf(summaries), nextMarker);
  }

  @Override
  public String presign(String bucket, String key, Duration ttl) {
    try {
      GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
      GetObjectPresignRequest req = GetObjectPresignRequest.builder()
          .signatureDuration(ttl).getObjectRequest(get).build();
      return presigner.presignGetObject(req).url().toString();
    } catch (Exception ex) { throw mapException("presign", bucket, key, ex); }
  }
```
> 注意:v2 `ListObjectsV2Request.startAfter(null)` 安全;`S3Object.lastModified()` 已是 `Instant`,直接传给 ObjectSummary(原 MinIO 版要 `.toInstant()`)。

- [ ] **Step 4: 改异常映射 mapException(按 S3Exception)**
```java
  private ObjectStoreException mapException(String operation, String bucket, String key, Exception ex) {
    if (ex instanceof S3Exception s3) {
      String code = s3.awsErrorDetails() == null ? "" : s3.awsErrorDetails().errorCode();
      String message = message(operation, bucket, key);
      return switch (code == null ? "" : code) {
        case "NoSuchKey", "NoSuchObject" -> new ObjectNotFoundException(message, ex);
        case "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch" -> new ObjectStoreAccessException(message, ex);
        default -> new ObjectStoreException(message, ex);
      };
    }
    return new ObjectStoreException(message(operation, bucket, key), ex);
  }
```
删除原 `mapErrorResponse` 方法(已并入)。

- [ ] **Step 5: ensureBucket 改调 S3BucketSupport(Task 4 产物)**

`ensureBucket` 内调 `S3BucketSupport.ensureBucket(s3Client, bucket, log, COMPONENT_NAME, properties.isAutoCreateBucket());`
> Task 4 会把 MinioBucketSupport 改名 S3BucketSupport + 改签名收 S3Client。本 Task 先按此调用写,Task 4 补齐。**为可编译,Task 2 与 Task 4 在同一 commit 完成**(见 Step 7)。

- [ ] **Step 6: 跑 S3ObjectStore IT(连 MinIOContainer)**

Run: `mvn -q -pl batch-common test -Dtest='S3ObjectStore*'`(IT 若是 failsafe 走 `failsafe:integration-test`,照模块现状)
Expected: 9 方法行为测试 PASS(put/get/getFrom/statSize/exists/list/copy/delete/presign 各一)

- [ ] **Step 7: Commit(与 Task 3/4 合并提交,见各 Task 末)**

> Task 2/3/4 互相依赖(S3ObjectStore 用 S3BucketSupport + S3Client bean),**三者一起做完再编译 + commit**。

---

## Task 3: S3AutoConfiguration — 建 S3Client + S3Presigner bean

**Files:** Rename `MinioAutoConfiguration.java` → `S3AutoConfiguration.java`;Modify `BatchObjectStoreAutoConfiguration.java`;Modify `META-INF/spring/...AutoConfiguration.imports`(若注册了类名)

- [ ] **Step 1: git mv 改名 + 改类名**
```bash
git mv batch-common/src/main/java/io/github/pinpols/batch/common/config/MinioAutoConfiguration.java \
       batch-common/src/main/java/io/github/pinpols/batch/common/config/S3AutoConfiguration.java
```
类名 `MinioAutoConfiguration` → `S3AutoConfiguration`。

- [ ] **Step 2: 改 bean 工厂方法**
```java
@AutoConfiguration
@ConditionalOnClass(S3Client.class)
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3AutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public S3Client s3Client(S3StorageProperties p) {
    ApacheHttpClient.Builder http = ApacheHttpClient.builder()
        .connectionTimeout(Duration.ofMillis(p.getConnectTimeoutMs()))
        .socketTimeout(Duration.ofMillis(p.getReadTimeoutMs()));
    S3ClientBuilder b = S3Client.builder()
        .endpointOverride(URI.create(p.getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(p.getAccessKey(), p.getSecretKey())))
        .forcePathStyle(true)                 // MinIO/Ceph 必须 path-style
        .httpClientBuilder(http);
    b.region(hasText(p.getRegion()) ? Region.of(p.getRegion()) : Region.US_EAST_1);
    return b.build();
  }

  @Bean
  @ConditionalOnMissingBean
  public S3Presigner s3Presigner(S3StorageProperties p) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(p.getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(p.getAccessKey(), p.getSecretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .region(hasText(p.getRegion()) ? Region.of(p.getRegion()) : Region.US_EAST_1)
        .build();
  }

  @Bean("s3HealthIndicator")
  @ConditionalOnClass(HealthIndicator.class)
  @ConditionalOnProperty(prefix = "management.health.s3", name = "enabled",
      havingValue = "true", matchIfMissing = true)
  @ConditionalOnMissingBean(name = "s3HealthIndicator")
  public HealthIndicator s3HealthIndicator(S3Client s3Client, S3StorageProperties p) {
    return new S3HealthIndicator(s3Client, p);   // Task 5 产物
  }
}
```
> `hasText` 用 `org.springframework.util.StringUtils.hasText`。health 配置 key 由 `management.health.minio` → `management.health.s3`(若有 yml 显式配置同步改;默认 matchIfMissing=true 不影响)。

- [ ] **Step 3: BatchObjectStoreAutoConfiguration 注入改造**

把建 `S3ObjectStore` 的地方从注入 `MinioClient` 改为注入 `S3Client s3Client, S3Presigner presigner`,传给 `new S3ObjectStore(s3Client, presigner, properties)`。

- [ ] **Step 4: AutoConfiguration.imports 改类名**

`batch-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 里 `...MinioAutoConfiguration` → `...S3AutoConfiguration`。

---

## Task 4: S3BucketSupport + S3HealthIndicator 改名换 SDK

**Files:** Rename `MinioBucketSupport.java`→`S3BucketSupport.java`、`MinioHealthIndicator.java`→`S3HealthIndicator.java`

- [ ] **Step 1: git mv 两文件 + 改类名**

- [ ] **Step 2: S3BucketSupport.ensureBucket 换 SDK**
```java
  public static boolean ensureBucket(S3Client s3Client, String bucket, Logger log,
      String componentName, boolean autoCreate) {
    if (s3Client == null || !Texts.hasText(bucket)) return false;
    if (!autoCreate) return true;
    try {
      boolean exists;
      try {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        exists = true;
      } catch (NoSuchBucketException e) { exists = false; }
      if (!exists) s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      LAST_FAILURE_LOG_AT.remove(cacheKey(componentName, bucket));
      return true;
    } catch (Exception ex) {
      // ... 原 5 分钟冷却日志逻辑不变,文案 "minio bucket ensure" → "s3 bucket ensure"
      return false;
    }
  }
```

- [ ] **Step 3: S3HealthIndicator.health() 换 SDK**
```java
  @Override
  public Health health() {
    try {
      s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
      return Health.up().withDetail("bucket", bucket).withDetail("exists", true).build();
    } catch (NoSuchBucketException ex) {
      return Health.up().withDetail("bucket", bucket).withDetail("exists", false).build();
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(S3HealthIndicator.class, "catch:Exception", ex);
      return Health.down(ex).withDetail("bucket", bucket).build();
    }
  }
```

- [ ] **Step 4: 编译 + 跑 common 全部测试(Task 2/3/4 一起)**

Run: `mvn -q -pl batch-common -am clean test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit(Task 2+3+4 合并)**
```bash
git add batch-common/
git commit -m "feat(s3): S3ObjectStore/AutoConfig/BucketSupport/HealthIndicator 换 AWS SDK v2 + 改名 S3*"
```

---

## Task 5: 6 处漏网生产代码

**Files:** Modify 6 文件

- [ ] **Step 1: RemoteFilesystemDispatchSupport 收敛 BatchObjectStore**

读 `batch-worker-dispatch/.../channel/RemoteFilesystemDispatchSupport.java` 的 `dispatchOss` / `dispatchNas` 等方法。把 `minioClient.putObject(...)` / `statObject` / `removeObject` 改为调注入的 `BatchObjectStore.put/statSize/delete`。
> 该类是 static util,需把 `BatchObjectStore` 作参数传入(调用方 OssDispatchChannelAdapter / NasDispatchChannelAdapter 改为注入 `BatchObjectStore` 而非 `MinioClient`)。删 `io.minio.*` import。

- [ ] **Step 2: OssDispatchChannelAdapter / DispatchChannelHealthService — MinioClient → S3Client / BatchObjectStore**

- OssDispatchChannelAdapter:`ObjectProvider<MinioClient>` → `ObjectProvider<BatchObjectStore>`(收敛后 dispatch 走 store)。
- DispatchChannelHealthService:持有 `MinioClient` 仅探活 → 改 `S3Client` headBucket,或注入 `BatchObjectStore`(若只 exists 探活)。读实际用法定。

- [ ] **Step 3: MinioBucketSupport 调用点已在 Task 4 改名,核对 import**

`DefaultDryRunPlanService` 用 `MinioClient.bucketExists` → 改 `S3Client.headBucket` catch NoSuchBucketException;或注入 `S3BucketSupport`。
`DefaultConsoleFileDownloadApplicationService` 仅 `import io.minio.errors.ErrorResponseException`(catch)→ 改 catch `software.amazon.awssdk.services.s3.model.S3Exception`(看 statusCode/errorCode)。

- [ ] **Step 4: 编译相关模块**

Run: `mvn -q -pl batch-worker-dispatch,batch-orchestrator,batch-console-api -am clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**
```bash
git add batch-worker-dispatch/ batch-orchestrator/ batch-console-api/
git commit -m "refactor(s3): 6 处漏网换 SDK(RemoteFilesystemDispatchSupport 收敛 BatchObjectStore)"
```

---

## Task 6: 12 测试文件换验证客户端

**Files:** Modify 12 test 文件(`MinIOContainer` 保留,验证用的 `MinioClient` → `S3Client`)

- [ ] **Step 1: 找全 12 文件**

Run: `grep -rl 'io\.minio\.\|MinioClient' batch-*/src/test/java`

- [ ] **Step 2: 逐个把测试里建/用的 MinioClient 换 S3Client**

`MinIOContainer minio` 提供的 endpoint/credentials 不变;测试里若自建 `MinioClient` 验证上传结果 → 换 `S3Client.builder().endpointOverride(URI.create(minio.getS3URL())).forcePathStyle(true)...build()`。
> `MinIOContainer.getS3URL()` / `getUserName()` / `getPassword()` 取连接参数。

- [ ] **Step 3: 跑全部受影响模块测试**

Run: `mvn -q -pl batch-common,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api -am test`
Expected: BUILD SUCCESS,所有 IT 绿

- [ ] **Step 4: Commit**
```bash
git add batch-*/src/test/
git commit -m "test(s3): 12 测试验证客户端 MinioClient → S3Client(MinIOContainer 不变)"
```

---

## Task 7: 移除 minio 依赖 + 收敛校验 + 文档

**Files:** Modify 9 pom + `docs/runbook/object-storage-s3-backends.md`

- [ ] **Step 1: 全仓确认无 io.minio 残留**

Run: `grep -rl 'io\.minio' batch-*/src/main batch-*/src/test`
Expected: **空**(若有残留先清,Task 2-6 漏的补上)

- [ ] **Step 2: 删 minio 依赖**

根 pom 删 `minio.version` 属性 + minio dependencyManagement;8 个模块 pom 删 `io.minio:minio` 依赖。

- [ ] **Step 3: 文档同步**

`docs/runbook/object-storage-s3-backends.md`:正文「MinIO Java SDK」「io.minio」字样 → 「AWS SDK for Java v2(`software.amazon.awssdk:s3`)」;说明仍是通用 S3 客户端,后端切换语义不变。

- [ ] **Step 4: 全量 clean 构建(规约:push 前必 clean compile)**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS;全模块编译 + 单测 + IT 绿

- [ ] **Step 5: Commit + push + PR**
```bash
git add -A
git commit -m "build(s3): 移除 io.minio 依赖 + runbook 文档同步;迁移完成"
git push -u origin feature/migrate-to-aws-sdk-v2
gh pr create --base main --head feature/migrate-to-aws-sdk-v2 \
  --title "refactor(s3): MinIO Java SDK → AWS SDK for Java v2 全量迁移(server 留 MinIO)" \
  --body "..."
gh pr merge --auto --squash
```

---

## Self-Review notes(已自检)

- **Spec 覆盖**:核心 S3ObjectStore(Task 2)/ client 双 bean(Task 3)/ BucketSupport+Health 改名(Task 4)/ 6 漏网(Task 5)/ 12 测试(Task 6)/ 移依赖+文档(Task 7)。✓
- **占位符**:Task 5/6 因文件多且重复,给的是「读实际用法 + 按映射表改」指令而非逐文件成品——这是机械重复 SDK swap,实现者照映射表逐个改,非遗漏。核心文件(S3ObjectStore/Config/BucketSupport/Health)给了完整成品代码。
- **类型一致**:S3Client/S3Presigner 字段名贯穿 Task 2/3;S3BucketSupport.ensureBucket 签名(S3Client)在 Task 2 Step 5 调用与 Task 4 Step 2 定义一致;ObjectSummary 构造 4 参(key/size/Instant/etag)v2 直接 `item.lastModified()` 已是 Instant。
- **编译连续性**:Task 1 加依赖不删 minio(中途可编译);Task 2/3/4 互依赖 → 合并 commit;Task 7 最后删 minio。无长红期。
- **Server 不变**:全程 MinIOContainer / docker MinIO 不动,仅客户端 SDK 换。
