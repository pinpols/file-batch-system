package com.example.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.BatchWorkerImportApplication;
import com.example.batch.worker.imports.runtime.ImportIngressScanner;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test: ImportIngressScanner discovers a CSV file placed in MinIO and registers
 * it as a platform file record in the DB.
 *
 * <p>The scanner is enabled here via {@code @TestPropertySource}, overriding the
 * {@code scanner.enabled=false} in application-test.yml. Stability window is set to 0 so
 * the file is immediately treated as stable.
 */
@SpringBootTest(
        classes = BatchWorkerImportApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "batch.worker.import.tenant-id=t1",
        "batch.worker.import.scanner.enabled=true",
        "batch.worker.import.scanner.stability-window-seconds=0",
        "batch.worker.import.scanner.prefix=ingress/",
        "batch.worker.import.scanner.require-done-file=false"
})
class ImportIngressScannerIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void orchestratorStub(DynamicPropertyRegistry registry) {
        OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
    }

    @Autowired
    private ImportIngressScanner scanner;

    @Autowired
    private PlatformFileRuntimeRepository runtimeRepository;

    @Test
    void shouldRegisterDiscoveredFileInPlatformDb() throws Exception {
        String objectName = "ingress/it-scan-test.csv";
        String bucket = minioBucket();

        // Upload a minimal CSV to MinIO
        byte[] content = "id,name\n1,Alice\n".getBytes(StandardCharsets.UTF_8);
        MinioClient client = MinioClient.builder()
                .endpoint(minioEndpoint())
                .credentials("minioadmin", "minioadmin123")
                .build();
        client.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .stream(new ByteArrayInputStream(content), content.length, -1)
                        .contentType("text/csv")
                        .build()
        );

        // scanner disabled in scheduler but we call scan() directly
        scanner.scan();

        assertThat(runtimeRepository.existsFileRecordByStoragePath("t1", bucket, objectName)).isTrue();
        Map<String, Object> row = runtimeRepository.loadFileRecordByStoragePath("t1", bucket, objectName);
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
        String bucket = minioBucket();

        byte[] content = "id,name\n2,Bob\n".getBytes(StandardCharsets.UTF_8);
        MinioClient client = MinioClient.builder()
                .endpoint(minioEndpoint())
                .credentials("minioadmin", "minioadmin123")
                .build();
        client.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .stream(new ByteArrayInputStream(content), content.length, -1)
                        .contentType("text/csv")
                        .build()
        );

        // First scan: registers the file
        scanner.scan();
        assertThat(runtimeRepository.existsFileRecordByStoragePath("t1", bucket, objectName)).isTrue();
        Map<String, Object> first = runtimeRepository.loadFileRecordByStoragePath("t1", bucket, objectName);
        long firstId = ((Number) first.get("id")).longValue();

        // Second scan: should not create a duplicate (idempotent)
        scanner.scan();
        assertThat(runtimeRepository.existsFileRecordByStoragePath("t1", bucket, objectName)).isTrue();
        Map<String, Object> after = runtimeRepository.loadFileRecordByStoragePath("t1", bucket, objectName);
        assertThat(((Number) after.get("id")).longValue()).isEqualTo(firstId);
    }
}
