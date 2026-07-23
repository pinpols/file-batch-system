package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectListing;
import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImportIngressScannerTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  @Mock private BatchObjectStore objectStore;

  private ImportWorkerConfiguration workerConfiguration;
  private ImportScannerProperties scannerProperties;
  private S3StorageProperties s3StorageProperties;
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
            new ImportWorkerConfiguration.FileProcessing(false, 1000, 1000, 500),
            null);

    scannerProperties = new ImportScannerProperties();
    scannerProperties.setEnabled(true);
    scannerProperties.setPrefix("ingress/");
    scannerProperties.setBatchSize(10);
    scannerProperties.setRequireDoneFile(false);
    scannerProperties.setStabilityWindowSeconds(0L);

    s3StorageProperties = new S3StorageProperties();
    s3StorageProperties.setBucket("batch-dev");

    scanner =
        new ImportIngressScanner(
            runtimeRepository,
            workerConfiguration,
            scannerProperties,
            s3StorageProperties,
            objectStore,
            new ObjectMapper());
  }

  @Test
  void scan_propagatesAndSkipsRegistration_whenListFails() {
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenThrow(new RuntimeException("storage unavailable"));

    assertThatThrownBy(() -> scanner.scan())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to scan objectStore ingress objects");

    verifyNoInteractions(runtimeRepository);
  }

  @Test
  void scan_readsAndValidatesSidecar_whenDoneFileFormatIsJsonAlias() {
    scannerProperties.setRequireDoneFile(true);
    scannerProperties.setDefaultBizDate("2026-07-23");
    scannerProperties.setDoneFileFormat(ImportScannerProperties.DoneFileFormat.JSON);
    ObjectSummary data = new ObjectSummary("ingress/t1/orders.csv", 5L, Instant.now(), "e1");
    ObjectSummary marker =
        new ObjectSummary("ingress/t1/orders.csv.done", 15L, Instant.now(), "e2");
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListing(List.of(data, marker), null));
    when(objectStore.get("batch-dev", "ingress/t1/orders.csv.done"))
        .thenReturn(new ByteArrayInputStream("{\"sizeBytes\":5}".getBytes(StandardCharsets.UTF_8)));
    when(runtimeRepository.existsFileRecordByStoragePath(
            "t1", "batch-dev", "ingress/t1/orders.csv"))
        .thenReturn(false);
    when(runtimeRepository.createFileRecord(any())).thenReturn(1L);

    scanner.scan();

    verify(objectStore).get("batch-dev", "ingress/t1/orders.csv.done");
    verify(runtimeRepository).createFileRecord(any());
  }

  @Test
  void scan_keepsMarkerModeBackwardCompatible_withoutReadingSidecar() {
    scannerProperties.setRequireDoneFile(true);
    scannerProperties.setDefaultBizDate("2026-07-23");
    scannerProperties.setDoneFileFormat(ImportScannerProperties.DoneFileFormat.MARKER);
    ObjectSummary data = new ObjectSummary("ingress/t1/orders.csv", 5L, Instant.now(), "e1");
    ObjectSummary marker = new ObjectSummary("ingress/t1/orders.csv.done", 0L, Instant.now(), "e2");
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListing(List.of(data, marker), null));
    when(runtimeRepository.existsFileRecordByStoragePath(
            "t1", "batch-dev", "ingress/t1/orders.csv"))
        .thenReturn(false);
    when(runtimeRepository.createFileRecord(any())).thenReturn(1L);

    scanner.scan();

    verify(objectStore, never()).get(any(), any());
    verify(runtimeRepository).createFileRecord(any());
  }
}
