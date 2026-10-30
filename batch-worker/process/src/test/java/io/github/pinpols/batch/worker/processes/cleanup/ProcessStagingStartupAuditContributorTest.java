package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.infrastructure.WorkerStartupAuditContributor.WorkerStartupAuditResult;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("处理暂存区启动审计:自动删除暂停且存在超过保留期的暂存行时给出不健康告警")
class ProcessStagingStartupAuditContributorTest {

  @Test
  @DisplayName("调度开启但自动删除暂停且存在超期行时,审计判不健康并暴露删除开关状态")
  void shouldWarn_whenAutomaticDeletionPausedAndRowsPastRetentionExist() {
    ProcessInvocationFenceMapper platformMapper = mock(ProcessInvocationFenceMapper.class);
    ProcessStagingBusinessCleanupService businessCleanup =
        mock(ProcessStagingBusinessCleanupService.class);
    ProcessStagingGenerationCleanupFence cleanupFence =
        mock(ProcessStagingGenerationCleanupFence.class);
    ProcessStagingGenerationCandidate candidate = new ProcessStagingGenerationCandidate(
        "tenant-a", 9L, UUID.randomUUID().toString(), "batch-1");
    when(platformMapper.listCleanupTenants()).thenReturn(List.of("tenant-a"));
    when(businessCleanup.countExpiredGenerations("tenant-a", 24)).thenReturn(1L);
    when(businessCleanup.listExpired("tenant-a", 24, 5000, 0)).thenReturn(List.of(candidate));
    when(cleanupFence.observeIfStale(candidate, 24))
        .thenReturn(new ProcessStagingGenerationObservation(3L, Instant.now().minusSeconds(7200)));
    ProcessStagingCleanupProperties properties = new ProcessStagingCleanupProperties();
    properties.setEnabled(true);
    ProcessStagingStartupAuditContributor contributor = new ProcessStagingStartupAuditContributor(
        platformMapper, businessCleanup, cleanupFence, properties);

    WorkerStartupAuditResult result = contributor.audit();

    assertThat(result.healthy()).isFalse();
    assertThat(result.details()).containsEntry("orphanRowsPastRetention", 3L);
    assertThat(result.details()).containsEntry("deletionEnabled", false);
  }
}
