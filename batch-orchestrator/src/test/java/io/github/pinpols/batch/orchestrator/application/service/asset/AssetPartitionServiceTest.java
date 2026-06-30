package io.github.pinpols.batch.orchestrator.application.service.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.AssetPartitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssetPartitionServiceTest {

  private AssetPartitionMapper assetPartitionMapper;
  private ResultVersionQueryService resultVersionQueryService;
  private AssetPartitionService service;

  @BeforeEach
  void setUp() {
    assetPartitionMapper = mock(AssetPartitionMapper.class);
    resultVersionQueryService = mock(ResultVersionQueryService.class);
    service = new AssetPartitionService(assetPartitionMapper, resultVersionQueryService);
  }

  @Test
  void findEffectiveJobPartitionPrefersMaterializedPartition() {
    LocalDate bizDate = LocalDate.of(2026, 6, 30);
    AssetPartitionSnapshot snapshot =
        new AssetPartitionSnapshot(
            "t1",
            "JOB_A",
            bizDate,
            "2026-06-30",
            "job:JOB_A:2026-06-30",
            "EFFECTIVE",
            4,
            101L,
            "INLINE_JSON",
            "{\"rows\":20}",
            null);
    when(assetPartitionMapper.selectEffectiveJobPartition("t1", "JOB_A", "2026-06-30"))
        .thenReturn(snapshot);

    Optional<AssetPartitionSnapshot> found =
        service.findEffectiveJobPartition("t1", "JOB_A", bizDate);

    assertThat(found).contains(snapshot);
    verify(resultVersionQueryService, never()).findEffectiveByJob("t1", "JOB_A", bizDate);
  }

  @Test
  void findEffectiveJobPartitionFallsBackToResultVersionProjection() {
    LocalDate bizDate = LocalDate.of(2026, 6, 30);
    ResultVersionEntity version =
        ResultVersionEntity.builder()
            .tenantId("t1")
            .businessKey("job:JOB_A:2026-06-30")
            .versionNo(3)
            .jobInstanceId(100L)
            .status("EFFECTIVE")
            .payloadStorage("INLINE_JSON")
            .payloadJson("{\"rows\":10}")
            .build();
    when(assetPartitionMapper.selectEffectiveJobPartition("t1", "JOB_A", "2026-06-30"))
        .thenReturn(null);
    when(resultVersionQueryService.findEffectiveByJob("t1", "JOB_A", bizDate))
        .thenReturn(Optional.of(version));

    Optional<AssetPartitionSnapshot> found =
        service.findEffectiveJobPartition("t1", "JOB_A", bizDate);

    assertThat(found).isPresent();
    assertThat(found.get().assetCode()).isEqualTo("JOB_A");
    assertThat(found.get().partitionKey()).isEqualTo("2026-06-30");
    assertThat(found.get().freshnessStatus()).isEqualTo("EFFECTIVE");
    assertThat(found.get().versionNo()).isEqualTo(3);
    assertThat(found.get().jobInstanceId()).isEqualTo(100L);
  }

  @Test
  void findEffectiveJobPartitionReturnsEmptyForInvalidInput() {
    assertThat(service.findEffectiveJobPartition("t1", " ", LocalDate.of(2026, 6, 30))).isEmpty();
    assertThat(service.findEffectiveJobPartition("t1", "JOB_A", null)).isEmpty();
    verify(assetPartitionMapper, never()).selectEffectiveJobPartition(null, null, null);
    verify(resultVersionQueryService, never()).findEffectiveByJob(null, null, null);
  }

  @Test
  void isJobPartitionReadyRequiresEffectiveVersion() {
    LocalDate bizDate = LocalDate.of(2026, 6, 30);
    when(resultVersionQueryService.findEffectiveByJob("t1", "JOB_A", bizDate))
        .thenReturn(Optional.empty());

    assertThat(service.isJobPartitionReady("t1", "JOB_A", bizDate)).isFalse();
  }

  @Test
  void materializeEffectiveJobPartitionUpsertsDataAssetAndPartition() {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId("t1");
    instance.setId(100L);
    instance.setJobCode("JOB_A");
    instance.setBizDate(LocalDate.of(2026, 6, 30));
    Instant effectiveAt = Instant.parse("2026-06-30T01:02:03Z");
    ResultVersionEntity version =
        ResultVersionEntity.builder()
            .id(900L)
            .tenantId("t1")
            .businessKey("job:JOB_A:2026-06-30")
            .versionNo(5)
            .jobInstanceId(100L)
            .status("EFFECTIVE")
            .effectiveAt(effectiveAt)
            .payloadStorage("INLINE_JSON")
            .payloadRef("s3://bucket/key")
            .build();
    when(assetPartitionMapper.selectDataAssetId("t1", "JOB_A", "JOB")).thenReturn(10L);

    service.materializeEffectiveJobPartition(instance, version);

    verify(assetPartitionMapper).upsertDataAsset("t1", "JOB_A", "JOB", "JOB_A", "JOB_A");
    verify(assetPartitionMapper)
        .upsertEffectiveJobPartition(
            new AssetPartitionMaterializationCommand(
                "t1",
                10L,
                "JOB_A",
                "2026-06-30",
                LocalDate.of(2026, 6, 30),
                900L,
                "job:JOB_A:2026-06-30",
                100L,
                effectiveAt,
                "INLINE_JSON",
                "s3://bucket/key"));
  }

  @Test
  void materializeEffectiveJobPartitionIgnoresNonEffectiveVersion() {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId("t1");
    instance.setId(100L);
    instance.setJobCode("JOB_A");
    instance.setBizDate(LocalDate.of(2026, 6, 30));
    ResultVersionEntity version =
        ResultVersionEntity.builder()
            .tenantId("t1")
            .jobInstanceId(100L)
            .businessKey("job:JOB_A:2026-06-30")
            .status("PENDING")
            .build();

    service.materializeEffectiveJobPartition(instance, version);

    verify(assetPartitionMapper, never()).upsertDataAsset("t1", "JOB_A", "JOB", "JOB_A", "JOB_A");
  }
}
