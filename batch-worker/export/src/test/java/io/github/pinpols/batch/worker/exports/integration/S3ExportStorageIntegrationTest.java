package io.github.pinpols.batch.worker.exports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.exports.BatchWorkerExportApplication;
import io.github.pinpols.batch.worker.exports.infrastructure.S3ExportStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test: S3ExportStorage read/write/copy/remove against the active test storage backend
 * (S3-compatible object storage by default, filesystem via {@code -Dbatch.test.storage.backend=filesystem}).
 */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("导出对象存储集成测试:写入,存在性判断,校验和,复制与删除在当前后端上的行为")
class S3ExportStorageIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  private S3ExportStorage storage;

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
