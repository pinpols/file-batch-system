package io.github.pinpols.batch.orchestrator.observability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class JobLifecycleMetricsRecorderTest {

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private JobLifecycleMetrics jobLifecycleMetrics;

  private JobLifecycleMetricsRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new JobLifecycleMetricsRecorder(
        jobInstanceMapper, jobDefinitionMapper, jobLifecycleMetrics);
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  @DisplayName("worker 终态指标复用实例快照，不在提交后重复查询 job_instance")
  void recordsFromExistingInstanceSnapshotWithoutReloadingInstance() {
    JobInstanceEntity instance = instance(100L, 200L, true);
    when(jobDefinitionMapper.selectById(200L))
        .thenReturn(JobDefinitionEntity.builder().jobType("ATOMIC").build());

    recorder.recordCompletionAfterCommit(
        "ta",
        instance,
        JobInstanceStatus.FAILED.code(),
        Instant.parse("2026-09-13T00:00:05Z"),
        "TECHNICAL");
    runAfterCommitCallbacks();

    verify(jobInstanceMapper, never()).selectById(any(), any());
    verify(jobLifecycleMetrics)
        .recordCompletion(
            eq("ta"),
            eq("ATOMIC"),
            eq(JobInstanceStatus.FAILED.code()),
            eq(true),
            eq(Duration.ofSeconds(5)));
    verify(jobLifecycleMetrics).recordFailure("ta", "ATOMIC", "TECHNICAL", true);
  }

  @Test
  @DisplayName("运维终态没有现成快照时仍在提交后读取权威实例")
  void reloadsInstanceForOperationalTerminalPath() {
    JobInstanceEntity instance = instance(100L, 200L, false);
    instance.setFailureClass("BUSINESS");
    when(jobInstanceMapper.selectById("ta", 100L)).thenReturn(instance);
    when(jobDefinitionMapper.selectById(200L))
        .thenReturn(JobDefinitionEntity.builder().jobType("PROCESS").build());

    recorder.recordCompletionAfterCommit(
        "ta", 100L, JobInstanceStatus.PARTIAL_FAILED.code(), Instant.parse("2026-09-13T00:00:05Z"));
    runAfterCommitCallbacks();

    verify(jobInstanceMapper).selectById("ta", 100L);
    verify(jobLifecycleMetrics)
        .recordCompletion(
            eq("ta"),
            eq("PROCESS"),
            eq(JobInstanceStatus.PARTIAL_FAILED.code()),
            eq(false),
            eq(Duration.ofSeconds(5)));
    verify(jobLifecycleMetrics).recordFailure("ta", "PROCESS", "BUSINESS", false);
  }

  private JobInstanceEntity instance(long id, long definitionId, boolean dryRun) {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(id);
    instance.setJobDefinitionId(definitionId);
    instance.setCreatedAt(Instant.parse("2026-09-13T00:00:00Z"));
    instance.setDryRun(dryRun);
    return instance;
  }

  private void runAfterCommitCallbacks() {
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
  }
}
