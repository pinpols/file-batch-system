package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("实例 pause/resume(ADR-044)")
class InstanceManagementPauseResumeTest {

  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobInstanceTerminalStatusApplicationService terminalStatusApplicationService;
  @Mock private RetryGovernanceService retryGovernanceService;

  @InjectMocks private InstanceManagementApplicationService service;

  private static JobInstanceEntity instance(String status) {
    JobInstanceEntity e = new JobInstanceEntity();
    e.setId(1L);
    e.setTenantId("t1");
    e.setInstanceNo("INST-1");
    e.setInstanceStatus(status);
    e.setVersion(3L);
    return e;
  }

  @Test
  @DisplayName("RUNNING 实例可暂停 → PAUSED,走 CAS lifecycle 更新")
  void shouldPause_whenRunning() {
    when(jobInstanceMapper.selectById("t1", 1L)).thenReturn(instance("RUNNING"));
    when(jobInstanceMapper.updateLifecycleStatus("t1", 1L, "PAUSED", 3L)).thenReturn(1);

    assertThat(service.pause("t1", 1L)).containsEntry("status", "PAUSED");
    verify(jobInstanceMapper).updateLifecycleStatus("t1", 1L, "PAUSED", 3L);
  }

  @Test
  @DisplayName("非 RUNNING 实例暂停 → STATE_CONFLICT,不写库")
  void shouldReject_whenPauseNonRunning() {
    when(jobInstanceMapper.selectById("t1", 1L)).thenReturn(instance("WAITING"));

    assertThatThrownBy(() -> service.pause("t1", 1L)).isInstanceOf(BizException.class);
    verify(jobInstanceMapper, never())
        .updateLifecycleStatus(eq("t1"), eq(1L), eq("PAUSED"), eq(3L));
  }

  @Test
  @DisplayName("PAUSED 实例可恢复 → RUNNING")
  void shouldResume_whenPaused() {
    when(jobInstanceMapper.selectById("t1", 1L)).thenReturn(instance("PAUSED"));
    when(jobInstanceMapper.updateLifecycleStatus("t1", 1L, "RUNNING", 3L)).thenReturn(1);

    assertThat(service.resume("t1", 1L)).containsEntry("status", "RUNNING");
  }

  @Test
  @DisplayName("非 PAUSED 实例恢复 → STATE_CONFLICT")
  void shouldReject_whenResumeNonPaused() {
    when(jobInstanceMapper.selectById("t1", 1L)).thenReturn(instance("RUNNING"));

    assertThatThrownBy(() -> service.resume("t1", 1L)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("CAS 落空(并发改动)→ STATE_CONFLICT")
  void shouldThrow_whenCasMissed() {
    when(jobInstanceMapper.selectById("t1", 1L)).thenReturn(instance("RUNNING"));
    when(jobInstanceMapper.updateLifecycleStatus("t1", 1L, "PAUSED", 3L)).thenReturn(0);

    assertThatThrownBy(() -> service.pause("t1", 1L)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("实例级 retry failed shards 只接受 FAILED 分片并返回统计")
  void shouldRetryFailedPartitionsForInstance() {
    when(jobInstanceMapper.selectById("t1", 1L)).thenReturn(instance("PARTIAL_FAILED"));
    JobPartitionEntity p1 = partition(10L, 0, 1L);
    JobPartitionEntity p2 = partition(11L, 2, 3L);
    when(jobPartitionMapper.selectByQuery(new JobPartitionQuery("t1", 1L, "FAILED", null)))
        .thenReturn(List.of(p1, p2));

    Map<String, Object> result = service.retryFailedPartitions("t1", 1L);

    assertThat(result)
        .containsEntry("requested", 2)
        .containsEntry("retried", 2)
        .containsEntry("conflicts", 0);
    assertThat(result.get("partitionIds")).isEqualTo(List.of(10L, 11L));
    verify(retryGovernanceService).retryPartition("t1", 10L, "t1:manual-partition-retry:10:1");
    verify(retryGovernanceService).retryPartition("t1", 11L, "t1:manual-partition-retry:11:3");
  }

  private static JobPartitionEntity partition(Long id, Integer retryCount, Long version) {
    JobPartitionEntity entity = new JobPartitionEntity();
    entity.setId(id);
    entity.setTenantId("t1");
    entity.setJobInstanceId(1L);
    entity.setPartitionStatus("FAILED");
    entity.setRetryCount(retryCount);
    entity.setVersion(version);
    return entity;
  }
}
