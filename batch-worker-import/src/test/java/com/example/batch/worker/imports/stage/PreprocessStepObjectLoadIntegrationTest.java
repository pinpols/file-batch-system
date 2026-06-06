package com.example.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.testing.MinIOContainer;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStageResult;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-sim #382 回归:PREPROCESS 从对象存储拉取大文件(storagePath→MinIO)。
 *
 * <p>覆盖 {@code PreprocessStep.resolveRawBytes} 的对象拉取分支 + 早期 "raw payload is blank" 校验放行 storagePath
 * 情形(回归 #382:之前缺该分支 + 校验会拦截无内联内容的对象路径 import)。用真 MinIO 容器, 投对象 → 触发 PREPROCESS → 断言下载内容流入
 * normalizedPayload。
 */
@Tag("integration")
class PreprocessStepObjectLoadIntegrationTest {

  private static MinIOContainer minio;
  private static String bucket;
  private static MinioClient client;

  @BeforeAll
  static void startMinio() {
    minio = new MinIOContainer();
    minio.start();
    bucket = minio.getDefaultBucket();
    client = minio.client();
    minio.ensureBucketExists(bucket);
  }

  @AfterAll
  static void stopMinio() {
    if (minio != null) {
      minio.stop();
    }
  }

  private PreprocessStep newStep() {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(true); // 跳过解密,下载的明文直通 pipeline
    PlatformFileRuntimeRepository runtimeRepo = mock(PlatformFileRuntimeRepository.class);
    when(runtimeRepo.toLong(any())).thenReturn(1L);
    when(runtimeRepo.loadLatestTemplateConfig(any(), any(), any())).thenReturn(Map.of());
    MinioStorageProperties props = new MinioStorageProperties();
    props.setBucket(bucket);
    return new PreprocessStep(
        runtimeRepo, security, mock(BatchObjectCryptoService.class), props, client);
  }

  private void putObject(String key, String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    client.putObject(
        PutObjectArgs.builder().bucket(bucket).object(key).stream(
                new ByteArrayInputStream(bytes), bytes.length, -1)
            .build());
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

    ImportStageResult result = newStep().execute(contextWithBlankRawPayload(objectPayload(key)));

    assertThat(result.success()).as("object-path import should succeed").isTrue();
    // 下载的对象内容应流入 normalizedPayload(证明 storagePath 分支生效 + 早期校验放行)
    ImportJobContext probe = contextWithBlankRawPayload(objectPayload(key));
    ImportStageResult r2 = newStep().execute(probe);
    assertThat(r2.success()).isTrue();
    Object normalized = probe.getAttributes().get("normalizedPayload");
    assertThat(normalized).isNotNull();
    assertThat(normalized.toString()).contains("OBJ-001").contains("customerNo");
  }

  @Test
  void fails_whenStoragePathObjectMissing() {
    // 对象不存在 → downloadObjectBytes 抛错 → PREPROCESS 失败(而非静默空载)
    ImportStageResult result =
        newStep()
            .execute(contextWithBlankRawPayload(objectPayload("ingress/objload-it/nope.json")));
    assertThat(result.success()).isFalse();
  }
}
