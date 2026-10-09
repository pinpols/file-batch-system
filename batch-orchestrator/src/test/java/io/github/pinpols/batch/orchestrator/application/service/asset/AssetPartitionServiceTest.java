package io.github.pinpols.batch.orchestrator.application.service.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.AssetPartitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("资产分区服务: 生效分区查询, 就绪判定与物化口径")
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
  @DisplayName("存在已物化分区时优先返回该快照, 不再回退查询生效版本")
  void shouldPreferMaterializedPartition_whenQueryingEffective() {
    LocalDate bizDate = LocalDate.of(2026, Month.JUNE, 30);
    AssetPartitionSnapshot snapshot = new AssetPartitionSnapshot(
        "t1",
        "JOB_A",
        bizDate,
        "2026-06-30",
        "job:JOB_A:2026-06-30",
        "EFFECTIVE",
        4,
        101L,
        Instant.parse("2026-06-30T02:00:00Z"),
        "INLINE_JSON",
        "{\"rows\":20}",
        null);
    when(assetPartitionMapper.selectEffectiveJobPartition("t1", "JOB_A", "2026-06-30"))
        .thenReturn(snapshot);
    when(resultVersionQueryService.findLatestByJob("t1", "JOB_A", bizDate))
        .thenReturn(Optional.of(ResultVersionEntity.builder()
            .tenantId("t1")
            .businessKey("job:JOB_A:2026-06-30")
            .versionNo(4)
            .jobInstanceId(101L)
            .status("EFFECTIVE")
            .build()));

    Optional<AssetPartitionSnapshot> found =
        service.findEffectiveJobPartition("t1", "JOB_A", bizDate);

    assertThat(found).contains(snapshot);
    verify(resultVersionQueryService, never()).findEffectiveByJob("t1", "JOB_A", bizDate);
  }

  @Test
  @DisplayName("已物化分区落后于最新生效版本时忽略快照, 返回最新版本投影")
  void shouldIgnoreStaleMaterialized_whenLatestEffectiveIsNewer() {
    LocalDate bizDate = LocalDate.of(2026, Month.JUNE, 30);
    AssetPartitionSnapshot stale = new AssetPartitionSnapshot(
        "t1",
        "JOB_A",
        bizDate,
        "2026-06-30",
        "job:JOB_A:2026-06-30",
        "EFFECTIVE",
        4,
        101L,
        Instant.parse("2026-06-30T02:00:00Z"),
        "INLINE_JSON",
        "{\"rows\":20}",
        null);
    ResultVersionEntity latest = ResultVersionEntity.builder()
        .tenantId("t1")
        .businessKey("job:JOB_A:2026-06-30")
        .versionNo(5)
        .jobInstanceId(102L)
        .status("EFFECTIVE")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"rows\":30}")
        .build();
    when(resultVersionQueryService.findLatestByJob("t1", "JOB_A", bizDate))
        .thenReturn(Optional.of(latest));
    when(assetPartitionMapper.selectEffectiveJobPartition("t1", "JOB_A", "2026-06-30"))
        .thenReturn(stale);

    Optional<AssetPartitionSnapshot> found =
        service.findEffectiveJobPartition("t1", "JOB_A", bizDate);

    assertThat(found).isPresent();
    assertThat(found.get().versionNo()).isEqualTo(5);
    assertThat(found.get().jobInstanceId()).isEqualTo(102L);
    assertThat(found.get().payloadJson()).isEqualTo("{\"rows\":30}");
  }

  @Test
  @DisplayName("没有已物化分区时回退为按最新生效版本构造投影")
  void shouldFallBackToVersionProjection_whenNoMaterializedPartition() {
    LocalDate bizDate = LocalDate.of(2026, Month.JUNE, 30);
    ResultVersionEntity version = ResultVersionEntity.builder()
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
    when(resultVersionQueryService.findLatestByJob("t1", "JOB_A", bizDate))
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
  @DisplayName("资产编码为空或营业日缺失时返回空, 且不访问存储")
  void shouldReturnEmpty_whenInputInvalid() {
    assertThat(service.findEffectiveJobPartition("t1", " ", LocalDate.of(2026, Month.JUNE, 30)))
        .isEmpty();
    assertThat(service.findEffectiveJobPartition("t1", "JOB_A", null)).isEmpty();
    verify(assetPartitionMapper, never()).selectEffectiveJobPartition(null, null, null);
    verify(resultVersionQueryService, never()).findLatestByJob(null, null, null);
  }

  @Test
  @DisplayName("最新产出不是生效版本时判定为未就绪")
  void shouldNotBeReady_whenLatestAttemptNotEffective() {
    LocalDate bizDate = LocalDate.of(2026, Month.JUNE, 30);
    when(resultVersionQueryService.findLatestByJob("t1", "JOB_A", bizDate))
        .thenReturn(Optional.of(ResultVersionEntity.builder()
            .tenantId("t1")
            .businessKey("job:JOB_A:2026-06-30")
            .versionNo(4)
            .status("PENDING")
            .build()));

    assertThat(service.isJobPartitionReady("t1", "JOB_A", bizDate)).isFalse();
  }

  @Test
  @DisplayName("最新产出处于待生效状态时屏蔽旧的生效版本, 返回空")
  void shouldReturnEmpty_whenLatestAttemptStillPending() {
    LocalDate bizDate = LocalDate.of(2026, Month.JUNE, 30);
    when(resultVersionQueryService.findLatestByJob("t1", "JOB_A", bizDate))
        .thenReturn(Optional.of(ResultVersionEntity.builder()
            .tenantId("t1")
            .businessKey("job:JOB_A:2026-06-30")
            .versionNo(5)
            .status("PENDING")
            .build()));

    Optional<AssetPartitionSnapshot> found =
        service.findEffectiveJobPartition("t1", "JOB_A", bizDate);

    assertThat(found).isEmpty();
    verify(assetPartitionMapper, never()).selectEffectiveJobPartition("t1", "JOB_A", "2026-06-30");
  }

  @Test
  @DisplayName("物化生效分区时补齐数据资产并写入分区快照")
  void shouldUpsertDataAssetAndPartition_whenMaterializing() {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId("t1");
    instance.setId(100L);
    instance.setJobCode("JOB_A");
    instance.setBizDate(LocalDate.of(2026, Month.JUNE, 30));
    Instant effectiveAt = Instant.parse("2026-06-30T01:02:03Z");
    ResultVersionEntity version = ResultVersionEntity.builder()
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
    service.materializeEffectiveJobPartition(instance, version);

    verify(assetPartitionMapper, times(1)).upsertDataAsset("t1", "JOB_A", "JOB", "JOB_A", "JOB_A");
    verify(assetPartitionMapper, times(1)).selectDataAssetId("t1", "JOB_A", "JOB");
    verify(assetPartitionMapper, times(2))
        .upsertEffectiveJobPartition(new AssetPartitionMaterializationCommand(
            "t1",
            10L,
            "JOB_A",
            "2026-06-30",
            LocalDate.of(2026, Month.JUNE, 30),
            900L,
            "job:JOB_A:2026-06-30",
            100L,
            effectiveAt,
            "INLINE_JSON",
            "s3://bucket/key"));
  }

  @Test
  @DisplayName("版本不是生效状态时不物化数据资产与分区")
  void shouldSkipMaterialization_whenVersionNotEffective() {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId("t1");
    instance.setId(100L);
    instance.setJobCode("JOB_A");
    instance.setBizDate(LocalDate.of(2026, Month.JUNE, 30));
    ResultVersionEntity version = ResultVersionEntity.builder()
        .tenantId("t1")
        .jobInstanceId(100L)
        .businessKey("job:JOB_A:2026-06-30")
        .status("PENDING")
        .build();

    service.materializeEffectiveJobPartition(instance, version);

    verify(assetPartitionMapper, never()).upsertDataAsset("t1", "JOB_A", "JOB", "JOB_A", "JOB_A");
  }
}
