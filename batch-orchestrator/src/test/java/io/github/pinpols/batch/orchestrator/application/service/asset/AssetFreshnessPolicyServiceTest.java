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
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.fixed(Instant.parse("2026-06-30T03:30:00Z"), ZoneId.of("UTC")),
            new BatchTimezoneProvider(new BatchTimezoneProperties()));
    service =
        new AssetFreshnessPolicyService(
            policyMapper, assetPartitionService, alertEventService, dateTimeSupport);
  }

  @Test
  void scanDuePoliciesEmitsMissingAlertWhenExpectedTimePassed() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 14_400, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady("t1", "JOB_A", LocalDate.of(2026, 6, 30)))
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
  void scanDuePoliciesEmitsStaleAlertAfterGraceWindow() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 60, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady("t1", "JOB_A", LocalDate.of(2026, 6, 30)))
        .thenReturn(false);

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isEqualTo(1);
    ArgumentCaptor<AlertEmitRequest> captor = ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(captor.capture());
    assertThat(captor.getValue().alertType()).isEqualTo("ASSET_FRESHNESS_STALE");
    assertThat(captor.getValue().severity()).isEqualTo("ERROR");
  }

  @Test
  void scanDuePoliciesSkipsBeforeExpectedTime() {
    AssetFreshnessPolicyRecord policy = policy("12:00", 3600, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isZero();
    verify(assetPartitionService, never()).isJobPartitionReady(any(), any(), any());
    verify(alertEventService, never()).emit(any());
  }

  @Test
  void scanDuePoliciesSkipsReadyPartition() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 60, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady("t1", "JOB_A", LocalDate.of(2026, 6, 30)))
        .thenReturn(true);

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isZero();
    verify(alertEventService, never()).emit(any());
  }

  @Test
  void scanDuePoliciesHonorsLookbackDays() {
    AssetFreshnessPolicyRecord policy = policy("09:00", 60, 2, "Asia/Shanghai", "ERROR");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady("t1", "JOB_A", LocalDate.of(2026, 6, 30)))
        .thenReturn(false);
    when(assetPartitionService.isJobPartitionReady("t1", "JOB_A", LocalDate.of(2026, 6, 29)))
        .thenReturn(false);

    int emitted = service.scanDuePolicies(10);

    assertThat(emitted).isEqualTo(2);
  }

  @Test
  void freshnessAlert_usesStableDedupKey_soRescanDoesNotStorm() {
    // arrange: same stale asset (same tenant/assetCode/bizDate), scan runs twice ~60s apart
    AssetFreshnessPolicyRecord policy = policy("09:00", 14_400, 1, "Asia/Shanghai", "WARN");
    when(policyMapper.selectEnabledPolicies(10)).thenReturn(List.of(policy));
    when(assetPartitionService.isJobPartitionReady("t1", "JOB_A", LocalDate.of(2026, 6, 30)))
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
  void scanDuePoliciesShortCircuitsInvalidLimit() {
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
