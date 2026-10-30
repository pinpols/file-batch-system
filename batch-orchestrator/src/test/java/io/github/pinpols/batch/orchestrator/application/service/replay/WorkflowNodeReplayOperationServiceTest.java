package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkflowNodeReplayTargetResolver;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkflowNodeReplayTargetResolver.WorkflowNodeReplayTarget;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("失败 Workflow JOB 节点 replay operation 审批前预览")
class WorkflowNodeReplayOperationServiceTest {

  private WorkflowNodeReplayTargetResolver targetResolver;
  private ReplayOperationMapper operationMapper;
  private ReplayOperationPreviewTokenMapper tokenMapper;
  private BatchDateTimeSupport dateTimeSupport;
  private WorkflowNodeReplayOperationService service;
  private WorkflowNodeReplayTarget source;

  @BeforeEach
  void setUp() {
    targetResolver = mock(WorkflowNodeReplayTargetResolver.class);
    operationMapper = mock(ReplayOperationMapper.class);
    tokenMapper = mock(ReplayOperationPreviewTokenMapper.class);
    dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    service = new WorkflowNodeReplayOperationService(
        targetResolver, operationMapper, tokenMapper, dateTimeSupport);
    source = source();
    when(targetResolver.resolve("tenant-a", 41L, 73L)).thenReturn(source);
  }

