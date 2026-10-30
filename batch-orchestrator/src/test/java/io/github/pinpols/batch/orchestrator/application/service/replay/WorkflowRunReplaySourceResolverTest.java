package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("整 Workflow 重放来源解析：冻结图与加密根参数")
class WorkflowRunReplaySourceResolverTest {

  private static final String TENANT = "replay-source-tenant";
  private static final long WORKFLOW_RUN_ID = 901L;
  private static final String PROTECTED_PARAMS = "protected-root-params";

  private WorkflowRunMapper workflowRunMapper;
  private WorkflowNodeRunMapper workflowNodeRunMapper;
  private SecretPayloadProtector secretPayloadProtector;
  private WorkflowRunReplaySourceResolver resolver;

  @BeforeEach
  void setUp() {
    workflowRunMapper = mock(WorkflowRunMapper.class);
    workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
    secretPayloadProtector = mock(SecretPayloadProtector.class);
    resolver = new WorkflowRunReplaySourceResolver(
        workflowRunMapper, workflowNodeRunMapper, secretPayloadProtector);
  }

  @Test
  @DisplayName("拒绝仍在运行中的来源且不读取节点参数")
  void shouldRejectNonTerminalSourceBeforeReadingStartPayload() {
    WorkflowRunEntity sourceRun = sourceRun("RUNNING");
    when(workflowRunMapper.selectById(TENANT, WORKFLOW_RUN_ID)).thenReturn(sourceRun);

    assertThatThrownBy(() -> resolver.resolve(TENANT, WORKFLOW_RUN_ID))
        .isInstanceOf(BizException.class);

    verifyNoInteractions(workflowNodeRunMapper, secretPayloadProtector);
  }

  @Test
  @DisplayName("缺少完整图快照时拒绝回退到当前 Workflow 定义")
  void shouldRejectSourceWithoutCompleteExecutionSnapshot() {
    WorkflowRunEntity sourceRun = sourceRun("FAILED");
    sourceRun.setExecutionSnapshot("{\"schemaVersion\":1}");
    when(workflowRunMapper.selectById(TENANT, WORKFLOW_RUN_ID)).thenReturn(sourceRun);

    assertThatThrownBy(() -> resolver.resolve(TENANT, WORKFLOW_RUN_ID))
        .isInstanceOf(BizException.class);

    verifyNoInteractions(workflowNodeRunMapper, secretPayloadProtector);
  }

  @Test
  @DisplayName("从来源 START 的加密载荷恢复根参数并保留来源图快照")
  void shouldResolveFrozenGraphAndProtectedRootParams() {
    WorkflowRunEntity sourceRun = sourceRun("FAILED");
    WorkflowNodeRunEntity startNode = new WorkflowNodeRunEntity();
    startNode.setWorkflowRunId(WORKFLOW_RUN_ID);
    startNode.setNodeCode("START");
    startNode.setNodeType("START");
    startNode.setNodeStatus("SUCCESS");
    startNode.setSourcePayloadProtected(PROTECTED_PARAMS);
    when(workflowRunMapper.selectById(TENANT, WORKFLOW_RUN_ID)).thenReturn(sourceRun);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(WORKFLOW_RUN_ID, "START"))
        .thenReturn(startNode);
    when(secretPayloadProtector.revealEncrypted(PROTECTED_PARAMS))
        .thenReturn("{\"businessDate\":\"2026-10-20\",\"mode\":\"full\"}");

    WorkflowRunReplaySourceResolver.Source source = resolver.resolve(TENANT, WORKFLOW_RUN_ID);

    assertThat(source.run()).isSameAs(sourceRun);
    assertThat(source.executionSnapshot().workflowDefinitionId()).isEqualTo(701L);
    assertThat(source.executionSnapshot().workflowVersion()).isEqualTo(4);
    assertThat(source.executionSnapshot().jobDefinition("IMPORT_NODE").version())
        .isEqualTo(2);
    assertThat(source.executionSnapshot().systemPipeline("IMPORT_NODE").code()).isEqualTo("IMPORT");
    assertThat(source.rootParams())
        .containsEntry("businessDate", "2026-10-20")
        .containsEntry("mode", "full");
    assertThat(source.toString()).doesNotContain("businessDate", "2026-10-20", "mode");
    assertThatThrownBy(() -> source.rootParams().put("another", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    verify(secretPayloadProtector).revealEncrypted(PROTECTED_PARAMS);
  }

  private static WorkflowRunEntity sourceRun(String status) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(WORKFLOW_RUN_ID);
    run.setTenantId(TENANT);
    run.setWorkflowDefinitionId(701L);
    run.setWorkflowVersionSnapshot(4);
    run.setRunStatus(status);
    run.setExecutionSnapshot(JsonUtils.toJson(Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        701,
        "workflowVersion",
        4,
        "workflowCalendarCode",
        "CALENDAR",
        "nodes",
        List.of(Map.of(
            "nodeCode", "IMPORT_NODE",
            "nodeType", "JOB",
            "tenantId", TENANT,
            "workflowDefinitionId", 701,
            "relatedJobCode", "LOAD_CUSTOMERS")),
        "edges",
        List.of(),
        "jobDefinitions",
        Map.of(
            "IMPORT_NODE",
            Map.of(
                "systemPipelineCode",
                "IMPORT",
                "systemPipelineVersion",
                1,
                "executionDefinition",
                Map.of(
                    "id",
                    711,
                    "tenantId",
                    TENANT,
                    "jobCode",
                    "LOAD_CUSTOMERS",
                    "jobType",
                    "IMPORT",
                    "version",
                    2),
                "jobDefinitionId",
                711,
                "jobCode",
                "LOAD_CUSTOMERS",
                "jobType",
                "IMPORT",
                "jobDefinitionVersion",
                2)))));
    return run;
  }
}
