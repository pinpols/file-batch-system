package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.S3ObjectStore;
import io.github.pinpols.batch.testing.MinioObjectStoreContainer;
import io.github.pinpols.batch.testing.TestObjectStoreContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileTemplateRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * ADR-sim #382 回归:PREPROCESS 从对象存储拉取大文件(storagePath→object store)。
 *
 * <p>覆盖 {@code PreprocessStep.resolveRawBytes} 的对象拉取分支 + 早期 "raw payload is blank" 校验放行 storagePath
 * 情形(回归 #382:之前缺该分支 + 校验会拦截无内联内容的对象路径 import)。用真 MinIO 容器, 投对象 → 触发 PREPROCESS → 断言下载内容流入
 * normalizedPayload。
 */
@Tag("integration")
@DisplayName("导入预处理对象拉取集成测试:对象下载流入载荷,越权拒绝与大对象流式直载")
class PreprocessStepObjectLoadIntegrationTest {

  private static MinioObjectStoreContainer objectStore;
  private static String bucket;
  private static S3Client client;
  private static S3Presigner presigner;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeAll
  static void startMinio() {
    objectStore = TestObjectStoreContainers.create();
    objectStore.start();
    bucket = objectStore.getDefaultBucket();
    client = objectStore.client();
    presigner = S3Presigner.builder()
        .endpointOverride(URI.create(objectStore.getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(objectStore.getAccessKey(), objectStore.getSecretKey())))
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(true).build())
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
    return newStep(registeredPath, true);
  }

  private PreprocessStep newStep(String registeredPath, boolean bypassMode) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(bypassMode); // 默认跳过解密；摘要场景启用正式校验路径
    PlatformFileRecordRepository runtimeRepo = mock(PlatformFileRecordRepository.class);
    PlatformFileTemplateRepository pipelineDefinitions = mock(PlatformFileTemplateRepository.class);
    when(pipelineDefinitions.loadLatestTemplateConfig(any(), any(), any())).thenReturn(Map.of());
    BatchObjectCryptoService cryptoService = mock(BatchObjectCryptoService.class);
    when(cryptoService.decrypt(any(byte[].class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    // 归属校验:loadFileRecord 返回本租户登记的 storage_path,使该对象路径被放行。
    when(runtimeRepo.loadFileRecord(any(), any()))
        .thenReturn(Map.of("storage_path", registeredPath));
    S3StorageProperties props = new S3StorageProperties();
    props.setBucket(bucket);
    return PreprocessStepFixture.create(
        runtimeRepo,
        pipelineDefinitions,
        security,
        cryptoService,
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
    attrs.put(PipelineRuntimeKeys.IMPORT_PAYLOAD, payload);
    context.setAttributes(attrs);
    return context;
  }

  private ImportPayload objectPayload(String storagePath) {
    return objectPayload(storagePath, "JSON");
  }

  private ImportPayload objectPayload(String storagePath, String format) {
    return objectPayload(storagePath, format, null, null);
  }

  private ImportPayload objectPayload(
      String storagePath, String format, String checksumType, String checksumValue) {
    Map<String, Object> fields = new HashMap<>();
    fields.put("fileFormatType", format);
    fields.put("storageType", "S3");
    fields.put("storagePath", storagePath);
    fields.put("storageBucket", bucket);
    if (checksumType != null) {
      fields.put("checksumType", checksumType);
      fields.put("checksumValue", checksumValue);
    }
    return objectMapper.convertValue(fields, ImportPayload.class);
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  @Test
  @DisplayName("无内联内容但给出对象路径时,从对象存储下载并流入规整载荷")
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
    Object normalized = probe.getAttributes().get(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD);
    assertThat(normalized).isNotNull();
    assertThat(normalized.toString()).contains("OBJ-001").contains("customerNo");
  }

  @Test
  @DisplayName("对象不存在时预处理失败,不静默按空内容继续")
  void fails_whenStoragePathObjectMissing() {
    // 对象不存在 → downloadObjectBytes 抛错 → PREPROCESS 失败(而非静默空载)
    String key = "ingress/objload-it/nope.json";
    ImportStageResult result = newStep(key).execute(contextWithBlankRawPayload(objectPayload(key)));
    assertThat(result.success()).isFalse();
  }

  @Test
  @DisplayName("登记对象暂时丢失时预处理失败,对象恢复后同一文件可重试成功")
  void recovers_whenRegisteredObjectIsRestoredAfterTemporaryLoss() throws Exception {
    String key = "ingress/objload-it/recover.json";
    byte[] content =
        "{\"records\":[{\"customerNo\":\"RECOVER-001\"}]}".getBytes(StandardCharsets.UTF_8);
    ImportPayload payload = objectPayload(key, "JSON", "SHA-256", sha256(content));

    client.deleteObject(builder -> builder.bucket(bucket).key(key));
    ImportJobContext failedContext = contextWithBlankRawPayload(payload);
    ImportStageResult missing = newStep(key, false).execute(failedContext);
    assertThat(missing.success())
        .as("missing registered object must fail closed")
        .isFalse();
    assertThat(failedContext.getAttributes().get(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD))
        .isNull();

    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    ImportJobContext recoveredContext = contextWithBlankRawPayload(payload);
    ImportStageResult recovered = newStep(key, false).execute(recoveredContext);
    assertThat(recovered.success()).as("restored object can be retried").isTrue();
    assertThat(String.valueOf(
            recoveredContext.getAttributes().get(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD)))
        .as("retry consumes the restored bytes")
        .contains("RECOVER-001");
  }

  @Test
  @DisplayName("登记后对象内容被替换时摘要复核失败且不产生规整载荷")
  void failsClosed_whenRegisteredObjectContentChanges() throws Exception {
    String key = "ingress/objload-it/tampered.json";
    byte[] registeredContent =
        "{\"records\":[{\"customerNo\":\"ORIGINAL\"}]}".getBytes(StandardCharsets.UTF_8);
    putObject(key, "{\"records\":[{\"customerNo\":\"TAMPERED\"}]}");
    ImportJobContext context = contextWithBlankRawPayload(
        objectPayload(key, "JSON", "SHA-256", sha256(registeredContent)));

    ImportStageResult result = newStep(key, false).execute(context);

    assertThat(result.success())
        .as("changed object must fail checksum verification")
        .isFalse();
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD))
        .as("changed bytes must not reach normalized payload")
        .isNull();
  }

  @Test
  @DisplayName("对象路径不属于本租户时拒绝拉取,且不泄漏对象内容")
  void fails_whenStoragePathNotOwnedByTenant() throws Exception {
    // 越权防护:对象真实存在,但 payload.storagePath 与本租户 file_record 登记路径不符 →
    // 归属校验拒绝拉取(IMPORT_PREPROCESS_OBJECT_FORBIDDEN),PREPROCESS 失败,不读他租户对象。
    String registered = "ingress/objload-it/owned.json";
    String forged = "ingress/other-tenant/secret.json";
    putObject(forged, "{\"records\":[{\"customerNo\":\"LEAK\"}]}");

    ImportJobContext context = contextWithBlankRawPayload(objectPayload(forged));
    ImportStageResult result = newStep(registered).execute(context);

    assertThat(result.success()).as("cross-tenant object fetch must be refused").isFalse();
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD))
        .as("forbidden fetch must not leak object content")
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("超大对象走完整流式直载:文件束即使开启范围切片也不截掉独立文件内容")
  void largeObject_streamsToSpoolWithoutHeapBuffering(boolean bundle) throws Exception {
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

    ImportJobContext context = contextWithBlankRawPayload(objectPayload(key, "DELIMITED"));
    if (bundle) {
      context.getAttributes().put(PipelineRuntimeKeys.BUNDLE_SOURCE_FILE_ID, 1L);
      context.getAttributes().put(PipelineRuntimeKeys.PARTITION_NO, 2);
      context.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 2);
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("partition_range_slice", true));
    }
    ImportStageResult result = newStep(key).execute(context);

    assertThat(result.success()).as("large object stream-direct should succeed").isTrue();
    // 流式直载:设 spool 路径,PREPROCESS 不落 normalizedPayload(交给 PARSE 流式解码)
    Object spoolPath = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH);
    assertThat(spoolPath).as("spool path should be set for large object").isNotNull();
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD))
        .as("PREPROCESS should NOT materialize normalizedPayload for large object")
        .isNull();
    Path spool = Path.of(spoolPath.toString());
    assertThat(Files.size(spool))
        .as("spool file should hold the streamed object bytes")
        .isEqualTo(sb.toString().getBytes(StandardCharsets.UTF_8).length);
    assertThat(context.getAttributes()).doesNotContainKey(PipelineRuntimeKeys.PARTITION_PRESLICED);
    Files.deleteIfExists(spool);
  }
}
