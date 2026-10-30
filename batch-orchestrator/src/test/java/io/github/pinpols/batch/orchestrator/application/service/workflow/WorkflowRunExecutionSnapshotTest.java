package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.LinkedHashMap;
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
    Map<String, Object> payload = new LinkedHashMap<>();
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
  @DisplayName("整图重放快照按节点固定 Worker 能力与操作身份")
  void shouldReadReplayGuardForEachEnabledJobNode() {
    Map<String, Object> definition = jobDefinitionMetadata();
    definition.put("replayExecutionGuard", replayGuardMetadata());
    WorkflowRunEntity run = validRun(Map.of("IMPORT_NODE", definition));
    Map<String, Object> raw =
        JsonUtils.fromJson(run.getExecutionSnapshot(), new TypeReference<>() {});
    raw.put("replayOperationId", 701L);
    run.setExecutionSnapshot(JsonUtils.toJson(raw));

    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(run);

    assertThat(snapshot.replayOperationId()).isEqualTo(701L);
    assertThat(snapshot.replayExecutionGuard("IMPORT_NODE").replayOperationId()).isEqualTo(701L);
    assertThat(snapshot.replayExecutionGuard("IMPORT_NODE").declaration().pluginId())
        .isEqualTo("jdbc_mapped");
  }

  @Test
  @DisplayName("整图重放快照缺少启用 JOB 的 Worker 能力时失败关闭")
  void shouldRejectReplaySnapshotWithoutEnabledJobReplayGuard() {
    WorkflowRunEntity run = validRun(Map.of("IMPORT_NODE", jobDefinitionMetadata()));
    Map<String, Object> raw =
        JsonUtils.fromJson(run.getExecutionSnapshot(), new TypeReference<>() {});
    raw.put("replayOperationId", 701L);
    run.setExecutionSnapshot(JsonUtils.toJson(raw));

    assertThatThrownBy(() -> WorkflowRunExecutionSnapshot.from(run))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("整图回放不能缺少已审批绑定的文件模板快照")
  void shouldRejectReplaySnapshotForFileJobWithoutTemplateBinding() {
    Map<String, Object> definition = jobDefinitionMetadata();
    definition.put("replayExecutionGuard", replayGuardMetadata());
    definition.remove("frozenFileTemplateSnapshots");
    WorkflowRunEntity run = validRun(Map.of("IMPORT_NODE", definition));
    Map<String, Object> raw =
        JsonUtils.fromJson(run.getExecutionSnapshot(), new TypeReference<>() {});
    raw.put("replayOperationId", 701L);
    run.setExecutionSnapshot(JsonUtils.toJson(raw));

    assertThatThrownBy(() -> WorkflowRunExecutionSnapshot.from(run))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("整图重放快照保留每个跨日依赖别名对应的精确结果版本")
  void shouldReadPinnedCrossDayResultVersionIds() {
    Map<String, Object> definition = jobDefinitionMetadata();
    definition.put("replayExecutionGuard", replayGuardMetadata());
    WorkflowRunEntity run = validRun(Map.of("IMPORT_NODE", definition));
    Map<String, Object> raw = executionSnapshotMap(run);
    Map<String, Object> node = firstNode(raw);
    node.put(
        "crossDayDependencies",
        "[{\"alias\":\"previous\",\"jobCode\":\"LOAD_PREVIOUS\",\"bizDateOffset\":-1}]");
    raw.put("nodes", List.of(node));
    raw.put("replayOperationId", 701L);
    raw.put(
        "crossDayDependencyResultVersionIdsByNode",
        Map.of("IMPORT_NODE", Map.of("previous", List.of(812L))));
    run.setExecutionSnapshot(JsonUtils.toJson(raw));

    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(run);

    assertThat(snapshot.crossDayDependencyResultVersionIds("IMPORT_NODE"))
        .containsEntry("previous", List.of(812L));
  }

  @Test
  @DisplayName("整图重放快照缺少跨日依赖结果版本时失败关闭")
  void shouldRejectReplaySnapshotWithoutPinnedCrossDayResultVersionIds() {
    Map<String, Object> definition = jobDefinitionMetadata();
    definition.put("replayExecutionGuard", replayGuardMetadata());
    WorkflowRunEntity run = validRun(Map.of("IMPORT_NODE", definition));
    Map<String, Object> raw = executionSnapshotMap(run);
    Map<String, Object> node = firstNode(raw);
    node.put(
        "crossDayDependencies",
        "[{\"alias\":\"previous\",\"jobCode\":\"LOAD_PREVIOUS\",\"bizDateOffset\":-1}]");
    raw.put("nodes", List.of(node));
    raw.put("replayOperationId", 701L);
    run.setExecutionSnapshot(JsonUtils.toJson(raw));

    assertThatThrownBy(() -> WorkflowRunExecutionSnapshot.from(run))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("整图重放快照拒绝与声明别名不匹配的跨日结果版本")
  void shouldRejectReplaySnapshotWithMismatchedCrossDayAlias() {
    Map<String, Object> definition = jobDefinitionMetadata();
    definition.put("replayExecutionGuard", replayGuardMetadata());
    WorkflowRunEntity run = validRun(Map.of("IMPORT_NODE", definition));
    Map<String, Object> raw = executionSnapshotMap(run);
    Map<String, Object> node = firstNode(raw);
    node.put(
        "crossDayDependencies",
        "[{\"alias\":\"previous\",\"jobCode\":\"LOAD_PREVIOUS\",\"bizDateOffset\":-1}]");
    raw.put("nodes", List.of(node));
    raw.put("replayOperationId", 701L);
    raw.put(
        "crossDayDependencyResultVersionIdsByNode",
        Map.of("IMPORT_NODE", Map.of("different", List.of(812L))));
    run.setExecutionSnapshot(JsonUtils.toJson(raw));

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
    Map<String, Object> raw =
        JsonUtils.fromJson(run.getExecutionSnapshot(), new TypeReference<>() {});
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

  private Map<String, Object> executionSnapshotMap(WorkflowRunEntity run) {
    return JsonUtils.fromJson(
        run.getExecutionSnapshot(), new TypeReference<Map<String, Object>>() {});
  }

  private Map<String, Object> firstNode(Map<String, Object> executionSnapshot) {
    Object firstNode = ((List<?>) executionSnapshot.get("nodes")).getFirst();
    return JsonUtils.fromJson(
        JsonUtils.toJson(firstNode), new TypeReference<Map<String, Object>>() {});
  }

  private Map<String, Object> jobDefinitionMetadata() {
    Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
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
        2));
    metadata.put("frozenFileTemplateSnapshots", Map.of("TPL_IMPORT", "sealed-template"));
    return metadata;
  }

  private Map<String, Object> replayGuardMetadata() {
    return Map.of(
        "replayOperationId",
        701L,
        "declaration",
        Map.of(
            "pluginId",
            "jdbc_mapped",
            "pluginVersion",
            "1.2.3",
            "externalEffectCategories",
            List.of("DATABASE_WRITE"),
            "idempotencyCapability",
            "IDEMPOTENT_BY_UNIQUE_CONSTRAINT",
            "idempotencyKeyScope",
            "tenant+business-key",
            "recoveryProtocolId",
            "database-unique-constraint",
            "contractVersion",
            1));
  }
}
