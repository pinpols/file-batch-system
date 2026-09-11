package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.param.MarkInstanceRunningParam;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateMachine;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateTransition;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.observability.LaunchPhaseMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultPartitionDispatchServiceTest {

  private SchedulePlanBuilder schedulePlanBuilder;
  private ResourceScheduler resourceScheduler;
  private PartitionLifecycleService partitionLifecycleService;
  private TaskExecutionService taskExecutionService;
  private TaskDispatchOutboxService taskDispatchOutboxService;
  private JobInstanceMapper jobInstanceMapper;
  private DefaultPartitionDispatchService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    schedulePlanBuilder = mock(SchedulePlanBuilder.class);
    resourceScheduler = mock(ResourceScheduler.class);
    partitionLifecycleService = mock(PartitionLifecycleService.class);
    taskExecutionService = mock(TaskExecutionService.class);
    taskDispatchOutboxService = mock(TaskDispatchOutboxService.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    StateMachine<Object> stateMachine = mock(StateMachine.class);
    when(stateMachine.transition(any(), any()))
        .thenReturn(new StateTransition(
            JobInstanceStatus.CREATED.code(), "START", JobInstanceStatus.RUNNING.code()));
    service = new DefaultPartitionDispatchService(
        schedulePlanBuilder,
        resourceScheduler,
        partitionLifecycleService,
        taskExecutionService,
        taskDispatchOutboxService,
        stateMachine,
        mock(WorkflowNodeDispatchService.class),
        jobInstanceMapper,
        mock(WorkflowRunMapper.class),
        new LaunchPhaseMetrics(new SimpleMeterRegistry()));
  }

  @Test
  void dispatch_marksInstanceWithKnownVersionWithoutPrecedingReload() {
    JobInstanceEntity jobInstance = dispatchablePlan(1L);
    when(jobInstanceMapper.markRunning(any())).thenReturn(1);

    service.dispatch(dispatchContext(jobInstance));

    ArgumentCaptor<MarkInstanceRunningParam> running =
        ArgumentCaptor.forClass(MarkInstanceRunningParam.class);
    verify(jobInstanceMapper).markRunning(running.capture());
    verify(jobInstanceMapper, never()).selectById(any(), any());
    assertThat(running.getValue().getExpectedVersion()).isEqualTo(1L);
    assertThat(running.getValue().getInstanceStatus()).isEqualTo(JobInstanceStatus.RUNNING.code());
    assertThat(jobInstance.getVersion()).isEqualTo(2L);
  }

  @Test
  void dispatch_rollsBackOnVersionConflictWithoutOverwritingConcurrentState() {
    JobInstanceEntity jobInstance = dispatchablePlan(3L);
    when(jobInstanceMapper.markRunning(any())).thenReturn(0);

    assertThatThrownBy(() -> service.dispatch(dispatchContext(jobInstance)))
        .isInstanceOf(BizException.class);

    verify(jobInstanceMapper, never()).selectById(any(), any());
    verify(jobInstanceMapper).markRunning(any());
    assertThat(jobInstance.getVersion()).isEqualTo(3L);
  }

  @Test
  void dispatch_insertsNewDispatchableRowsAsReadyWithoutRedundantPromotionUpdates() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("ta");
    plan.setDefaultWorkerType("ATOMIC");
    SchedulePlan.PartitionPlan partitionPlan = new SchedulePlan.PartitionPlan();
    partitionPlan.setPartitionNo(1);
    plan.setPartitions(List.of(partitionPlan));
    ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
    decision.setDispatchable(true);
    decision.setPartitionStatus(PartitionStatus.CREATED.code());
    decision.setTaskStatus(io.github.pinpols.batch.common.enums.TaskStatus.CREATED.code());
    when(schedulePlanBuilder.build(any())).thenReturn(plan);
    when(resourceScheduler.schedule(any())).thenReturn(decision);

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setId(20L);
    partition.setTenantId("ta");
    partition.setPartitionNo(1);
    partition.setPartitionStatus(PartitionStatus.READY.code());
    partition.setIdempotencyKey("10:1");
    partition.setVersion(0L);
    when(partitionLifecycleService.createPartitions(plan, 10L, PartitionStatus.READY.code()))
        .thenReturn(List.of(partition));
    when(jobInstanceMapper.markRunning(any())).thenReturn(1);
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(10L);
    jobInstance.setTenantId("ta");
    jobInstance.setInstanceStatus(JobInstanceStatus.CREATED.code());
    jobInstance.setVersion(0L);

    service.dispatch(dispatchContext(jobInstance));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity>> tasks =
        ArgumentCaptor.forClass(List.class);
    verify(taskExecutionService).createTasks(tasks.capture());
    assertThat(tasks.getValue())
        .singleElement()
        .satisfies(task -> assertThat(task.getTaskStatus())
            .isEqualTo(io.github.pinpols.batch.common.enums.TaskStatus.READY.code()));
    assertThat(partitionPlan.getPartitionStatus()).isEqualTo(PartitionStatus.READY.code());
    assertThat(jobInstance.getExpectedPartitionCount()).isEqualTo(1);
    verify(partitionLifecycleService, never()).releaseForDispatch(any(), any(), any(), any());
    verify(taskDispatchOutboxService).writeDispatchEvent(any(), any(), any(), any(), any());
  }

  private JobInstanceEntity dispatchablePlan(long version) {
    SchedulePlan plan = new SchedulePlan();
    plan.setPartitions(List.of());
    ResourceSchedulingDecision decision = new ResourceSchedulingDecision();
    decision.setDispatchable(true);
    decision.setPartitionStatus(PartitionStatus.CREATED.code());
    when(schedulePlanBuilder.build(any())).thenReturn(plan);
    when(resourceScheduler.schedule(any())).thenReturn(decision);
    when(partitionLifecycleService.createPartitions(plan, 10L, PartitionStatus.CREATED.code()))
        .thenReturn(List.of());

    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(10L);
    jobInstance.setTenantId("ta");
    jobInstance.setInstanceStatus(JobInstanceStatus.CREATED.code());
    jobInstance.setVersion(version);
    return jobInstance;
  }

  private PartitionDispatchService.DispatchContext dispatchContext(JobInstanceEntity jobInstance) {
    LaunchRequest request = new LaunchRequest(
        "ta",
        "IMPORT_ORDERS",
        LocalDate.of(2026, Month.SEPTEMBER, 11),
        TriggerType.MANUAL,
        "request-1",
        "trace-1",
        Map.of());
    return PartitionDispatchService.DispatchContext.of(
        new PartitionDispatchService.DispatchRequest(request, Map.of(), "trace-1"),
        new PartitionDispatchService.DispatchRuntime(
            jobInstance, null, List.of(), Instant.parse("2026-09-11T00:00:00Z")));
  }

  @Test
  void enrichPayload_addsDerivedFieldsWhenMissing() {
    LaunchRequest request = new LaunchRequest(
        "tc",
        "TC_EXPORT_RISK_ALERT",
        LocalDate.of(2026, Month.APRIL, 22),
        TriggerType.SCHEDULED,
        "req-1",
        "trace-1",
        Map.of());
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setBatchNo("2026-04-22");

    Map<String, Object> payload =
        DefaultPartitionDispatchService.enrichPayload(request, jobInstance, Map.of());

    assertThat(payload)
        .containsEntry("batchNo", "2026-04-22")
        .containsEntry("bizDate", "2026-04-22")
        .containsEntry("jobCode", "TC_EXPORT_RISK_ALERT");
  }

  @Test
  void enrichPayload_keepsExplicitFieldsUntouched() {
    LaunchRequest request = new LaunchRequest(
        "tc",
        "TC_EXPORT_RISK_ALERT",
        LocalDate.of(2026, Month.APRIL, 22),
        TriggerType.SCHEDULED,
        "req-2",
        "trace-2",
        Map.of("batchNo", "REQ-BATCH"));
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setBatchNo("INSTANCE-BATCH");

    Map<String, Object> payload = DefaultPartitionDispatchService.enrichPayload(
        request,
        jobInstance,
        Map.of("batchNo", "REQ-BATCH", "bizDate", "2026-04-01", "jobCode", "CUSTOM_JOB"));

    assertThat(payload)
        .containsEntry("batchNo", "REQ-BATCH")
        .containsEntry("bizDate", "2026-04-01")
        .containsEntry("jobCode", "CUSTOM_JOB");
  }

  @Test
  void enrichBundleBinding_injectsBindingForBundlePartition() {
    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setSourceFileId(42L);
    partition.setTemplateCode("RISK_IMPORT_V2");
    partition.setTargetRef("biz.risk_alert");
    Map<String, Object> payload = new HashMap<>();

    DefaultPartitionDispatchService.enrichBundleBinding(payload, partition);

    // P1-1/P1-3 防御:source_file_id/target_ref 落带 bundle 前缀的键,不与泛化 sourceFileId/targetRef 撞;
    // templateCode 是 payload 真字段保持原名。
    assertThat(payload)
        .containsEntry("bundleSourceFileId", 42L)
        .containsEntry("templateCode", "RISK_IMPORT_V2")
        .containsEntry("bundleTargetRef", "biz.risk_alert")
        .doesNotContainKeys("sourceFileId", "targetRef");
  }

  @Test
  void enrichBundleBinding_leavesNormalPartitionPayloadUnchanged() {
    // 普通(非束)partition 三根绑定列均为空，payload 不得新增任何字段——保证存量导入零影响。
    JobPartitionEntity partition = new JobPartitionEntity();
    Map<String, Object> payload = new HashMap<>();

    DefaultPartitionDispatchService.enrichBundleBinding(payload, partition);

    assertThat(payload).doesNotContainKeys("bundleSourceFileId", "templateCode", "bundleTargetRef");
  }

  @Test
  void enrichBundleBinding_nullPartitionIsNoOp() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("batchNo", "2026-04-22");

    DefaultPartitionDispatchService.enrichBundleBinding(payload, null);

    assertThat(payload).hasSize(1).containsEntry("batchNo", "2026-04-22");
  }
}