  @Test
  @DisplayName("预览冻结来源身份、业务日和策略，只持久化凭证摘要")
  void shouldFreezeSourceAndIssueHashOnlyPreviewToken() {
    AtomicReference<ReplayOperationPreviewTokenEntity> insertedToken = new AtomicReference<>();
    when(tokenMapper.insert(any())).thenAnswer(invocation -> {
      insertedToken.set(invocation.getArgument(0));
      return 1;
    });

    WorkflowNodeReplayPreviewResponse preview = service.preview(command());

    assertThat(preview.sourceWorkflowRunId()).isEqualTo(41L);
    assertThat(preview.sourceWorkflowNodeRunId()).isEqualTo(73L);
    assertThat(preview.sourceJobInstanceId()).isEqualTo(92L);
    assertThat(preview.jobCode()).isEqualTo("JOB_A");
    assertThat(preview.bizDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(preview.resultPolicy()).isEqualTo("CREATE_NEW_VERSION");
    assertThat(preview.configVersionPolicy()).isEqualTo("USE_ORIGINAL_CONFIG");
    assertThat(preview.warnings())
        .containsExactly(
            "Only the selected failed JOB node is replayed; its external side effects may run again.");
    assertThat(insertedToken.get().tokenHash()).isNotEqualTo(preview.previewToken());
    assertThat(insertedToken.get().operationKey())
        .isEqualTo(preview.operationKey().toString());
    assertThat(insertedToken.get().snapshotPayload()).contains("sourceExecutionSnapshot");
  }

  @Test
  @DisplayName("受理时源快照变化拒绝创建待审批父操作且不消费凭证")
  void shouldRejectAdmissionWhenFrozenSourceChanges() {
    AtomicReference<ReplayOperationPreviewTokenEntity> insertedToken = new AtomicReference<>();
    when(tokenMapper.insert(any())).thenAnswer(invocation -> {
      insertedToken.set(invocation.getArgument(0));
      return 1;
    });
    WorkflowNodeReplayPreviewResponse preview = service.preview(command());
    when(tokenMapper.selectForUpdate(eq("tenant-a"), any())).thenReturn(insertedToken.get());
    source.sourceRun().setExecutionSnapshot("changed-execution-snapshot");

    assertThatThrownBy(() -> service.submitForApproval(command(preview)))
        .hasMessageContaining("replay_preview_changed");

    verify(tokenMapper, never()).consume(eq("tenant-a"), any(), any());
    verify(operationMapper, never()).insert(any());
  }

  @Test
  @DisplayName("精确来源标记外部结果未知时，不签发可重放预览")
  void shouldRejectUnknownExternalOutcomeBeforeIssuingPreview() {
    source.sourceNodeRun().setErrorCode("EXTERNAL_OUTCOME_UNKNOWN");

    assertThatThrownBy(() -> service.preview(command()))
        .hasMessageContaining("replay_external_outcome_unknown");

    verify(tokenMapper, never()).insert(any());
  }

  @Test
  @DisplayName("提交同一预览凭证创建待审批 Replay Operation 并原子关联")
  void shouldCreatePendingApprovalOperationAndConsumePreviewOnce() {
    AtomicReference<ReplayOperationPreviewTokenEntity> insertedToken = new AtomicReference<>();
    AtomicReference<ReplayOperationEntity> insertedOperation = new AtomicReference<>();
    when(tokenMapper.insert(any())).thenAnswer(invocation -> {
      insertedToken.set(invocation.getArgument(0));
      return 1;
    });
    WorkflowNodeReplayPreviewResponse preview = service.preview(command());
    when(tokenMapper.selectForUpdate(eq("tenant-a"), any())).thenReturn(insertedToken.get());
    when(tokenMapper.consume(eq("tenant-a"), any(), any())).thenReturn(1);
    when(operationMapper.insert(any())).thenAnswer(invocation -> {
      insertedOperation.set(invocation.getArgument(0));
      return 1;
    });
    when(operationMapper.selectByIdentity(
            eq("tenant-a"), eq(preview.operationKey().toString())))
        .thenAnswer(invocation -> insertedOperation.get() == null
            ? null
            : insertedOperation.get().toBuilder().id(101L).build());
    when(tokenMapper.linkOperation(eq("tenant-a"), any(), eq(101L))).thenReturn(1);

    ReplayOperationEntity persisted = service.submitForApproval(command(preview));

    assertThat(persisted.id()).isEqualTo(101L);
    assertThat(persisted.status()).isEqualTo("PENDING_APPROVAL");
    assertThat(persisted.requestedBy()).isEqualTo("operator-a");
    assertThat(persisted.sourceWorkflowRunId()).isEqualTo(41L);
    assertThat(persisted.sourceWorkflowNodeRunId()).isEqualTo(73L);
    assertThat(persisted.sourceJobInstanceId()).isEqualTo(92L);
    ArgumentCaptor<String> tokenHash = ArgumentCaptor.forClass(String.class);
    verify(tokenMapper).consume(eq("tenant-a"), tokenHash.capture(), any());
    assertThat(tokenHash.getValue()).isEqualTo(insertedToken.get().tokenHash());
    verify(tokenMapper).linkOperation("tenant-a", insertedToken.get().tokenHash(), 101L);
  }

  private WorkflowNodeReplayCommand command() {
    return WorkflowNodeReplayCommand.builder()
        .tenantId("tenant-a")
        .workflowRunId(41L)
        .workflowNodeRunId(73L)
        .reason("replay after fixing source")
        .requestedBy("operator-a")
        .resultPolicy(null)
        .configVersionPolicy(null)
        .configVersion(null)
        .traceId("trace-a")
        .operationKey(null)
        .previewToken(null)
        .build();
  }

  private WorkflowNodeReplayCommand command(WorkflowNodeReplayPreviewResponse preview) {
    return WorkflowNodeReplayCommand.builder()
        .tenantId("tenant-a")
        .workflowRunId(41L)
        .workflowNodeRunId(73L)
        .reason("replay after fixing source")
        .requestedBy("operator-a")
        .resultPolicy(null)
        .configVersionPolicy(null)
        .configVersion(null)
        .traceId("trace-b")
        .operationKey(preview.operationKey())
        .previewToken(preview.previewToken())
        .build();
  }

  private static WorkflowNodeReplayTarget source() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(41L);
    run.setTenantId("tenant-a");
    run.setBizDate(LocalDate.of(2026, 1, 15));
    run.setRunStatus(WorkflowRunStatus.FAILED.code());
    run.setExecutionSnapshot("{\"version\":1}");
    run.setDryRun(false);

    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setId(73L);
    nodeRun.setWorkflowRunId(41L);
    nodeRun.setNodeCode("IMPORT_CUSTOMERS");
    nodeRun.setNodeType(WorkflowNodeType.JOB.code());
    nodeRun.setRunSeq(1);
    nodeRun.setNodeStatus(WorkflowNodeRunStatus.FAILED.code());
    nodeRun.setJobInstanceId(92L);

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(92L);
    instance.setTenantId("tenant-a");
    instance.setJobCode("JOB_A");
    instance.setBizDate(LocalDate.of(2026, 1, 15));
    instance.setInstanceStatus(JobInstanceStatus.FAILED.code());
    instance.setJobDefinitionVersion(7);
    instance.setParamsSnapshot("{\"source\":\"frozen\"}");
    instance.setDryRun(false);
    return new WorkflowNodeReplayTarget(run, nodeRun, instance);
  }
}
