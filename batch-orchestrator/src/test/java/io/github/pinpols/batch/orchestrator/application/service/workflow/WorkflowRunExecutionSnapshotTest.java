package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workflow 运行执行快照的完整性与版本边界")
class WorkflowRunExecutionSnapshotTest {

  @Test
  @DisplayName("v2 快照同时固定 Workflow 图、Job 定义和对应系统 Pipeline 身份")
  void shouldReadPinnedExecutionDefinitionsAndPipelineIdentity() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setTenantId("tenant-a");
    run.setWorkflowDefinitionId(81L);
    run.setWorkflowVersionSnapshot(4);
    run.setExecutionSnapshot(JsonUtils.toJson(Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        81,
        "workflowVersion",
        4,
        "workflowCalendarCode",
        "WF_CALENDAR",
        "nodes",
        List.of(Map.of(
            "nodeCode", "IMPORT_NODE",
            "nodeType", "JOB",
            "tenantId", "tenant-a",
            "workflowDefinitionId", 81,
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
                    "id", 101,
                    "tenantId", "tenant-a",
                    "jobCode", "LOAD_CUSTOMERS",
                    "jobType", "IMPORT",
                    "version", 2),
                "jobDefinitionId",
                101,
                "jobCode",
                "LOAD_CUSTOMERS",
                "jobType",
                "IMPORT",
                "jobDefinitionVersion",
                2)))));

    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(run);
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    snapshot.copySystemPipelineIdentity("IMPORT_NODE", payload);

    assertThat(snapshot.node("IMPORT_NODE").getRelatedJobCode()).isEqualTo("LOAD_CUSTOMERS");
    assertThat(snapshot.workflowCalendarCode()).isEqualTo("WF_CALENDAR");
    assertThat(snapshot.jobDefinition("IMPORT_NODE").version()).isEqualTo(2);
    assertThat(payload).containsEntry("systemPipelineCode", "IMPORT");
    assertThat(payload).containsEntry("systemPipelineVersion", 1);
  }

  @Test
  @DisplayName("v1 仅有 Launch 身份快照时走旧定义读取兼容路径")
  void shouldNotTreatLegacyIdentityOnlySnapshotAsGraphSnapshot() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setExecutionSnapshot("{\"schemaVersion\":1,\"workflow\":{\"id\":1}}");

    assertThat(WorkflowRunExecutionSnapshot.from(run)).isNull();
  }

  @Test
  @DisplayName("v2 快照缺少 JOB 配置时拒绝回退到可变定义")
  void shouldRejectJobNodeWithoutPinnedDefinition() {
    WorkflowRunEntity run = validRun(Map.of());

    assertThatThrownBy(() -> WorkflowRunExecutionSnapshot.from(run))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("v2 快照中的 Job 类型与系统 Pipeline 身份不一致时拒绝恢复")
  void shouldRejectMismatchedSystemPipelineIdentity() {
    WorkflowRunEntity run = validRun(Map.of(
        "IMPORT_NODE",
        Map.of(
            "systemPipelineCode",
            "EXPORT",
            "systemPipelineVersion",
            1,
            "executionDefinition",
            Map.of(
                "id", 101,
                "tenantId", "tenant-a",
                "jobCode", "LOAD_CUSTOMERS",
                "jobType", "IMPORT",
                "version", 2),
            "jobDefinitionId",
            101,
            "jobCode",
            "LOAD_CUSTOMERS",
            "jobType",
            "IMPORT",
            "jobDefinitionVersion",
            2)));

    assertThatThrownBy(() -> WorkflowRunExecutionSnapshot.from(run))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("v2 快照不能包含指向其他 Workflow 的节点")
  void shouldRejectNodeFromAnotherWorkflow() {
    WorkflowRunEntity run = validRun(Map.of(
        "IMPORT_NODE",
        Map.of(
            "systemPipelineCode",
            "IMPORT",
            "systemPipelineVersion",
            1,
            "executionDefinition",
            Map.of(
                "id", 101,
                "tenantId", "tenant-a",
                "jobCode", "LOAD_CUSTOMERS",
                "jobType", "IMPORT",
                "version", 2),
            "jobDefinitionId",
            101,
            "jobCode",
            "LOAD_CUSTOMERS",
            "jobType",
            "IMPORT",
            "jobDefinitionVersion",
            2)));
    Map<String, Object> raw = JsonUtils.fromJson(
        run.getExecutionSnapshot(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    raw.put(
        "nodes",
        List.of(Map.of(
            "nodeCode", "IMPORT_NODE",
            "nodeType", "JOB",
            "tenantId", "tenant-a",
            "workflowDefinitionId", 99,
            "relatedJobCode", "LOAD_CUSTOMERS")));
    run.setExecutionSnapshot(JsonUtils.toJson(raw));

    assertThatThrownBy(() -> WorkflowRunExecutionSnapshot.from(run))
        .isInstanceOf(BizException.class);
  }

  private WorkflowRunEntity validRun(Map<String, Object> jobDefinitions) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setTenantId("tenant-a");
    run.setWorkflowDefinitionId(81L);
    run.setWorkflowVersionSnapshot(4);
    run.setExecutionSnapshot(JsonUtils.toJson(Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        81,
        "workflowVersion",
        4,
        "nodes",
        List.of(Map.of(
            "nodeCode", "IMPORT_NODE",
            "nodeType", "JOB",
            "tenantId", "tenant-a",
            "workflowDefinitionId", 81,
            "relatedJobCode", "LOAD_CUSTOMERS")),
        "edges",
        List.of(),
        "jobDefinitions",
        jobDefinitions)));
    return run;
  }
}
