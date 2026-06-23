package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.S3ObjectStore;
import io.github.pinpols.batch.testing.ObjectStoreContainer;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * ADR-sim #382 回归:PREPROCESS 从对象存储拉取大文件(storagePath→MinIO)。
 *
 * <p>覆盖 {@code PreprocessStep.resolveRawBytes} 的对象拉取分支 + 早期 "raw payload is blank" 校验放行 storagePath
 * 情形(回归 #382:之前缺该分支 + 校验会拦截无内联内容的对象路径 import)。用真 MinIO 容器, 投对象 → 触发 PREPROCESS → 断言下载内容流入
 * normalizedPayload。
 */
@Tag("integration")
class PreprocessStepObjectLoadIntegrationTest {

  private static ObjectStoreContainer objectStore;
  private static String bucket;
  private static S3Client client;
  private static S3Presigner presigner;

  @BeforeAll
  static void startMinio() {
    objectStore = new ObjectStoreContainer();
    objectStore.start();
    bucket = objectStore.getDefaultBucket();
    client = objectStore.client();
    presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(objectStore.getEndpoint()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        objectStore.getAccessKey(), objectStore.getSecretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .region(Region.US_EAST_1)
            .build();
    objectStore.ensureBucketExists(bucket);
  }

  @AfterAll
  static void stopMinio() {
    if (objectStore != null) {
      objectStore.stop();
    }
  }

  /** 用本租户登记 {@code storage_path=registeredPath} 的 file_record stub 构造 step(归属校验放行该路径)。 */
  private PreprocessStep newStep(String registeredPath) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(true); // 跳过解密,下载的明文直通 pipeline
    PlatformFileRuntimeRepository runtimeRepo = mock(PlatformFileRuntimeRepository.class);
    when(runtimeRepo.toLong(any())).thenReturn(1L);
    when(runtimeRepo.loadLatestTemplateConfig(any(), any(), any())).thenReturn(Map.of());
    // 归属校验:loadFileRecord 返回本租户登记的 storage_path,使该对象路径被放行。
    when(runtimeRepo.loadFileRecord(any(), any()))
        .thenReturn(Map.of("storage_path", registeredPath));
    S3StorageProperties props = new S3StorageProperties();
    props.setBucket(bucket);
    return new PreprocessStep(
        runtimeRepo,
        security,
        mock(BatchObjectCryptoService.class),
        props,
        new S3ObjectStore(client, presigner, props));
  }

  private void putObject(String key, String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(bytes));
  }

  private ImportJobContext contextWithBlankRawPayload(ImportPayload payload) {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-objload-it");
    context.setJobCode("OBJLOAD_IMPORT");
    context.setWorkerId("worker-objload-1");
    context.setRawPayload(""); // 无内联内容,真正走对象路径(并验证早期校验放行 storagePath)
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 1L);
    attrs.put(PipelineRuntimeKeys.TASK_ID, 101L);
    attrs.put("importPayload", payload);
    context.setAttributes(attrs);
    return context;
  }

  private ImportPayload objectPayload(String storagePath) {
    // 字段序见 ImportPayload:...,12 storageType,13 storagePath,14 storageBucket,15 templateCode,
    //   16 batchNo,17 content,18 contentBase64,...,23 metadata。content/contentBase64 留空 = 走对象。
    return new ImportPayload(
        null,
        null,
        null,
        null,
        "JSON",
        null,
        null,
        null,
        null,
        null,
        null,
        "S3",
        storagePath,
        bucket,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of());
  }

  @Test
  void loadsObjectFromStorage_whenNoInlineContentButStoragePathPresent() throws Exception {
    String key = "ingress/objload-it/cust.json";
    String content = "{\"records\":[{\"customerNo\":\"OBJ-001\"},{\"customerNo\":\"OBJ-002\"}]}";
    putObject(key, content);

    ImportStageResult result = newStep(key).execute(contextWithBlankRawPayload(objectPayload(key)));

    assertThat(result.success()).as("object-path import should succeed").isTrue();
    // 下载的对象内容应流入 normalizedPayload(证明 storagePath 分支生效 + 早期校验放行)
    ImportJobContext probe = contextWithBlankRawPayload(objectPayload(key));
    ImportStageResult r2 = newStep(key).execute(probe);
    assertThat(r2.success()).isTrue();
    Object normalized = probe.getAttributes().get("normalizedPayload");
    assertThat(normalized).isNotNull();
    assertThat(normalized.toString()).contains("OBJ-001").contains("customerNo");
  }

  @Test
  void fails_whenStoragePathObjectMissing() {
    // 对象不存在 → downloadObjectBytes 抛错 → PREPROCESS 失败(而非静默空载)
    String key = "ingress/objload-it/nope.json";
    ImportStageResult result = newStep(key).execute(contextWithBlankRawPayload(objectPayload(key)));
    assertThat(result.success()).isFalse();
  }

  @Test
  void fails_whenStoragePathNotOwnedByTenant() throws Exception {
    // 越权防护:对象真实存在,但 payload.storagePath 与本租户 file_record 登记路径不符 →
    // 归属校验拒绝拉取(IMPORT_PREPROCESS_OBJECT_FORBIDDEN),PREPROCESS 失败,不读他租户对象。
    String registered = "ingress/objload-it/owned.json";
    String forged = "ingress/other-tenant/secret.json";
    putObject(forged, "{\"records\":[{\"customerNo\":\"LEAK\"}]}");

    ImportJobContext context = contextWithBlankRawPayload(objectPayload(forged));
    ImportStageResult result = newStep(registered).execute(context);

    assertThat(result.success()).as("cross-tenant object fetch must be refused").isFalse();
    assertThat(context.getAttributes().get("normalizedPayload"))
        .as("forbidden fetch must not leak object content")
        .isNull();
  }

  @Test
  void largeObject_streamsToSpoolWithoutHeapBuffering() throws Exception {
    // ≥16MB(spool 阈值)的对象走流式直载:落 spool 文件 + 设 IMPORT_LARGE_TEXT_PATH 交 PARSE 流式消费,
    // 不读进堆(normalizedPayload 不在 PREPROCESS 设置)。生成 ~17MB CSV 验证。
    String key = "ingress/objload-it/big.csv";
    StringBuilder sb = new StringBuilder(18 * 1024 * 1024);
    sb.append("entity_id,entity_type,score_value,score_band,score_date\n");
    int row = 0;
    while (sb.length() < 17 * 1024 * 1024) {
      sb.append("BIGSTREAM-").append(row++).append(",CUSTOMER,42,HIGH,2026-06-06\n");
    }
    putObject(key, sb.toString());

    ImportJobContext context = contextWithBlankRawPayload(objectPayload(key));
    ImportStageResult result = newStep(key).execute(context);

    assertThat(result.success()).as("large object stream-direct should succeed").isTrue();
    // 流式直载:设 spool 路径,PREPROCESS 不落 normalizedPayload(交给 PARSE 流式解码)
    Object spoolPath = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH);
    assertThat(spoolPath).as("spool path should be set for large object").isNotNull();
    assertThat(context.getAttributes().get("normalizedPayload"))
        .as("PREPROCESS should NOT materialize normalizedPayload for large object")
        .isNull();
    java.nio.file.Path spool = java.nio.file.Path.of(spoolPath.toString());
    assertThat(java.nio.file.Files.size(spool))
        .as("spool file should hold the streamed object bytes")
        .isGreaterThan(16L * 1024 * 1024);
    java.nio.file.Files.deleteIfExists(spool);
  }
}
