package io.github.pinpols.batch.worker.exports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchKmsProperties;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.exports.BatchWorkerExportApplication;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.infrastructure.S3ExportStorage;
import io.github.pinpols.batch.worker.exports.stage.StoreStep;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试：使用当前测试存储后端验证 S3ExportStorage 的读取、写入、复制和删除。
 * (S3-compatible object storage by default, filesystem via {@code -Dbatch.test.storage.backend=filesystem}).
 */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("导出对象存储集成测试:写入,存在性判断,校验和,复制与删除在当前后端上的行为")
class S3ExportStorageIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  private S3ExportStorage storage;

  private final StoreStep storeStep;

  private final S3StorageProperties storageProperties;

  private final JdbcTemplate jdbcTemplate;

  private final BatchObjectStore objectStore;

  @Autowired
  S3ExportStorageIntegrationTest(
      StoreStep storeStep,
      S3StorageProperties storageProperties,
      JdbcTemplate jdbcTemplate,
      BatchObjectStore objectStore) {
    this.storeStep = storeStep;
    this.storageProperties = storageProperties;
    this.jdbcTemplate = jdbcTemplate;
    this.objectStore = objectStore;
  }

  @Test
  @DisplayName("已登记加密产物重派后真实对象与 PostgreSQL 摘要及安全元数据保持不变")
  void shouldReuseRegisteredEncryptedArtifactAfterWholePipelineRetry() throws Exception {
    String tenantId = "seam2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String objectName = "export/seam2/" + tenantId + ".json";
    long jobInstanceId = 101L;
    String keyRef = "SEAM2_TEST_KEY";
    String keyMaterial = Base64.getEncoder()
        .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII));
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    BatchKmsProperties kms = new BatchKmsProperties();
    kms.setDefaultKeyRef(keyRef);
    kms.setKeys(Map.of(keyRef, keyMaterial));
    BatchObjectCryptoService realCrypto = new BatchObjectCryptoService(security, kms);
    byte[] originalCiphertext =
        realCrypto.encrypt("original registered export".getBytes(StandardCharsets.UTF_8), keyRef);
    Path regenerated = Files.createTempFile("seam2-export-retry-", ".json");
    Files.writeString(regenerated, "regenerated plaintext from retry");

    try {
      storage.writeObject(objectName, originalCiphertext, "application/octet-stream");
      String originalChecksum = storage.sha256Hex(objectName);
      String originalMetadata = """
          {"jobInstanceId":%s,"contentEncryptionEnabled":true,
          "encryptionKeyRef":"%s","encryptionObjectVersion":"BATCHENC1"}
          """.formatted(jobInstanceId, keyRef);
      final String insertBatchFileRecordSql = """
          insert into batch.file_record (
              tenant_id, file_category, file_name, original_file_name, file_format_type,
              file_size_bytes, checksum_type, checksum_value, storage_type, storage_path,
              storage_bucket, source_type, file_status, metadata_json)
          values (?, 'OUTPUT', ?, ?, 'JSON', ?, 'SHA-256', ?, 'S3', ?, ?,
              'GENERATED', 'GENERATED', ?::jsonb)
          """;
      jdbcTemplate.update(
          insertBatchFileRecordSql,
          tenantId,
          tenantId + ".json",
          tenantId + ".json",
          (long) originalCiphertext.length,
          originalChecksum,
          objectName,
          storageProperties.getBucket(),
          originalMetadata);
      final String selectBatchFileRecordSql = """
          select metadata_json::text from batch.file_record where tenant_id = ? and storage_path = ?
      """;
      String metadataBefore =
          jdbcTemplate.queryForObject(selectBatchFileRecordSql, String.class, tenantId, objectName);

      ExportJobContext context = new ExportJobContext();
      context.setTenantId(tenantId);
      context.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, jobInstanceId);
      context.getAttributes().put(PipelineRuntimeKeys.GENERATED_FILE_PATH, regenerated.toString());
      context.getAttributes().put(PipelineRuntimeKeys.OBJECT_NAME, objectName);
      context
          .getAttributes()
          .put(
              PipelineRuntimeKeys.TEMPLATE_CONFIG,
              Map.of("content_encryption_enabled", true, "encryption_key_ref", keyRef));

      var result = storeStep.execute(context);

      assertThat(result.success()).isTrue();
      assertThat(context.getAttributes())
          .containsEntry(PipelineRuntimeKeys.CHECKSUM_VALUE, originalChecksum)
          .containsEntry(PipelineRuntimeKeys.EXPORT_STORE_COMMITTED, true);
      assertThat(storage.sha256Hex(objectName)).isEqualTo(originalChecksum);
      try (var storedBytes = objectStore.get(storageProperties.getBucket(), objectName)) {
        assertThat(realCrypto.decrypt(storedBytes.readAllBytes()))
            .isEqualTo("original registered export".getBytes(StandardCharsets.UTF_8));
      }
      final String selectBatchFileRecordSqlValue1 = """
          select checksum_value from batch.file_record where tenant_id = ? and storage_path = ?
      """;
      String selectedChecksumValue = jdbcTemplate.queryForObject(
          selectBatchFileRecordSqlValue1, String.class, tenantId, objectName);
      assertThat(selectedChecksumValue).isEqualTo(originalChecksum);
      final String selectBatchFileRecordSqlValue2 = """
          select metadata_json::text from batch.file_record where tenant_id = ? and storage_path = ?
      """;
      String selectedBatchFileRecord = jdbcTemplate.queryForObject(
          selectBatchFileRecordSqlValue2, String.class, tenantId, objectName);
      assertThat(selectedBatchFileRecord).isEqualTo(metadataBefore);
      assertThat(regenerated).doesNotExist();
    } finally {
      storage.removeObject(objectName);
      final String deleteBatchFileRecordSql = """
          delete from batch.file_record where tenant_id = ? and storage_path = ?
      """;
      jdbcTemplate.update(deleteBatchFileRecordSql, tenantId, objectName);
      Files.deleteIfExists(regenerated);
    }
  }

  @Test
  @DisplayName("写入 JSON 对象后,存在性检查立即为真")
  void shouldWriteAndDetectJsonObject() {
    String objectName = "export/it-test-write.json";
    String content = "{\"test\":true,\"value\":42}";

    storage.writeJson(objectName, content);

    assertThat(storage.objectExists(objectName)).isTrue();
  }

  @Test
  @DisplayName("写入后返回的校验和与本地独立计算值一致,可据此校验完整性")
  void shouldComputeCorrectSha256AfterWrite() throws Exception {
    String objectName = "export/it-test-sha256.json";
    String content = "{\"checksum\":\"test\"}";
    byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);

    storage.writeJson(objectName, content);

    String expectedHex =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contentBytes));
    assertThat(storage.sha256Hex(objectName)).isEqualTo(expectedHex);
  }

  @Test
  @DisplayName("复制对象后目标键存在,且内容校验和与源对象一致")
  void shouldCopyObjectToNewKey() {
    String source = "export/it-test-copy-source.json";
    String dest = "export/it-test-copy-dest.json";
    storage.writeJson(source, "{\"copy\":true}");

    storage.copyObject(source, dest);

    assertThat(storage.objectExists(dest)).isTrue();
    assertThat(storage.sha256Hex(dest)).isEqualTo(storage.sha256Hex(source));
  }

  @Test
  @DisplayName("删除对象后,存在性判断转为假")
  void shouldRemoveObject() {
    String objectName = "export/it-test-remove.json";
    storage.writeJson(objectName, "{\"remove\":true}");
    assertThat(storage.objectExists(objectName)).isTrue();

    storage.removeObject(objectName);

    assertThat(storage.objectExists(objectName)).isFalse();
  }

  @Test
  @DisplayName("查询不存在的对象返回假,不抛异常")
  void shouldReturnFalseForNonExistentObject() {
    assertThat(storage.objectExists(
            "export/no-such-object-" + BatchDateTimeSupport.utcEpochMillis() + ".json"))
        .isFalse();
  }

  @Test
  @DisplayName("写入原始字节后返回同一对象名,且可被检测到存在")
  void shouldWriteRawBytesAndDetectObject() {
    String objectName = "export/it-test-bytes.bin";
    byte[] bytes = "raw binary content".getBytes(StandardCharsets.UTF_8);

    String written = storage.writeObject(objectName, bytes, "application/octet-stream");

    assertThat(written).isEqualTo(objectName);
    assertThat(storage.objectExists(objectName)).isTrue();
  }

  @Test
  @DisplayName("未提供对象名时自动生成非空对象名并成功落盘")
  void shouldGenerateObjectNameWhenNullProvided() {
    String written =
        storage.writeObject(null, "{}".getBytes(StandardCharsets.UTF_8), "application/json");

    assertThat(written).isNotBlank();
    assertThat(storage.objectExists(written)).isTrue();
  }

  @Test
  @DisplayName("写入的 JSON 经当前后端直读后内容完全一致")
  void shouldRoundTripWrittenJsonThroughActiveBackend() throws Exception {
    String objectName = "export/it-test-roundtrip.json";
    String content = "{\"roundTrip\":true,\"n\":7}";

    storage.writeJson(objectName, content);

    byte[] bytes = readObject(s3Bucket(), objectName);
    String read = new String(bytes, StandardCharsets.UTF_8);
    assertThat(read).isEqualTo(content);
  }
}
