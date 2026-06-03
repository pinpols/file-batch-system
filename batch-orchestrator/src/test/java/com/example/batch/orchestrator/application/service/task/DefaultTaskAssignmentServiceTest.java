package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.config.ResourceSchedulerProperties;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.domain.param.AssignWorkerParam;
import com.example.batch.orchestrator.domain.param.RenewLeaseParam;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 守护 worker 认领 / 续租关键路径:
 *
 * <ul>
 *   <li>assignWorker: task 不存在 → null,不调 CAS
 *   <li>assignWorker: worker 离线 / 跨组 → 直接返 current(409 由 controller 转)
 *   <li>assignWorker: CAS 冲突 → 重读 DB
 *   <li>renewTaskLease: 多重前置 — task 不存在 / 非 RUNNING / worker 不匹配 / invocationId 缺失 全部拒
 *   <li>updateTaskStatus: 不存在返 null
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DefaultTaskAssignmentServiceTest {

  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobStepInstanceMapper jobStepInstanceMapper;
  @Mock private JobExecutionLogMapper jobExecutionLogMapper;
  @Mock private WorkerRegistryMapper workerRegistryMapper;
  @Mock private JobDefinitionMapper jobDefinitionMapper;

  private DefaultTaskAssignmentService service;
  private final PartitionLeaseProperties leaseProps = new PartitionLeaseProperties();
  private final ResourceSchedulerProperties resourceProps = new ResourceSchedulerProperties();

  @BeforeEach
  void setUp() {
    service =
        new DefaultTaskAssignmentService(
            jobTaskMapper,
            jobPartitionMapper,
            jobInstanceMapper,
            jobStepInstanceMapper,
            jobExecutionLogMapper,
            workerRegistryMapper,
            jobDefinitionMapper,
            leaseProps,
            resourceProps,
            new SimpleMeterRegistry());
  }

  // ===== assignWorker =====

  @Test
  @DisplayName("assignWorker: task 不存在 → null,不读 worker / 不调 CAS")
  void assignTaskMissingReturnsNull() {
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(null);

    assertThat(service.assignWorker("ta", 100L, "w1")).isNull();
    verify(workerRegistryMapper, never()).selectByTenantAndWorkerCode(anyString(), anyString());
    verify(jobTaskMapper, never()).assignWorker(any(AssignWorkerParam.class));
  }

  @Test
  @DisplayName("assignWorker: workerCode 为空 → 返 current,不读 worker")
  void assignBlankWorkercodeReturnsCurrentUnchanged() {
    JobTaskEntity task = task(100L, 1L, TaskStatus.READY.code());
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(task);

    JobTaskEntity result = service.assignWorker("ta", 100L, "  ");
    assertThat(result).isSameAs(task);
    verify(workerRegistryMapper, never()).selectByTenantAndWorkerCode(anyString(), anyString());
  }

  @Test
  @DisplayName("assignWorker: worker 不在线 → 返 current,不 CAS")
  void assignWorkerOfflineReturnsCurrent() {
    JobTaskEntity task = task(100L, 1L, TaskStatus.READY.code());
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(task);
    when(workerRegistryMapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(worker(WorkerRegistryStatus.OFFLINE.code(), "default"));

    JobTaskEntity result = service.assignWorker("ta", 100L, "w1");
    assertThat(result).isSameAs(task);
    verify(jobTaskMapper, never()).assignWorker(any(AssignWorkerParam.class));
  }

  @Test
  @DisplayName("assignWorker: worker DRAINING → 返 current,不 CAS")
  void assignWorkerDrainingReturnsCurrent() {
    JobTaskEntity task = task(100L, 1L, TaskStatus.READY.code());
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(task);
    when(workerRegistryMapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(worker(WorkerRegistryStatus.DRAINING.code(), "default"));

    JobTaskEntity result = service.assignWorker("ta", 100L, "w1");
    assertThat(result).isSameAs(task);
  }

  @Test
  @DisplayName("assignWorker: workerGroup 与 partition group 不匹配 → 返 current")
  void assignWorkerGroupMismatchReturnsCurrent() {
    JobTaskEntity task = task(100L, 1L, TaskStatus.READY.code());
    task.setJobPartitionId(50L);
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(task);
    when(workerRegistryMapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(worker(WorkerRegistryStatus.ONLINE.code(), "wrong-group"));
    JobPartitionEntity p = new JobPartitionEntity();
    p.setWorkerGroup("default");
    when(jobPartitionMapper.selectById(eq("ta"), eq(50L))).thenReturn(p);

    JobTaskEntity result = service.assignWorker("ta", 100L, "w1");
    assertThat(result).isSameAs(task);
    verify(jobTaskMapper, never()).assignWorker(any(AssignWorkerParam.class));
  }

  @Test
  @DisplayName("assignWorker: CAS 失败 → 重读 DB 返回最新状态")
  void assignCasConflictReturnsRefreshed() {
    JobTaskEntity initial = task(100L, 1L, TaskStatus.READY.code());
    JobTaskEntity refreshed = task(100L, 2L, TaskStatus.RUNNING.code());
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(initial, refreshed);
    when(workerRegistryMapper.selectByTenantAndWorkerCode(eq("ta"), eq("w1")))
        .thenReturn(worker(WorkerRegistryStatus.ONLINE.code(), null));
    when(jobTaskMapper.assignWorker(any(AssignWorkerParam.class))).thenReturn(0);

    JobTaskEntity result = service.assignWorker("ta", 100L, "w1");
    assertThat(result).isSameAs(refreshed);
  }

  // ===== renewTaskLease =====

  @Test
  @DisplayName("renewTaskLease: task 不存在 → false")
  void renewTaskMissing() {
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(null);
    assertThat(service.renewTaskLease("ta", 100L, "w1", "inv-1")).isFalse();
  }

  @Test
  @DisplayName("renewTaskLease: task 无 partition → false")
  void renewTaskWithoutPartition() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobPartitionId(null);
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    assertThat(service.renewTaskLease("ta", 100L, "w1", "inv-1")).isFalse();
  }

  @Test
  @DisplayName("renewTaskLease: task 非 RUNNING → false")
  void renewTaskNotRunning() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.READY.code());
    t.setJobPartitionId(50L);
    t.setAssignedWorkerCode("w1");
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    assertThat(service.renewTaskLease("ta", 100L, "w1", "inv-1")).isFalse();
  }

  @Test
  @DisplayName("renewTaskLease: workerCode 不匹配 → false(防止跨 worker 续他人租)")
  void renewWorkerMismatch() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobPartitionId(50L);
    t.setAssignedWorkerCode("w-other");
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    assertThat(service.renewTaskLease("ta", 100L, "w1", "inv-1")).isFalse();
  }

  @Test
  @DisplayName("renewTaskLease: invocationId 缺失 → false(R3-P1-10)")
  void renewMissingInvocation() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobPartitionId(50L);
    t.setAssignedWorkerCode("w1");
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    assertThat(service.renewTaskLease("ta", 100L, "w1", null)).isFalse();
    assertThat(service.renewTaskLease("ta", 100L, "w1", "")).isFalse();
    assertThat(service.renewTaskLease("ta", 100L, "w1", "  ")).isFalse();
    verify(jobPartitionMapper, never()).renewLease(any());
  }

  @Test
  @DisplayName("renewTaskLease: 全部前置通过 + mapper 命中 → true")
  void renewSucceedsWhenAllChecksPass() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobPartitionId(50L);
    t.setAssignedWorkerCode("w1");
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    when(jobPartitionMapper.renewLease(any(RenewLeaseParam.class))).thenReturn(1);

    assertThat(service.renewTaskLease("ta", 100L, "w1", "inv-1")).isTrue();
  }

  @Test
  @DisplayName("renewTaskLease: mapper 返 0(并发被抢走)→ false")
  void renewReturnsFalseWhenMapperMiss() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobPartitionId(50L);
    t.setAssignedWorkerCode("w1");
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    when(jobPartitionMapper.renewLease(any(RenewLeaseParam.class))).thenReturn(0);

    assertThat(service.renewTaskLease("ta", 100L, "w1", "inv-1")).isFalse();
  }

  // ===== recordHeartbeat (ORCH-P4-1) =====

  @Test
  @DisplayName("recordHeartbeat: 续租失败 → leaseRenewed=false,不写 details / 不读取消标记")
  void recordHeartbeatLeaseFails() {
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(null);

    var result = service.recordHeartbeat("ta", 100L, "w1", "inv-1", "{\"p\":1}");

    assertThat(result.leaseRenewed()).isFalse();
    assertThat(result.cancelRequested()).isFalse();
    verify(jobTaskMapper, never()).updateHeartbeatDetails(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("recordHeartbeat: 续租成功 + details 非空 → 写 details,回带 cancelRequested")
  void recordHeartbeatWritesDetailsAndReadsCancel() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobPartitionId(50L);
    t.setAssignedWorkerCode("w1");
    t.setCancelRequested(true);
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    when(jobPartitionMapper.renewLease(any(RenewLeaseParam.class))).thenReturn(1);

    var result = service.recordHeartbeat("ta", 100L, "w1", "inv-1", "{\"processed\":42}");

    assertThat(result.leaseRenewed()).isTrue();
    assertThat(result.cancelRequested()).isTrue();
    verify(jobTaskMapper).updateHeartbeatDetails("ta", 100L, "{\"processed\":42}");
  }

  @Test
  @DisplayName("recordHeartbeat: details 为 null → 不写 details,仅续租 + 读取消标记")
  void recordHeartbeatSkipsDetailsWhenNull() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobPartitionId(50L);
    t.setAssignedWorkerCode("w1");
    when(jobTaskMapper.selectById(eq("ta"), eq(100L))).thenReturn(t);
    when(jobPartitionMapper.renewLease(any(RenewLeaseParam.class))).thenReturn(1);

    var result = service.recordHeartbeat("ta", 100L, "w1", "inv-1", null);

    assertThat(result.leaseRenewed()).isTrue();
    assertThat(result.cancelRequested()).isFalse();
    verify(jobTaskMapper, never()).updateHeartbeatDetails(anyString(), anyLong(), anyString());
  }

  // ===== requestCancel (ORCH-P4-1) =====

  @Test
  @DisplayName("requestCancel: mapper 命中 RUNNING task → true")
  void requestCancelMarksRunningTask() {
    when(jobTaskMapper.requestCancel("ta", 100L)).thenReturn(1);
    assertThat(service.requestCancel("ta", 100L)).isTrue();
  }

  @Test
  @DisplayName("requestCancel: 非 RUNNING / 不存在 / 已请求 → false")
  void requestCancelNoMatch() {
    when(jobTaskMapper.requestCancel("ta", 100L)).thenReturn(0);
    assertThat(service.requestCancel("ta", 100L)).isFalse();
  }

  // ===== updateTaskStatus =====

  @Test
  @DisplayName("updateTaskStatus: task 不存在 → null,不写表")
  void updateTaskMissing() {
    when(jobTaskMapper.selectById(eq("ta"), anyLong())).thenReturn(null);

    assertThat(service.updateTaskStatus("ta", 100L, TaskStatus.SUCCESS.code(), null, null))
        .isNull();
    verify(jobTaskMapper, never()).updateStatus(any());
  }

  // ===== fixtures =====

  private JobTaskEntity task(Long id, Long version, String status) {
    JobTaskEntity t = new JobTaskEntity();
    t.setId(id);
    t.setVersion(version);
    t.setTaskStatus(status);
    t.setTenantId("ta");
    return t;
  }

  private WorkerRegistryEntity worker(String status, String group) {
    return new WorkerRegistryEntity(
        1L, "ta", "w1", group, null, null, status, Instant.now(), 0, 10, null, null);
  }
}
