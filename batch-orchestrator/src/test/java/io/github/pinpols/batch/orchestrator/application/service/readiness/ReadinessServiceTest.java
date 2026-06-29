package io.github.pinpols.batch.orchestrator.application.service.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import java.time.LocalDate;
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
    when(assetPartitionService.isJobPartitionReady("t1", "UP_JOB", BIZ_DATE)).thenReturn(true);

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isTrue();
    assertThat(result.reason()).isNull();
  }

  @Test
  @DisplayName("无 EFFECTIVE 版本 → not ready(不消费 PENDING / DRY_RUN / 失败产物)")
  void shouldNotBeReady_whenAssetPartitionNotEffective() {
    // arrange
    when(assetPartitionService.isJobPartitionReady("t1", "UP_JOB", BIZ_DATE)).thenReturn(false);

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("asset-partition-not-effective");
  }

  @Test
  @DisplayName("rerun 正在跑且新版本未 EFFECTIVE → not ready")
  void shouldNotBeReady_whenRerunNotEffective() {
    // arrange
    when(assetPartitionService.isJobPartitionReady("t1", "UP_JOB", BIZ_DATE)).thenReturn(false);

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
    when(assetPartitionService.isJobPartitionReady("t1", "UP_JOB", BIZ_DATE)).thenReturn(false);

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
    verify(assetPartitionService, never()).isJobPartitionReady(any(), any(), any());
  }

  @Test
  @DisplayName("null bizDate → 短路 not ready")
  void shouldShortCircuit_whenBizDateNull() {
    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", null);

    // assert
    assertThat(result.ready()).isFalse();
    verify(assetPartitionService, never()).isJobPartitionReady(eq("t1"), eq("UP_JOB"), any());
  }
}
