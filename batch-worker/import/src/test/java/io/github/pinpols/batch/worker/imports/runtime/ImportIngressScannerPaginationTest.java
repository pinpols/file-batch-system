package io.github.pinpols.batch.worker.imports.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.FilesystemObjectStore;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("导入扫描器分页发现")
class ImportIngressScannerPaginationTest {

  @Mock
  private PlatformFileRecordRepository fileRecords;

  @Mock
  private PlatformFileAuditRepository fileAudits;

  @TempDir
  Path storageRoot;

  @Test
  @DisplayName("已登记对象占满一页时仍能发现后续新文件")
  void scan_shouldDiscoverNewFile_afterPreviouslyRegisteredPage() throws Exception {
    Path tenantDir = storageRoot.resolve("batch-dev/ingress/t1");
    Files.createDirectories(tenantDir);
    for (int index = 0; index < 201; index++) {
      Files.writeString(tenantDir.resolve("input-%03d.csv".formatted(index)), "data");
    }

    ImportScannerProperties properties = new ImportScannerProperties();
    properties.setBatchSize(200);
    properties.setStabilityWindowSeconds(0);
    properties.setDefaultBizDate("2026-11-09");
    S3StorageProperties storageProperties = new S3StorageProperties();
    storageProperties.setBucket("batch-dev");
    ImportWorkerConfiguration worker = new ImportWorkerConfiguration(
        null,
        "IMPORT",
        "t1",
        15000L,
        "test-topic",
        "test-group",
        null,
        new ImportWorkerConfiguration.FileProcessing(false, 1000, 1000, 500),
        null);
    FilesystemObjectStore objectStore =
        new FilesystemObjectStore(storageRoot.toString(), "", "test-secret");
    InMemoryImportIngressScanRepository scans = new InMemoryImportIngressScanRepository();
    ImportIngressScanner scanner = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        properties,
        storageProperties,
        objectStore,
        new ObjectMapper(),
        scans,
        ImportIngressIdentityFixtures.s3Identity());
    scans.declare(new ImportIngressOwnershipRepository.Scope(
        scanner.scanScope(),
        scanner.scanStorageIdentity(),
        storageProperties.getBucket(),
        properties.getPrefix(),
        "ACTIVE",
        List.of()));
    String newFile = "ingress/t1/input-200.csv";
    when(fileRecords.existsFileRecordByStoragePath(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> !newFile.equals(invocation.getArgument(2)));
    AtomicBoolean registered = new AtomicBoolean();
    when(fileRecords.createFileRecord(any())).thenAnswer(invocation -> {
      registered.set(true);
      return 1L;
    });

    for (int i = 0; i < 10 && !registered.get(); i++) {
      scanner.scan();
    }

    verify(fileRecords)
        .createFileRecord(argThat(record -> newFile.equals(record.getStoragePath())));
  }

  @Test
  @DisplayName("持续追加超过每轮批大小的对象时，已发现文件仍能进入登记")
  void scan_shouldRegisterDiscoveredFiles_whenNewKeysKeepArriving() throws Exception {
    Path tenantDir = storageRoot.resolve("batch-dev/ingress/t1");
    Files.createDirectories(tenantDir);
    ImportScannerProperties properties = new ImportScannerProperties();
    properties.setBatchSize(2);
    properties.setStabilityWindowSeconds(0);
    properties.setDefaultBizDate("2026-11-09");
    S3StorageProperties storageProperties = new S3StorageProperties();
    storageProperties.setBucket("batch-dev");
    ImportWorkerConfiguration worker = new ImportWorkerConfiguration(
        null,
        "IMPORT",
        "t1",
        15000L,
        "test-topic",
        "test-group",
        null,
        new ImportWorkerConfiguration.FileProcessing(false, 1000, 1000, 500),
        null);
    FilesystemObjectStore objectStore =
        new FilesystemObjectStore(storageRoot.toString(), "", "test-secret");
    InMemoryImportIngressScanRepository scans = new InMemoryImportIngressScanRepository();
    ImportIngressScanner scanner = new ImportIngressScanner(
        fileRecords,
        fileAudits,
        worker,
        properties,
        storageProperties,
        objectStore,
        new ObjectMapper(),
        scans,
        ImportIngressIdentityFixtures.s3Identity());
    scans.declare(new ImportIngressOwnershipRepository.Scope(
        scanner.scanScope(),
        scanner.scanStorageIdentity(),
        storageProperties.getBucket(),
        properties.getPrefix(),
        "ACTIVE",
        List.of()));

    for (int tick = 0; tick < 8; tick++) {
      for (int offset = 0; offset < 3; offset++) {
        int sequence = tick * 3 + offset;
        Files.writeString(tenantDir.resolve("input-%03d.csv".formatted(sequence)), "data");
      }
      scanner.scan();
    }

    verify(fileRecords, atLeastOnce()).createFileRecord(any());
  }
}
