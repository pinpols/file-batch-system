package io.github.pinpols.batch.orchestrator.application.service.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.AssetFreshnessPolicyRecord;
import io.github.pinpols.batch.orchestrator.mapper.AssetFreshnessPolicyMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("资产新鲜度策略服务: 到期扫描, 缺失与滞后告警口径")
class AssetFreshnessPolicyServiceTest {

  private AssetFreshnessPolicyMapper policyMapper;
  private AssetPartitionService assetPartitionService;
  private AlertEventService alertEventService;
  private AssetFreshnessPolicyService service;

  @BeforeEach
  void setUp() {
    policyMapper = mock(AssetFreshnessPolicyMapper.class);
    assetPartitionService = mock(AssetPartitionService.class);
    alertEventService = mock(AlertEventService.class);
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.fixed(Instant.parse("2026-06-30T03:30:00Z"), ZoneId.of("UTC")),
        new BatchTimezoneProvider(new BatchTimezoneProperties()));
    service = new AssetFreshnessPolicyService(
        policyMapper, assetPartitionService, alertEventService, dateTimeSupport);
  }

  @Test
  @DisplayName("超过期望产出时间仍未就绪时发出资产缺失告警")
  void shouldEmitMissingAlert_whenExpectedTimePassed() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 14_400, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady(
            "t1", "JOB_A", LocalDate.of(2026, Month.JUNE, 30)))
        .thenReturn(false);

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isEqualTo(1);
    ArgumentCaptor<AlertEmitRequest> captor = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(captor.capture());
    assertThat(captor.getValue().alertType()).isEqualTo("ASSET_FRESHNESS_MISSING");
    assertThat(captor.getValue().severity()).isEqualTo("WARN");
    assertThat(captor.getValue().resourceKey()).isEqualTo("t1:JOB_A:2026-06-30");
  }

  @Test
  @DisplayName("超过宽限期仍未就绪时发出资产滞后告警")
  void shouldEmitStaleAlert_whenGraceWindowElapsed() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 60, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady(
            "t1", "JOB_A", LocalDate.of(2026, Month.JUNE, 30)))
        .thenReturn(false);

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isEqualTo(1);
    ArgumentCaptor<AlertEmitRequest> captor = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(captor.capture());
    assertThat(captor.getValue().alertType()).isEqualTo("ASSET_FRESHNESS_STALE");
    assertThat(captor.getValue().severity()).isEqualTo("ERROR");
  }

  @Test
  @DisplayName("未到期望产出时间时不查询就绪状态也不告警")
  void shouldSkip_whenBeforeExpectedTime() {
    AssetFreshnessPolicyRecord policy = policy("12:00", 3600, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isZero();
    verify(assetPartitionService, never()).isJobPartitionReady(any(), any(), any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("资产已就绪时不发出任何告警")
  void shouldSkip_whenPartitionReady() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 60, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady(
            "t1", "JOB_A", LocalDate.of(2026, Month.JUNE, 30)))
        .thenReturn(true);

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isZero();
    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("按配置的回看天数扫描对应营业日并逐日告警")
  void shouldScanLookbackDays_whenPolicyConfigured() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 60, 2, "Asia/Shanghai", "ERROR");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady(
            "t1", "JOB_A", LocalDate.of(2026, Month.JUNE, 30)))
        .thenReturn(false);
    when(assetPartitionService.isJobPartitionReady(
            "t1", "JOB_A", LocalDate.of(2026, Month.JUNE, 29)))
        .thenReturn(false);

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isEqualTo(2);
  }

  @Test
  @DisplayName("重复扫描同一滞后资产时告警去重键保持稳定, 不会产生重复告警")
  void freshnessAlert_usesStableDedupKey_soRescanDoesNotStorm() {
    // arrange: same stale asset (same tenant/assetCode/bizDate), scan runs twice ~60s apart
    AssetFreshnessPolicyRecord policy = policy("09:00", 14_400, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady(
            "t1", "JOB_A", LocalDate.of(2026, Month.JUNE, 30)))
        .thenReturn(false);

    // act: re-scan the same still-stale asset (mirrors the ~60s scheduled scan re-firing)
    int firstEmitted = service.scanDuePolicies(10);
    int secondEmitted = service.scanDuePolicies(10);

    // assert: each scan emits, but the dedup fingerprint is IDENTICAL so the downstream
    // UPSERT merges instead of creating duplicate alert rows.
    assertThat(firstEmitted).isEqualTo(1);
    assertThat(secondEmitted).isEqualTo(1);
    ArgumentCaptor<AlertEmitRequest> captor = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService, times(2)).emit(captor.capture());
    List<AlertEmitRequest> emissions = captor.getAllValues();
    String firstKey = emissions.get(0).resourceKey();
    String secondKey = emissions.get(1).resourceKey();
    assertThat(firstKey).isEqualTo(secondKey);
    // deterministic composite of the stable identifiers: tenant:assetCode:bizDate
    assertThat(firstKey)
        .isEqualTo("t1:JOB_A:2026-06-30")
        .contains("t1")
        .contains("JOB_A")
        .contains("2026-06-30");
    // alertType is likewise stable across re-scans (same dedup class)
    assertThat(emissions.get(0).alertType()).isEqualTo(emissions.get(1).alertType());
  }

  @Test
  @DisplayName("扫描条数上限非正时直接返回零, 不查询策略")
  void shouldReturnZero_whenScanLimitNotPositive() {
    assertThat(service.scanDuePolicies(0)).isZero();
    verify(policyMapper, never()).selectEnabledPolicies(0);
  }

  private static AssetFreshnessPolicyRecord policy(
      String expectedBy,
      int staleAfterSeconds,
      int lookbackDays,
      String timezone,
      String severity) {
    return new AssetFreshnessPolicyRecord(
        1L,
        "t1",
        "JOB_A",
        "JOB",
        LocalTime.parse(expectedBy),
        timezone,
        staleAfterSeconds,
        lookbackDays,
        severity,
        true);
  }
}
