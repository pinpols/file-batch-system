package com.example.batch.worker.exports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import com.example.batch.worker.exports.infrastructure.S3ExportStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/** Integration test: S3ExportStorage read/write/copy/remove against real MinIO container. */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class S3ExportStorageIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired private S3ExportStorage storage;

  @Test
  void shouldWriteAndDetectJsonObject() {
    String objectName = "export/it-test-write.json";
    String content = "{\"test\":true,\"value\":42}";

    storage.writeJson(objectName, content);

    assertThat(storage.objectExists(objectName)).isTrue();
  }

  @Test
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
  void shouldCopyObjectToNewKey() {
    String source = "export/it-test-copy-source.json";
    String dest = "export/it-test-copy-dest.json";
    storage.writeJson(source, "{\"copy\":true}");

    storage.copyObject(source, dest);

    assertThat(storage.objectExists(dest)).isTrue();
    assertThat(storage.sha256Hex(dest)).isEqualTo(storage.sha256Hex(source));
  }

  @Test
  void shouldRemoveObject() {
    String objectName = "export/it-test-remove.json";
    storage.writeJson(objectName, "{\"remove\":true}");
    assertThat(storage.objectExists(objectName)).isTrue();

    storage.removeObject(objectName);

    assertThat(storage.objectExists(objectName)).isFalse();
  }

  @Test
  void shouldReturnFalseForNonExistentObject() {
    assertThat(
            storage.objectExists(
                "export/no-such-object-" + BatchDateTimeSupport.utcEpochMillis() + ".json"))
        .isFalse();
  }

  @Test
  void shouldWriteRawBytesAndDetectObject() {
    String objectName = "export/it-test-bytes.bin";
    byte[] bytes = "raw binary content".getBytes(StandardCharsets.UTF_8);

    String written = storage.writeObject(objectName, bytes, "application/octet-stream");

    assertThat(written).isEqualTo(objectName);
    assertThat(storage.objectExists(objectName)).isTrue();
  }

  @Test
  void shouldGenerateObjectNameWhenNullProvided() {
    String written =
        storage.writeObject(null, "{}".getBytes(StandardCharsets.UTF_8), "application/json");

    assertThat(written).isNotBlank();
    assertThat(storage.objectExists(written)).isTrue();
  }

  @Test
  void shouldRoundTripWrittenJsonThroughMinio() throws Exception {
    String objectName = "export/it-test-roundtrip.json";
    String content = "{\"roundTrip\":true,\"n\":7}";

    storage.writeJson(objectName, content);

    try (S3Client client =
        S3Client.builder()
            .endpointOverride(URI.create(s3Endpoint()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("minioadmin", "minioadmin123")))
            .forcePathStyle(true)
            .region(Region.US_EAST_1)
            .build()) {
      byte[] bytes =
          client
              .getObjectAsBytes(
                  GetObjectRequest.builder().bucket(minioBucket()).key(objectName).build())
              .asByteArray();
      String read = new String(bytes, StandardCharsets.UTF_8);
      assertThat(read).isEqualTo(content);
    }
  }
}
