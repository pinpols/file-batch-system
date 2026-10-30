package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("PROCESS 过期代际清理分页")
class ProcessStagingOrphanCleanerPaginationTest {
  @Test
  @DisplayName("候选集在清理中缩小时仍扫描到后续代际")
  @SuppressWarnings("unchecked")
  void shouldNotSkipLaterCandidatesWhenEarlierGenerationIsReclaimed() {
    ProcessStagingMapper mapper = mock(ProcessStagingMapper.class);
    ProcessInvocationFenceMapper platformMapper = mock(ProcessInvocationFenceMapper.class);
    ProcessStagingBusinessCleanupService business =
        mock(ProcessStagingBusinessCleanupService.class);
    ProcessStagingGenerationCleanupFence fence = mock(ProcessStagingGenerationCleanupFence.class);
    ObjectProvider<MeterRegistry> registry = mock(ObjectProvider.class);
    ProcessStagingCleanupProperties properties = new ProcessStagingCleanupProperties();
    properties.setDeletionEnabled(true);
    properties.setBatchSize(100);
    List<ProcessStagingGenerationCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      candidates.add(new ProcessStagingGenerationCandidate(
          "t1",
          (long) i,
          new UUID(0, i + 1).toString(),
          "batch",
          Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)));
    }
    when(platformMapper.listCleanupTenants()).thenReturn(List.of("t1"));
    when(business.listExpiredAfter(
            eq("t1"), anyInt(), anyInt(), nullable(Instant.class), nullable(String.class)))
        .thenAnswer(invocation -> {
          int limit = invocation.getArgument(2);
          Instant after = invocation.getArgument(3);
          return candidates.stream()
              .filter(candidate -> after == null || candidate.getCreatedAt().isAfter(after))
              .limit(limit)
              .toList();
        });
    when(fence.reclaimIfStale(any(), anyInt())).thenAnswer(invocation -> {
      ProcessStagingGenerationCandidate candidate = invocation.getArgument(0);
      if (candidate.getTaskId() == 0L || candidate.getTaskId() == 100L) {
        candidates.remove(candidate);
        return 1;
      }
      return 0;
    });
    ProcessStagingOrphanCleaner cleaner = new ProcessStagingOrphanCleaner(
        mapper, properties, registry, platformMapper, business, fence);

    assertThat(cleaner.cleanOnce()).isEqualTo(2);
    assertThat(candidates).noneMatch(candidate -> candidate.getTaskId() == 100L);
  }
}
