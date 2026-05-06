package com.example.batch.orchestrator.application.service.forensic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.BatchDayOperationAuditMapper;
import com.example.batch.orchestrator.mapper.ForensicExportLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForensicExportServiceTest {

  @TempDir Path tempDir;

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
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    service =
        new ForensicExportService(
            logMapper, jobInstanceMapper, auditMapper, properties, dateTimeSupport);
  }

  @Test
  void shouldProduceZipBundleWithManifestAndSha256() throws IOException {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(1L);
    instance.setTenantId("t1");
    instance.setJobCode("DAILY_PNL");
    instance.setBizDate(LocalDate.of(2026, 3, 15));
    instance.setInstanceStatus("SUCCESS");
    when(jobInstanceMapper.selectForensicByBizDateRange(eq("t1"), any(), any(), isNull(), anyInt()))
        .thenReturn(List.of(instance));
    when(auditMapper.selectByCalendarBizDate(eq("t1"), isNull(), any(), anyInt()))
        .thenReturn(List.of());

    ForensicExportResponse response =
        service.export(
            ForensicExportRequest.builder()
                .tenantId("t1")
                .bizDateFrom(LocalDate.of(2026, 3, 15))
                .bizDateTo(LocalDate.of(2026, 3, 15))
                .requestedBy("ops")
                .build());

    assertThat(response.exportId()).isNotBlank();
    assertThat(response.status()).isEqualTo("COMPLETED");
    assertThat(response.fileSizeBytes()).isPositive();
    assertThat(response.sha256()).hasSize(64); // SHA-256 hex

    Path zipPath = Path.of(response.storagePath());
    assertThat(zipPath).exists();

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
  void shouldRejectWhenDisabled() {
    properties.setEnabled(false);
    assertThatThrownBy(
            () ->
                service.export(
                    ForensicExportRequest.builder()
                        .tenantId("t1")
                        .bizDateFrom(LocalDate.of(2026, 3, 15))
                        .bizDateTo(LocalDate.of(2026, 3, 15))
                        .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.forensic.disabled");
  }

  @Test
  void shouldRejectInvalidDateRange() {
    assertThatThrownBy(
            () ->
                service.export(
                    ForensicExportRequest.builder()
                        .tenantId("t1")
                        .bizDateFrom(LocalDate.of(2026, 3, 16))
                        .bizDateTo(LocalDate.of(2026, 3, 15))
                        .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.forensic.invalid_date_range");
  }

  @Test
  void shouldRejectMissingTenantOrDate() {
    assertThatThrownBy(
            () ->
                service.export(
                    ForensicExportRequest.builder()
                        .bizDateFrom(LocalDate.of(2026, 3, 15))
                        .bizDateTo(LocalDate.of(2026, 3, 15))
                        .build()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.forensic.invalid_argument");
  }

  @Test
  void shouldMarkFailedWhenMapperBlowsUp() {
    when(jobInstanceMapper.selectForensicByBizDateRange(eq("t1"), any(), any(), isNull(), anyInt()))
        .thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(
            () ->
                service.export(
                    ForensicExportRequest.builder()
                        .tenantId("t1")
                        .bizDateFrom(LocalDate.of(2026, 3, 15))
                        .bizDateTo(LocalDate.of(2026, 3, 15))
                        .build()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    verify(logMapper, atLeastOnce()).insert(any());
    verify(logMapper).markFailed(eq("t1"), anyString(), anyString(), any());
  }

  @Test
  void shouldHonourJobCodesFilter() {
    when(jobInstanceMapper.selectForensicByBizDateRange(
            eq("t1"), any(), any(), eq(List.of("DAILY_PNL")), anyInt()))
        .thenReturn(List.of());
    when(auditMapper.selectByCalendarBizDate(eq("t1"), isNull(), any(), anyInt()))
        .thenReturn(List.of());

    ForensicExportResponse response =
        service.export(
            ForensicExportRequest.builder()
                .tenantId("t1")
                .bizDateFrom(LocalDate.of(2026, 3, 15))
                .bizDateTo(LocalDate.of(2026, 3, 15))
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
