package io.github.pinpols.batch.orchestrator.application.service.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionSnapshot;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("上游就绪查询服务")
class ReadinessServiceTest {

  @Mock private AssetPartitionService assetPartitionService;

  @InjectMocks private ReadinessService readinessService;

  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 6, 20);

  @Test
  @DisplayName("上游该批次日 asset partition 有 EFFECTIVE 版本 → ready")
  void shouldBeReady_whenAssetPartitionEffective() {
    // arrange
    AssetPartitionSnapshot partition =
        new AssetPartitionSnapshot(
            "t1",
            "UP_JOB",
            BIZ_DATE,
            "2026-06-20",
            "job:UP_JOB:2026-06-20",
            "EFFECTIVE",
            3,
            900L,
            "INLINE_JSON",
            "{\"rows\":10}",
            "s3://bucket/key");
    when(assetPartitionService.findEffectiveJobPartition("t1", "UP_JOB", BIZ_DATE))
        .thenReturn(Optional.of(partition));

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isTrue();
    assertThat(result.reason()).isNull();
    assertThat(result.assetCode()).isEqualTo("UP_JOB");
    assertThat(result.bizDate()).isEqualTo(BIZ_DATE);
    assertThat(result.partitionKey()).isEqualTo("2026-06-20");
    assertThat(result.businessKey()).isEqualTo("job:UP_JOB:2026-06-20");
    assertThat(result.freshnessStatus()).isEqualTo("EFFECTIVE");
    assertThat(result.versionNo()).isEqualTo(3);
    assertThat(result.jobInstanceId()).isEqualTo(900L);
    assertThat(result.payloadStorage()).isEqualTo("INLINE_JSON");
    assertThat(result.payloadRef()).isEqualTo("s3://bucket/key");
  }

  @Test
  @DisplayName("无 EFFECTIVE 版本 → not ready(不消费 PENDING / DRY_RUN / 失败产物)")
  void shouldNotBeReady_whenAssetPartitionNotEffective() {
    // arrange
    when(assetPartitionService.findEffectiveJobPartition("t1", "UP_JOB", BIZ_DATE))
        .thenReturn(Optional.empty());

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("asset-partition-not-effective");
    assertThat(result.assetCode()).isEqualTo("UP_JOB");
    assertThat(result.bizDate()).isEqualTo(BIZ_DATE);
    assertThat(result.partitionKey()).isEqualTo("2026-06-20");
  }

  @Test
  @DisplayName("rerun 正在跑且新版本未 EFFECTIVE → not ready")
  void shouldNotBeReady_whenRerunNotEffective() {
    // arrange
    when(assetPartitionService.findEffectiveJobPartition("t1", "UP_JOB", BIZ_DATE))
        .thenReturn(Optional.empty());

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("asset-partition-not-effective");
  }

  @Test
  @DisplayName("该批次日没有 asset partition → not ready")
  void shouldNotBeReady_whenNoAssetPartition() {
    // arrange
    when(assetPartitionService.findEffectiveJobPartition("t1", "UP_JOB", BIZ_DATE))
        .thenReturn(Optional.empty());

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("asset-partition-not-effective");
  }

  @Test
  @DisplayName("入参非法 → 短路 not ready,不查库")
  void shouldShortCircuit_whenArgsInvalid() {
    // act
    ReadinessResult result = readinessService.checkJobReady("t1", " ", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("invalid-readiness-query");
    verify(assetPartitionService, never()).findEffectiveJobPartition(any(), any(), any());
  }

  @Test
  @DisplayName("null bizDate → 短路 not ready")
  void shouldShortCircuit_whenBizDateNull() {
    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", null);

    // assert
    assertThat(result.ready()).isFalse();
    verify(assetPartitionService, never()).findEffectiveJobPartition(eq("t1"), eq("UP_JOB"), any());
  }
}
