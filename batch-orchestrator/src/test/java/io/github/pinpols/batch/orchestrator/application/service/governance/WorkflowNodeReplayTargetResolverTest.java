package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.governance.WorkflowNodeReplayTargetResolver.WorkflowNodeReplayTarget;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("节点 Replay 目标的精确身份与准入")
class WorkflowNodeReplayTargetResolverTest {

  private WorkflowRunMapper workflowRunMapper;
  private WorkflowNodeRunMapper workflowNodeRunMapper;
  private JobInstanceMapper jobInstanceMapper;
  private JobTaskMapper jobTaskMapper;
  private WorkflowNodeReplayTargetResolver resolver;

  @BeforeEach
  void setUp() {
    workflowRunMapper = mock(WorkflowRunMapper.class);
    workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    jobTaskMapper = mock(JobTaskMapper.class);
    resolver = new WorkflowNodeReplayTargetResolver(
        workflowRunMapper, workflowNodeRunMapper, jobInstanceMapper, jobTaskMapper);
  }

  @DisplayName("仅按精确运行与节点身份解析失败作业节点")
  @Test
  void shouldResolveFailedJobNode_whenRunAndNodeMatch() {
    WorkflowRunEntity sourceRun = workflowRun(71L, WorkflowRunStatus.FAILED.code());
    WorkflowNodeRunEntity sourceNode = workflowNode(
        82L, 71L, 93L, WorkflowNodeType.JOB.code(), WorkflowNodeRunStatus.FAILED.code());
    JobInstanceEntity sourceJob = jobInstance(93L, "IMPORT_CUSTOMERS", sourceRun.getBizDate());
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant-a", 71L))
        .thenReturn(sourceRun);
    when(workflowNodeRunMapper.selectByTenantRunAndIdForUpdate("tenant-a", 71L, 82L))
        .thenReturn(sourceNode);
    when(jobInstanceMapper.selectById("tenant-a", 93L)).thenReturn(sourceJob);
    when(jobTaskMapper.existsUnknownExternalOutcomeForJobInstance("tenant-a", 93L))
        .thenReturn(false);

    WorkflowNodeReplayTarget target = resolver.resolve("tenant-a", 71L, 82L);

    assertThat(target.sourceRun()).isSameAs(sourceRun);
    assertThat(target.sourceNodeRun()).isSameAs(sourceNode);
    assertThat(target.sourceJobInstance()).isSameAs(sourceJob);
  }

  @DisplayName("外部结果未知时在 Replay 准入前拒绝")
  @Test
  void shouldRejectAdmission_whenExternalOutcomeUnknown() {
    WorkflowRunEntity sourceRun = workflowRun(71L, WorkflowRunStatus.FAILED.code());
    WorkflowNodeRunEntity sourceNode = workflowNode(
        82L, 71L, 93L, WorkflowNodeType.JOB.code(), WorkflowNodeRunStatus.FAILED.code());
    JobInstanceEntity sourceJob = jobInstance(93L, "IMPORT_CUSTOMERS", sourceRun.getBizDate());
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant-a", 71L))
        .thenReturn(sourceRun);
    when(workflowNodeRunMapper.selectByTenantRunAndIdForUpdate("tenant-a", 71L, 82L))
        .thenReturn(sourceNode);
    when(jobInstanceMapper.selectById("tenant-a", 93L)).thenReturn(sourceJob);
    when(jobTaskMapper.existsUnknownExternalOutcomeForJobInstance("tenant-a", 93L))
        .thenReturn(true);

    assertThatThrownBy(() -> resolver.resolve("tenant-a", 71L, 82L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.workflow.replay_external_outcome_unknown");
  }

  @DisplayName("拒绝属于其它 WorkflowRun 的节点")
  @Test
  void shouldRejectNode_whenOwnedByAnotherWorkflowRun() {
    WorkflowRunEntity sourceRun = workflowRun(71L, WorkflowRunStatus.FAILED.code());
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant-a", 71L))
        .thenReturn(sourceRun);
    when(workflowNodeRunMapper.selectByTenantRunAndIdForUpdate("tenant-a", 71L, 82L))
        .thenReturn(null);

    assertThatThrownBy(() -> resolver.resolve("tenant-a", 71L, 82L))
        .isInstanceOf(BizException.class);
  }

  @DisplayName("拒绝已成功节点和控制节点")
  @Test
  void shouldRejectNode_whenSuccessfulOrControl() {
    WorkflowRunEntity sourceRun = workflowRun(71L, WorkflowRunStatus.FAILED.code());
    when(workflowRunMapper.selectByTenantAndIdForUpdate("tenant-a", 71L))
        .thenReturn(sourceRun);
    when(workflowNodeRunMapper.selectByTenantRunAndIdForUpdate("tenant-a", 71L, 82L))
        .thenReturn(workflowNode(82L, 71L, 93L, WorkflowNodeType.JOB.code(),
            WorkflowNodeRunStatus.SUCCESS.code()));

    assertThatThrownBy(() -> resolver.resolve("tenant-a", 71L, 82L))
        .isInstanceOf(BizException.class);

    when(workflowNodeRunMapper.selectByTenantRunAndIdForUpdate("tenant-a", 71L, 82L))
        .thenReturn(workflowNode(82L, 71L, null, WorkflowNodeType.END.code(),
            WorkflowNodeRunStatus.FAILED.code()));
    assertThatThrownBy(() -> resolver.resolve("tenant-a", 71L, 82L))
        .isInstanceOf(BizException.class);
  }

  private static WorkflowRunEntity workflowRun(Long id, String status) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(id);
    run.setTenantId("tenant-a");
    run.setBizDate(LocalDate.of(2026, 10, 20));
    run.setRunStatus(status);
    return run;
  }

  private static WorkflowNodeRunEntity workflowNode(
      Long id, Long workflowRunId, Long jobInstanceId, String nodeType, String nodeStatus) {
    WorkflowNodeRunEntity run = new WorkflowNodeRunEntity();
    run.setId(id);
    run.setWorkflowRunId(workflowRunId);
    run.setJobInstanceId(jobInstanceId);
    run.setNodeType(nodeType);
    run.setNodeStatus(nodeStatus);
    return run;
  }

  private static JobInstanceEntity jobInstance(Long id, String jobCode, LocalDate bizDate) {
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(id);
    instance.setTenantId("tenant-a");
    instance.setJobCode(jobCode);
    instance.setBizDate(bizDate);
    instance.setInstanceStatus(JobInstanceStatus.FAILED.code());
    return instance;
  }
}
