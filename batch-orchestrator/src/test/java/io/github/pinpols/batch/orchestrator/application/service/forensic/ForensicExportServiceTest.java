package io.github.pinpols.batch.orchestrator.application.service.forensic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayOperationAuditMapper;
import io.github.pinpols.batch.orchestrator.mapper.ForensicExportLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("取证导出服务: 打包产物, 请求参数校验与失败标记口径")
class ForensicExportServiceTest {

  @TempDir
  Path tempDir;

  private ForensicExportLogMapper logMapper;
  private JobInstanceMapper jobInstanceMapper;
  private BatchDayOperationAuditMapper auditMapper;
  private ForensicExportProperties properties;
  private ForensicExportService service;

  @BeforeEach
  void setUp() {
    logMapper = mock(ForensicExportLogMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    auditMapper = mock(BatchDayOperationAuditMapper.class);
    properties = new ForensicExportProperties();
    properties.setStorageDir(tempDir.toString());
    properties.setInstanceRowCap(10_000);
    properties.setEnabled(true);
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    ForensicExportLogTransactionService transactionService =
        new ForensicExportLogTransactionService(logMapper);
    service = new ForensicExportService(
        logMapper, jobInstanceMapper, auditMapper, properties, dateTimeSupport, transactionService);
  }

  @Test
  @DisplayName("导出成功时生成含清单与业务数据的压缩包, 并给出摘要与文件大小")
  void shouldProduceZipBundleWithManifestAndSha256() throws IOException {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(1L);
    instance.setTenantId("t1");
    instance.setJobCode("DAILY_PNL");
    instance.setBizDate(LocalDate.of(2026, Month.MARCH, 15));
    instance.setInstanceStatus("SUCCESS");
    when(jobInstanceMapper.selectForensicByBizDateRange(eq("t1"), any(), any(), isNull(), anyInt()))
        .thenReturn(List.of(instance));
    when(auditMapper.selectByCalendarBizDate(eq("t1"), isNull(), any(), anyInt()))
        .thenReturn(List.of());

    ForensicExportResponse response = service.export(ForensicExportRequest.builder()
        .tenantId("t1")
        .bizDateFrom(LocalDate.of(2026, Month.MARCH, 15))
        .bizDateTo(LocalDate.of(2026, Month.MARCH, 15))
        .requestedBy("ops")
        .build());

    assertThat(response.exportId()).isNotBlank();
    assertThat(response.status()).isEqualTo("COMPLETED");
    assertThat(response.fileSizeBytes()).isPositive();
    assertThat(response.sha256()).hasSize(64); // SHA-256 hex

    Path zipPath = Path.of(response.storagePath());
    assertThat(zipPath).exists();
    if (Files.getFileStore(zipPath).supportsFileAttributeView("posix")) {
      assertThat(Files.getPosixFilePermissions(zipPath))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      assertThat(Files.getPosixFilePermissions(zipPath.getParent()))
          .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      assertThat(zip.getEntry("manifest.json")).isNotNull();
      assertThat(zip.getEntry("job-instances.json")).isNotNull();
      assertThat(zip.getEntry("batch-day-operation-audits.json")).isNotNull();
    }

    verify(logMapper, atLeastOnce()).insert(any());
    verify(logMapper)
        .markCompleted(
            eq("t1"),
            anyString(),
            eq(zipPath.toString()),
            anyLong(),
            eq(response.sha256()),
            anyString(),
            any());
  }

  @Test
  @DisplayName("取证根为符号链接时拒绝写入并登记失败,不向链接目标泄露证据")
  void shouldRejectSymlinkRoot_whenExportingPrivateEvidence() throws IOException {
    assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
    Path target = Files.createDirectory(tempDir.resolve("target"));
    Path link = Files.createSymbolicLink(tempDir.resolve("link"), target);
    properties.setStorageDir(link.toString());
    assertThatThrownBy(() -> service.export(ForensicExportRequest.builder()
            .tenantId("t1")
            .bizDateFrom(LocalDate.of(2026, Month.MARCH, 15))
            .bizDateTo(LocalDate.of(2026, Month.MARCH, 15))
            .build()))
        .isInstanceOf(BizException.class);
    try (var files = Files.list(target)) {
      assertThat(files).isEmpty();
    }
    verify(logMapper).markFailed(eq("t1"), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("导出功能关闭时拒绝请求并提示功能未启用")
  void shouldRejectWhenDisabled() {
    properties.setEnabled(false);
    assertThatThrownBy(() -> service.export(ForensicExportRequest.builder()
            .tenantId("t1")
            .bizDateFrom(LocalDate.of(2026, Month.MARCH, 15))
            .bizDateTo(LocalDate.of(2026, Month.MARCH, 15))
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.forensic.disabled");
  }

  @Test
  @DisplayName("营业日区间起止颠倒时拒绝请求")
  void shouldRejectInvalidDateRange() {
    assertThatThrownBy(() -> service.export(ForensicExportRequest.builder()
            .tenantId("t1")
            .bizDateFrom(LocalDate.of(2026, Month.MARCH, 16))
            .bizDateTo(LocalDate.of(2026, Month.MARCH, 15))
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.forensic.invalid_date_range");
  }

  @Test
  @DisplayName("营业日区间超过配置的跨度上限时拒绝请求")
  void shouldRejectDateRangeBeyondConfiguredCap() {
    properties.setMaxDateRangeDays(2);
    assertThatThrownBy(() -> service.export(ForensicExportRequest.builder()
            .tenantId("t1")
            .bizDateFrom(LocalDate.of(2026, Month.MARCH, 15))
            .bizDateTo(LocalDate.of(2026, Month.MARCH, 17))
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.forensic.date_range_too_large");
  }

  @Test
  @DisplayName("租户或营业日缺失时拒绝请求")
  void shouldRejectMissingTenantOrDate() {
    assertThatThrownBy(() -> service.export(ForensicExportRequest.builder()
            .bizDateFrom(LocalDate.of(2026, Month.MARCH, 15))
            .bizDateTo(LocalDate.of(2026, Month.MARCH, 15))
            .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.forensic.invalid_argument");
  }

  @Test
  @DisplayName("查询过程抛异常时导出标记为失败, 并记录失败日志")
  void shouldMarkFailedWhenMapperBlowsUp() {
    when(jobInstanceMapper.selectForensicByBizDateRange(eq("t1"), any(), any(), isNull(), anyInt()))
        .thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(() -> service.export(ForensicExportRequest.builder()
            .tenantId("t1")
            .bizDateFrom(LocalDate.of(2026, Month.MARCH, 15))
            .bizDateTo(LocalDate.of(2026, Month.MARCH, 15))
            .build()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    verify(logMapper, atLeastOnce()).insert(any());
    verify(logMapper).markFailed(eq("t1"), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("指定任务清单时导出清单只包含该过滤条件")
  void shouldHonourJobCodesFilter() {
    when(jobInstanceMapper.selectForensicByBizDateRange(
            eq("t1"), any(), any(), eq(List.of("DAILY_PNL")), anyInt()))
        .thenReturn(List.of());
    when(auditMapper.selectByCalendarBizDate(eq("t1"), isNull(), any(), anyInt()))
        .thenReturn(List.of());

    ForensicExportResponse response = service.export(ForensicExportRequest.builder()
        .tenantId("t1")
        .bizDateFrom(LocalDate.of(2026, Month.MARCH, 15))
        .bizDateTo(LocalDate.of(2026, Month.MARCH, 15))
        .jobCodes(List.of("DAILY_PNL"))
        .build());

    assertThat(response.status()).isEqualTo("COMPLETED");
    assertManifestContainsJobFilter(Path.of(response.storagePath()), "DAILY_PNL");
  }

  private void assertManifestContainsJobFilter(Path zipPath, String expected) {
    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      ZipEntry manifestEntry = zip.getEntry("manifest.json");
      String content = new String(zip.getInputStream(manifestEntry).readAllBytes());
      assertThat(content).contains(expected);
    } catch (IOException e) {
      throw new AssertionError("manifest read failed", e);
    }
  }
}
