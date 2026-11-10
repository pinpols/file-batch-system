package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectListing;
import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
  private InMemoryImportIngressScanRepository scans;

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

    scans = new InMemoryImportIngressScanRepository();
    scanner = new ImportIngressScanner(
        runtimeRepository,
        fileAudits,
        workerConfiguration,
        scannerProperties,
        s3StorageProperties,
        objectStore,
        new ObjectMapper(),
        scans,
        ImportIngressIdentityFixtures.s3Identity());
    declareBindings(List.of());
    lenient().when(objectStore.exists(any(), any())).thenReturn(true);
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
  @DisplayName("同一存储范围和等价规则的摘要不受扫描间隔、批大小及绑定顺序影响")
  void scanIdentity_shouldIgnoreOperationalSettingsAndBindingOrder() {
    ImportScannerProperties.ChannelBinding first = binding("t1", "ingress/t1/a/", "CH_A");
    ImportScannerProperties.ChannelBinding second = binding("t2", "ingress/t2/b/", "CH_B");
    declareBindings(List.of(first, second));
    String scope = scanner.scanScope();
    String rules = scanner.scanConfigurationDigest();

    scannerProperties.setPollIntervalMillis(5000);
    scannerProperties.setBatchSize(5);
    declareBindings(List.of(second, first));

    assertThat(scanner.scanScope()).isEqualTo(scope);
    assertThat(scanner.scanConfigurationDigest()).isEqualTo(rules);
    scannerProperties.setSourceType("API");
    assertThat(scanner.scanScope()).isEqualTo(scope);
    assertThat(scanner.scanConfigurationDigest()).isNotEqualTo(rules);
    scannerProperties.setPrefix("other/");
    assertThat(scanner.scanScope()).isNotEqualTo(scope);
  }

  @Test
  @DisplayName("导入渠道仅按租户和最长受控目录前缀绑定")
  void scanner_shouldAssignConfiguredTenantAndLongestPrefix() {
    scannerProperties.setDefaultBizDate("2026-11-09");
    declareBindings(List.of(
        binding("t1", "ingress/t1/", "CH_BASE"),
        binding("t1", "ingress/t1/a/", "CH_A"),
        binding("other", "ingress/t2/", "CH_OTHER")));
    ObjectSummary matched = new ObjectSummary("ingress/t1/a/orders.csv", 1, Instant.now(), "a");
    ObjectSummary unmatched = new ObjectSummary("ingress/t2/orders.csv", 1, Instant.now(), "b");
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListing(List.of(matched, unmatched), null));

    scanner.scan();

    ArgumentCaptor<FileRecordParam> records = ArgumentCaptor.forClass(FileRecordParam.class);
    verify(runtimeRepository, times(2)).createFileRecord(records.capture());
    assertThat(records.getAllValues())
        .anySatisfy(record -> {
          assertThat(record.getStoragePath()).isEqualTo(matched.key());
          assertThat(record.getArrivalChannelCode()).isEqualTo("CH_A");
        })
        .anySatisfy(record -> {
          assertThat(record.getStoragePath()).isEqualTo(unmatched.key());
          assertThat(record.getArrivalChannelCode()).isNull();
        });
  }

  private static ImportScannerProperties.ChannelBinding binding(
      String tenant, String prefix, String channel) {
    ImportScannerProperties.ChannelBinding binding = new ImportScannerProperties.ChannelBinding();
    binding.setTenantId(tenant);
    binding.setObjectPrefix(prefix);
    binding.setChannelCode(channel);
    return binding;
  }

  @ParameterizedTest
  @MethodSource("disabledBatchManifests")
  @DisplayName("批次清单功能关闭后仍保留协议文件归属，重复扫描不误登记为数据文件")
  void shouldExcludeProtocolObjectFromDataIngress_whenBatchManifestIsDisabled(
      String suffix, String defaultBizDate) throws Exception {
    scannerProperties.setBatchManifestEnabled(false);
    scannerProperties.setBatchManifestSuffix(suffix);
    scannerProperties.setDefaultBizDate(defaultBizDate);
    ObjectSummary manifest =
        new ObjectSummary("ingress/t1/bundle" + suffix, 100L, Instant.now(), "manifest-version");
    when(objectStore.list(any(), any(), any(), anyInt()))
        .thenReturn(new ObjectListing(List.of(manifest), null));

    for (int scan = 0; scan < 3; scan++) {
      scanner.scan();
    }

    verifyNoInteractions(runtimeRepository, fileAudits);
    verify(objectStore, never()).get(anyString(), anyString());
  }

  static Stream<Arguments> disabledBatchManifests() {
    return Stream.of(
        Arguments.of(".batch.json", null),
        Arguments.of(".batch.json", "2026-10-16"),
        Arguments.of(".group.json", null),
        Arguments.of(".group.json", "2026-10-16"));
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

  @Test
  @DisplayName("未声明 ACTIVE 范围时不列举对象")
  void scan_skipsUndeclaredScope() {
    scannerProperties.setPrefix("undeclared/");
    assertThat(scanner.scan()).isEqualTo(ImportIngressScanner.ScanResult.OWNERSHIP_INACTIVE);
    verifyNoInteractions(objectStore, runtimeRepository);
  }

  @Test
  @DisplayName("遗留静态渠道绑定必须迁移到共享声明")
  void scan_rejectsStaticBindings() {
    scannerProperties.setChannelBindings(List.of(binding("t1", "ingress/t1/", "CH_A")));
    assertThatThrownBy(() -> scanner.scan())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("迁移至共享入口归属声明");
    verifyNoInteractions(objectStore, runtimeRepository);
  }

  @Test
  @DisplayName("迁移相同清单和渠道声明后保持既有扫描规则摘要")
  void scanDigest_shouldMatchPreviouslyPersistedStaticBindings() {
    scannerProperties.setPrefix("ingress/wait-file-76ee0f1a/");
    scannerProperties.setDefaultBizDate("2026-11-09");
    scannerProperties.setBatchManifestEnabled(true);
    scannerProperties.setStabilityWindowSeconds(2);
    workerConfiguration = new ImportWorkerConfiguration(
        null,
        "IMPORT",
        "default-tenant",
        15000L,
        "test-topic",
        "test-group",
        null,
        new ImportWorkerConfiguration.FileProcessing(false, 1000, 1000, 500),
        null);
    scanner = new ImportIngressScanner(
        runtimeRepository,
        fileAudits,
        workerConfiguration,
        scannerProperties,
        s3StorageProperties,
        objectStore,
        new ObjectMapper(),
        scans,
        ImportIngressIdentityFixtures.s3Identity());
    String directory = "ingress/wait-file-76ee0f1a/default-tenant/input/";
    List<ImportIngressOwnershipRepository.Binding> bindings = List.of(
        new ImportIngressOwnershipRepository.Binding(
            "MANIFEST",
            "default-tenant",
            directory,
            "G_76ee0f1a",
            "validation-manifests/wait-file-76ee0f1a/customers-{bizDateCompact}.batch.json",
            null,
            true),
        new ImportIngressOwnershipRepository.Binding(
            "CHANNEL", "default-tenant", directory, null, null, "scan_76ee0f1a", true));
    scans.declare(new ImportIngressOwnershipRepository.Scope(
        scanner.scanScope(),
        scanner.scanStorageIdentity(),
        s3StorageProperties.getBucket(),
        scannerProperties.getPrefix(),
        "ACTIVE",
        bindings));
    assertThat(scanner.scanConfigurationDigest())
        .isEqualTo("8af03a3dfab1b5bbc47e70b022feee6f4b93ad62d0e3701ef5d9dff6a924161c");
  }

  private void declareBindings(List<ImportScannerProperties.ChannelBinding> channels) {
    List<ImportIngressOwnershipRepository.Binding> bindings = channels.stream()
        .map(value -> new ImportIngressOwnershipRepository.Binding(
            "CHANNEL",
            value.getTenantId(),
            value.getObjectPrefix(),
            null,
            null,
            value.getChannelCode(),
            true))
        .toList();
    scans.declare(new ImportIngressOwnershipRepository.Scope(
        scanner.scanScope(),
        scanner.scanStorageIdentity(),
        s3StorageProperties.getBucket(),
        scannerProperties.getPrefix(),
        "ACTIVE",
        bindings));
  }
}
