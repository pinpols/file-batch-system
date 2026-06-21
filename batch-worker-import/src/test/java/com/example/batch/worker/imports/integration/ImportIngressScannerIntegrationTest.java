package com.example.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.BatchWorkerImportApplication;
import com.example.batch.worker.imports.runtime.ImportIngressScanner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 集成测试：ImportIngressScanner 发现放置在 MinIO 中的 CSV 文件并将其注册为数据库中的平台文件记录。
 *
 * <p>此处通过 {@code @TestPropertySource} 启用扫描器，覆盖 application-test.yml 中的 {@code
 * scanner.enabled=false}。稳定窗口设为 0，文件立即被视为稳定。
 */
@SpringBootTest(
    classes = BatchWorkerImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.worker.import.tenant-id=t1",
      "batch.worker.import.scanner.enabled=true",
      "batch.worker.import.scanner.stability-window-seconds=0",
      "batch.worker.import.scanner.prefix=ingress/",
      "batch.worker.import.scanner.require-done-file=false",
      "batch.worker.import.scanner.default-biz-date=2026-05-05",
      "batch.worker.import.scanner.batch-manifest-enabled=true"
    })
class ImportIngressScannerIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired private ImportIngressScanner scanner;

  @Autowired private PlatformFileRuntimeRepository runtimeRepository;

  @Test
  void shouldRegisterDiscoveredFileInPlatformDb() throws Exception {
    String objectName = "ingress/it-scan-test.csv";
    String bucket = s3Bucket();

    // 上传一个最小化的 CSV 到 MinIO
    byte[] content = "id,name\n1,Alice\n".getBytes(StandardCharsets.UTF_8);
    S3Client client = s3Client();
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(objectName).contentType("text/csv").build(),
        RequestBody.fromBytes(content));

    // 调度器中扫描已禁用，但我们直接调用 scan()
    scanner.scan();

    assertThat(runtimeRepository.existsFileRecordByStoragePath("t1", bucket, objectName)).isTrue();
    Map<String, Object> row =
        runtimeRepository.loadFileRecordByStoragePath("t1", bucket, objectName);
    assertThat(row).isNotEmpty();
    assertThat(row.get("tenant_id")).isEqualTo("t1");
    assertThat(row.get("storage_bucket")).isEqualTo(bucket);
    assertThat(row.get("storage_path")).isEqualTo(objectName);
    assertThat(row.get("file_status")).isEqualTo("RECEIVED");
    assertThat(((Number) row.get("file_size_bytes")).longValue()).isEqualTo(content.length);
  }

  @Test
  void shouldNotRegisterAlreadyKnownFile() throws Exception {
    String objectName = "ingress/it-scan-already-known.csv";
    String bucket = s3Bucket();

    byte[] content = "id,name\n2,Bob\n".getBytes(StandardCharsets.UTF_8);
    S3Client client = s3Client();
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(objectName).contentType("text/csv").build(),
        RequestBody.fromBytes(content));

    // 第一次扫描：注册文件
    scanner.scan();
    assertThat(runtimeRepository.existsFileRecordByStoragePath("t1", bucket, objectName)).isTrue();
    Map<String, Object> first =
        runtimeRepository.loadFileRecordByStoragePath("t1", bucket, objectName);
    long firstId = ((Number) first.get("id")).longValue();

    // 第二次扫描：不应创建重复记录（幂等）
    scanner.scan();
    assertThat(runtimeRepository.existsFileRecordByStoragePath("t1", bucket, objectName)).isTrue();
    Map<String, Object> after =
        runtimeRepository.loadFileRecordByStoragePath("t1", bucket, objectName);
    assertThat(((Number) after.get("id")).longValue()).isEqualTo(firstId);
  }

  @Test
  void shouldPersistBundleTemplateCodeFromV2Manifest() throws Exception {
    String bucket = s3Bucket();
    String dataObject = "ingress/order-it.csv";
    String manifestObject = "ingress/bundle-it.batch.json";
    S3Client client = s3Client();

    // v2 批次清单:声明 order-it.csv 用模板 TPL_ORDER
    String manifestJson =
        """
        {
          "schemaVersion": "batch-manifest-v2",
          "fileGroupCode": "bundle-it-group",
          "bizDate": "2026-05-05",
          "tenantId": "t1",
          "requiredFiles": ["order-it.csv"],
          "jobCode": "BUNDLE_IMPORT_IT",
          "fileMapping": [ { "fileName": "order-it.csv", "templateCode": "TPL_ORDER" } ]
        }
        """;
    client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(manifestObject)
            .contentType("application/json")
            .build(),
        RequestBody.fromBytes(manifestJson.getBytes(StandardCharsets.UTF_8)));
    byte[] content = "id,name\n1,Alice\n".getBytes(StandardCharsets.UTF_8);
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(dataObject).contentType("text/csv").build(),
        RequestBody.fromBytes(content));

    scanner.scan();

    Map<String, Object> row =
        runtimeRepository.loadFileRecordByStoragePath("t1", bucket, dataObject);
    assertThat(row).isNotEmpty();
    // ADR-046:per-file 模板从 v2 manifest 落到 file_record.metadata_json,供 launch 展异构 partition
    assertThat(String.valueOf(row.get("metadata_json")))
        .contains("bundleTemplateCode")
        .contains("TPL_ORDER")
        // 2c-2a:本束该启动的 BUNDLE_IMPORT 作业 code 也落库,供 2c-2b 凑齐发 launch
        .contains("bundleJobCode")
        .contains("BUNDLE_IMPORT_IT");
  }

  @Test
  void shouldBackfillBundleMetadataWhenDataArrivesBeforeManifest() throws Exception {
    String bucket = s3Bucket();
    String suffix = String.valueOf(System.nanoTime());
    String dataFileName = "order-late-manifest-it-" + suffix + ".csv";
    String dataObject = "ingress/" + dataFileName;
    String manifestObject = "ingress/bundle-late-manifest-it-" + suffix + ".batch.json";
    S3Client client = s3Client();

    byte[] content = "id,name\n3,Carol\n".getBytes(StandardCharsets.UTF_8);
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(dataObject).contentType("text/csv").build(),
        RequestBody.fromBytes(content));

    scanner.scan();
    Map<String, Object> before =
        runtimeRepository.loadFileRecordByStoragePath("t1", bucket, dataObject);
    assertThat(before).isNotEmpty();
    assertThat(String.valueOf(before.get("metadata_json"))).doesNotContain("bundleJobCode");

    String manifestJson =
        """
        {
          "schemaVersion": "batch-manifest-v2",
          "fileGroupCode": "bundle-late-it-group",
          "bizDate": "2026-05-05",
          "tenantId": "t1",
          "requiredFiles": ["%s"],
          "jobCode": "BUNDLE_IMPORT_LATE_IT",
          "fileMapping": [
            { "fileName": "%s", "templateCode": "TPL_ORDER_LATE" }
          ]
        }
        """
            .formatted(dataFileName, dataFileName);
    client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(manifestObject)
            .contentType("application/json")
            .build(),
        RequestBody.fromBytes(manifestJson.getBytes(StandardCharsets.UTF_8)));

    scanner.scan();

    Map<String, Object> after =
        runtimeRepository.loadFileRecordByStoragePath("t1", bucket, dataObject);
    assertThat(String.valueOf(after.get("metadata_json")))
        .contains("requiredFileSet")
        .contains("bundleTemplateCode")
        .contains("TPL_ORDER_LATE")
        .contains("bundleJobCode")
        .contains("BUNDLE_IMPORT_LATE_IT");
  }

  private S3Client s3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(s3Endpoint()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("minioadmin", "minioadmin123")))
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
        .build();
  }
}
