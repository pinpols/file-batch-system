package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PROCESS staging 清理必须在实例锁内确认 generation 已过期")
class ProcessStagingGenerationCleanupFenceTest {

  private static final String TENANT = "tenant_a";
  private static final long TASK_ID = 31L;
  private static final long INSTANCE_ID = 17L;
  private static final UUID OLD_GENERATION =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID CURRENT_GENERATION =
      UUID.fromString("20000000-0000-0000-0000-000000000001");

  @Mock
  private ProcessInvocationFenceMapper platformMapper;

  @Mock
  private ProcessStagingBusinessCleanupService businessCleanup;

  private ProcessStagingGenerationCleanupFence cleanupFence;

  @BeforeEach
  void setUp() {
    cleanupFence = new ProcessStagingGenerationCleanupFence(platformMapper, businessCleanup);
    when(platformMapper.selectTaskInstanceId(TENANT, TASK_ID)).thenReturn(INSTANCE_ID);
  }

  @Test
  void shouldDeleteOnlyAfterFenceAndFreshGenerationRead() {
    ProcessStagingGenerationCandidate candidate = candidate();
    when(platformMapper.selectTaskCurrentStagingGeneration(TENANT, TASK_ID))
        .thenReturn(CURRENT_GENERATION.toString());
    when(businessCleanup.sealAndDelete(candidate, 10)).thenReturn(4);

    assertThat(cleanupFence.reclaimIfStale(candidate, 10)).isEqualTo(4);

    InOrder order = inOrder(platformMapper, businessCleanup);
    order.verify(platformMapper).selectTaskInstanceId(TENANT, TASK_ID);
    order.verify(platformMapper).acquireInstanceFence(TENANT, INSTANCE_ID);
    order.verify(platformMapper).selectTaskCurrentStagingGeneration(TENANT, TASK_ID);
    order.verify(businessCleanup).sealAndDelete(candidate, 10);
  }

  @Test
  void shouldKeepCurrentGeneration() {
    ProcessStagingGenerationCandidate candidate = candidate();
    when(platformMapper.selectTaskCurrentStagingGeneration(TENANT, TASK_ID))
        .thenReturn(OLD_GENERATION.toString());

    assertThat(cleanupFence.reclaimIfStale(candidate, 10)).isZero();
    verify(businessCleanup, never()).sealAndDelete(candidate, 10);
  }

  @Test
  void shouldFailClosedWhenTaskHasNoCurrentGeneration() {
    ProcessStagingGenerationCandidate candidate = candidate();
    when(platformMapper.selectTaskCurrentStagingGeneration(TENANT, TASK_ID)).thenReturn(null);

    assertThat(cleanupFence.reclaimIfStale(candidate, 10)).isZero();
    verify(businessCleanup, never()).sealAndDelete(candidate, 10);
  }

  @Test
  void shouldFailClosedWhenTaskCannotBeResolved() {
    when(platformMapper.selectTaskInstanceId(TENANT, TASK_ID)).thenReturn(null);

    assertThat(cleanupFence.reclaimIfStale(candidate(), 10)).isZero();
    verify(platformMapper, never()).acquireInstanceFence(TENANT, INSTANCE_ID);
    verify(businessCleanup, never()).sealAndDelete(candidate(), 10);
  }

  private static ProcessStagingGenerationCandidate candidate() {
    return new ProcessStagingGenerationCandidate(
        TENANT, TASK_ID, OLD_GENERATION.toString(), "batch-1");
  }
}
