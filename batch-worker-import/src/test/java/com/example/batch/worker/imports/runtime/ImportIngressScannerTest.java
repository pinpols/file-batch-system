package com.example.batch.worker.imports.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportScannerProperties;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImportIngressScannerTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  @Mock private MinioClient minioClient;

  private ImportWorkerConfiguration workerConfiguration;
  private ImportScannerProperties scannerProperties;
  private MinioStorageProperties minioStorageProperties;
  private ImportIngressScanner scanner;

  @BeforeEach
  void setUp() {
    workerConfiguration =
        new ImportWorkerConfiguration(
            null,
            "IMPORT",
            "t1",
            15000L,
            "test-topic",
            "test-group",
            null,
            new ImportWorkerConfiguration.FileProcessing(false, 1000, 1000, 500));

    scannerProperties = new ImportScannerProperties();
    scannerProperties.setEnabled(true);
    scannerProperties.setPrefix("ingress/");
    scannerProperties.setBatchSize(10);
    scannerProperties.setRequireDoneFile(false);
    scannerProperties.setStabilityWindowSeconds(0L);

    minioStorageProperties = new MinioStorageProperties();
    minioStorageProperties.setBucket("batch-dev");

    scanner =
        new ImportIngressScanner(
            runtimeRepository,
            workerConfiguration,
            scannerProperties,
            minioStorageProperties,
            minioClient);
  }

  @Test
  void scan_skipsWhenBucketEnsureFails() throws Exception {
    when(minioClient.bucketExists(any())).thenThrow(new RuntimeException("minio unavailable"));

    scanner.scan();

    verifyNoInteractions(runtimeRepository);
  }
}
