package io.github.pinpols.batch.orchestrator.application.service.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssetPartitionServiceTest {

  private ResultVersionQueryService resultVersionQueryService;
  private AssetPartitionService service;

  @BeforeEach
  void setUp() {
    resultVersionQueryService = mock(ResultVersionQueryService.class);
    service = new AssetPartitionService(resultVersionQueryService);
  }

  @Test
  void findEffectiveJobPartitionProjectsResultVersionAsAssetPartition() {
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
    verify(resultVersionQueryService, never()).findEffectiveByJob(null, null, null);
  }

  @Test
  void isJobPartitionReadyRequiresEffectiveVersion() {
    LocalDate bizDate = LocalDate.of(2026, 6, 30);
    when(resultVersionQueryService.findEffectiveByJob("t1", "JOB_A", bizDate))
        .thenReturn(Optional.empty());

    assertThat(service.isJobPartitionReady("t1", "JOB_A", bizDate)).isFalse();
  }
}
