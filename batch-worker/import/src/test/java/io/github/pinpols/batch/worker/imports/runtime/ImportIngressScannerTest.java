package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("导入入口扫描器单测:列举失败上抛,完成标记读取与兼容模式语义")
class ImportIngressScannerTest {

  @Mock
  private PlatformFileRecordRepository runtimeRepository;

  @Mock
  private PlatformFileAuditRepository fileAudits;

  @Mock
  private BatchObjectStore objectStore;

  private ImportWorkerConfiguration workerConfiguration;
  private ImportScannerProperties scannerProperties;
  private S3StorageProperties s3StorageProperties;
  private ImportIngressScanner scanner;

  @BeforeEach
  void setUp() {
    workerConfiguration = new ImportWorkerConfiguration(
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

    scanner = new ImportIngressScanner(
        runtimeRepository,
        fileAudits,
        workerConfiguration,
        scannerProperties,
        s3StorageProperties,
        objectStore,
        new ObjectMapper());
  }

  @Test
  @DisplayName("列举对象失败时向上抛出,且不触发任何登记写库")
  void scan_propagatesAndSkipsRegistration_whenListFails() {
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenThrow(new RuntimeException("storage unavailable"));

    assertThatThrownBy(() -> scanner.scan())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to scan objectStore ingress objects");

    verifyNoInteractions(runtimeRepository);
  }

  @Test
  @DisplayName("MANIFEST 模式校验 schema、文件名、长度和原始对象摘要后再登记")
  void scan_readsAndValidatesSidecar_whenDoneFileFormatIsJsonAlias() throws Exception {
    scannerProperties.setRequireDoneFile(true);
    scannerProperties.setDefaultBizDate("2026-07-23");
    scannerProperties.setDoneFileFormat(ImportScannerProperties.DoneFileFormat.JSON);
    ObjectSummary data = new ObjectSummary("ingress/t1/orders.csv", 5L, Instant.now(), "e1");
    ObjectSummary marker =
        new ObjectSummary("ingress/t1/orders.csv.done", 15L, Instant.now(), "e2");
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListing(List.of(data, marker), null));
    byte[] dataBytes = "hello".getBytes(StandardCharsets.UTF_8);
    String checksum =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dataBytes));
    when(objectStore.get("batch-dev", "ingress/t1/orders.csv.done"))
        .thenReturn(
            new ByteArrayInputStream(("""
                    {"schemaVersion":"file-sidecar-manifest-v1","fileName":"orders.csv","sizeBytes":5,
                     "checksumType":"SHA-256","checksumValue":"%s","checksumScope":"RAW_OBJECT"}
                    """).formatted(checksum).getBytes(StandardCharsets.UTF_8)));
    when(objectStore.get("batch-dev", "ingress/t1/orders.csv"))
        .thenReturn(new ByteArrayInputStream(dataBytes));
    when(runtimeRepository.existsFileRecordByStoragePath(
            "t1", "batch-dev", "ingress/t1/orders.csv"))
        .thenReturn(false);
    when(runtimeRepository.createFileRecord(any())).thenReturn(1L);

    scanner.scan();

    verify(objectStore).get("batch-dev", "ingress/t1/orders.csv.done");
    verify(objectStore).get("batch-dev", "ingress/t1/orders.csv");
    ArgumentCaptor<FileRecordParam> recordCaptor = ArgumentCaptor.forClass(FileRecordParam.class);
    verify(runtimeRepository).createFileRecord(recordCaptor.capture());
    assertThat(((Map<?, ?>) recordCaptor.getValue().getMetadata()).get("integrityState"))
        .isEqualTo("VERIFIED");
  }

  @Test
  @DisplayName("MANIFEST 摘要与原始对象不一致时拒绝登记")
  void scan_skipsRegistration_whenManifestChecksumDoesNotMatchObject() {
    scannerProperties.setRequireDoneFile(true);
    scannerProperties.setDefaultBizDate("2026-07-23");
    scannerProperties.setDoneFileFormat(ImportScannerProperties.DoneFileFormat.MANIFEST);
    ObjectSummary data = new ObjectSummary("ingress/t1/orders.csv", 5L, Instant.now(), "e1");
    ObjectSummary marker =
        new ObjectSummary("ingress/t1/orders.csv.done", 15L, Instant.now(), "e2");
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListing(List.of(data, marker), null));
    String invalidManifest = """
        {"schemaVersion":"file-sidecar-manifest-v1","fileName":"orders.csv","sizeBytes":5,
         "checksumType":"SHA-256","checksumValue":"%s","checksumScope":"RAW_OBJECT"}
        """.formatted("0".repeat(64));
    when(objectStore.get("batch-dev", "ingress/t1/orders.csv.done"))
        .thenReturn(new ByteArrayInputStream(invalidManifest.getBytes(StandardCharsets.UTF_8)));
    when(objectStore.get("batch-dev", "ingress/t1/orders.csv"))
        .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

    scanner.scan();

    verify(runtimeRepository, never()).createFileRecord(any());
  }

  @Test
  @DisplayName("MANIFEST 拒绝 MD5 校验类型，避免弱摘要被当作完整性证明")
  void scan_skipsRegistration_whenManifestUsesMd5() {
    scannerProperties.setRequireDoneFile(true);
    scannerProperties.setDefaultBizDate("2026-07-23");
    scannerProperties.setDoneFileFormat(ImportScannerProperties.DoneFileFormat.MANIFEST);
    ObjectSummary data = new ObjectSummary("ingress/t1/orders.csv", 5L, Instant.now(), "e1");
    ObjectSummary marker =
        new ObjectSummary("ingress/t1/orders.csv.done", 15L, Instant.now(), "e2");
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListing(List.of(data, marker), null));
    String legacyManifest = """
        {"schemaVersion":"file-sidecar-manifest-v1","fileName":"orders.csv","sizeBytes":5,
         "checksumType":"MD5","checksumValue":"%s","checksumScope":"RAW_OBJECT"}
        """.formatted("a".repeat(32));
    when(objectStore.get("batch-dev", "ingress/t1/orders.csv.done"))
        .thenReturn(new ByteArrayInputStream(legacyManifest.getBytes(StandardCharsets.UTF_8)));

    scanner.scan();

    verify(objectStore, never()).get("batch-dev", "ingress/t1/orders.csv");
    verify(runtimeRepository, never()).createFileRecord(any());
  }

  @Test
  @DisplayName("标记模式保持向后兼容,不读取标记文件内容")
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
