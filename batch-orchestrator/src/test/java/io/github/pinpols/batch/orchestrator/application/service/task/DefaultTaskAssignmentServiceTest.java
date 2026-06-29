package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.orchestrator.config.PartitionLeaseProperties;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.AssignWorkerParam;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseParam;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
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

  // ===== loadEffectiveConfig =====

  @Test
  @DisplayName("loadEffectiveConfig: 从 partition input_snapshot 暴露 typed 分区计划契约")
  void loadEffectiveConfigExposesPartitionPlanContract() {
    JobTaskEntity t = task(100L, 1L, TaskStatus.RUNNING.code());
    t.setJobInstanceId(10L);
    t.setJobPartitionId(50L);
    t.setTaskType("IMPORT");
    t.setTaskSeq(1);
    t.setTaskPayload("{}");
    when(jobTaskMapper.selectById("ta", 100L)).thenReturn(t);

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(10L);
    instance.setTenantId("ta");
    instance.setJobDefinitionId(20L);
    instance.setInstanceNo("INST-1");
    instance.setJobCode("JOB_A");
    instance.setExpectedPartitionCount(4);
    instance.setTraceId("trace-1");
    when(jobInstanceMapper.selectById("ta", 10L)).thenReturn(instance);

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setId(50L);
    partition.setPartitionNo(3);
    partition.setPartitionKey("JOB_A:2026-06-30:3");
    partition.setBusinessKey("biz-key");
    partition.setIdempotencyKey("idem-key");
    partition.setCurrentInvocationId("inv-1");
    partition.setInputSnapshot(
        """
        {"partitionPlanVersion":1,"shardIndex":2,"shardTotal":4,
         "rangeStartInclusive":500,"rangeEndExclusive":750,"expectedRows":250}
        """);
    when(jobPartitionMapper.selectById("ta", 50L)).thenReturn(partition);
    when(jobDefinitionMapper.selectById(20L))
        .thenReturn(
            JobDefinitionEntity.builder()
                .id(20L)
                .executionMode("FULL")
                .retryPolicy("NONE")
                .build());

    EffectiveTaskConfig config = service.loadEffectiveConfig("ta", 100L);

    assertThat(config.partitionPlanVersion()).isEqualTo(1);
    assertThat(config.shardIndex()).isEqualTo(2);
    assertThat(config.shardTotal()).isEqualTo(4);
    assertThat(config.rangeStartInclusive()).isEqualTo(500L);
    assertThat(config.rangeEndExclusive()).isEqualTo(750L);
    assertThat(config.expectedRows()).isEqualTo(250L);
    assertThat(config.partitionNo()).isEqualTo(3);
    assertThat(config.partitionCount()).isEqualTo(4);
    assertThat(config.partitionInvocationId()).isEqualTo("inv-1");
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
