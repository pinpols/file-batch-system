package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RERUN 配置选择：按原快照、最新定义和指定历史版本解析")
class RerunJobDefinitionResolverTest {

  private static final String TENANT = "tenant-a";
  private static final String JOB_CODE = "IMPORT_A";
  private static final Long JOB_DEFINITION_ID = 77L;
  private static final Long SOURCE_INSTANCE_ID = 900L;

  private JobInstanceMapper jobInstances;
  private WorkflowRunMapper workflowRuns;
  private RerunJobDefinitionResolver resolver;
  private JobInstanceEntity source;
  private JobDefinitionEntity latest;

  @BeforeEach
  void setUp() {
    jobInstances = mock(JobInstanceMapper.class);
    workflowRuns = mock(WorkflowRunMapper.class);
    resolver = new RerunJobDefinitionResolver(jobInstances, workflowRuns);
    source = new JobInstanceEntity();
    source.setId(SOURCE_INSTANCE_ID);
    source.setTenantId(TENANT);
    source.setJobCode(JOB_CODE);
    source.setJobDefinitionId(JOB_DEFINITION_ID);
    source.setJobDefinitionVersion(1);
    when(jobInstances.selectById(TENANT, SOURCE_INSTANCE_ID)).thenReturn(source);
    latest = definition(2, "queue-v2", "timeout-v2");
  }

  @Test
  @DisplayName("USE_ORIGINAL_CONFIG 从原 WorkflowRun 快照恢复完整定义")
  void shouldResolveOriginalDefinitionFromSourceWorkflowSnapshot() {
    when(workflowRuns.selectByNodeJobInstanceId(TENANT, SOURCE_INSTANCE_ID))
        .thenReturn(runWithDefinition(1, "queue-v1", "timeout-v1"));

    JobDefinitionEntity selected = resolver.resolve(request(Map.of()), JOB_CODE, latest);

    assertDefinition(selected, 1, "queue-v1", "timeout-v1");
    assertThat(selected.defaultParams()).containsEntry("source", "v1");
  }

  @Test
  @DisplayName("USE_LATEST_CONFIG 选择当前定义但仍校验源实例租户与作业身份")
  void shouldResolveLatestDefinitionAfterValidatingSourceInstance() {
    JobDefinitionEntity selected = resolver.resolve(
        request(Map.of("_rerunConfigVersionPolicy", "USE_LATEST_CONFIG")), JOB_CODE, latest);

    assertThat(selected).isSameAs(latest);
    verify(jobInstances).selectById(TENANT, SOURCE_INSTANCE_ID);
  }

  @Test
  @DisplayName("USE_SPECIFIED_VERSION 精确读取指定 Job 版本的不可变执行快照")
  void shouldResolveSpecifiedVersionFromHistoricalWorkflowSnapshot() {
    when(workflowRuns.selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 1))
        .thenReturn(runWithDefinition(1, "queue-v1", "timeout-v1"));

    JobDefinitionEntity selected = resolver.resolve(
        request(
            Map.of("_rerunConfigVersionPolicy", "USE_SPECIFIED_VERSION", "_rerunConfigVersion", 1)),
        JOB_CODE,
        latest);

    assertDefinition(selected, 1, "queue-v1", "timeout-v1");
    verify(workflowRuns).selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 1);
  }

  @Test
  @DisplayName("指定版本没有不可变运行快照时明确拒绝")
  void shouldRejectUnavailableSpecifiedVersion() {
    when(workflowRuns.selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 3))
        .thenReturn(null);

    assertThatThrownBy(() -> resolver.resolve(
            request(Map.of(
                "_rerunConfigVersionPolicy", "USE_SPECIFIED_VERSION", "_rerunConfigVersion", 3)),
            JOB_CODE,
            latest))
        .hasMessageContaining("error.rerun.config_version_unavailable");
  }

  @Test
  @DisplayName("拒绝非整数的来源实例 ID 与配置版本")
  void shouldRejectFractionalSourceIdAndConfigVersion() {
    assertThatThrownBy(
            () -> resolver.resolve(request(Map.of("parentInstanceId", 900.5)), JOB_CODE, latest))
        .hasMessageContaining("error.rerun.source_instance_required");

    assertThatThrownBy(() -> resolver.resolve(
            request(Map.of(
                "_rerunConfigVersionPolicy", "USE_SPECIFIED_VERSION", "_rerunConfigVersion", 1.5)),
            JOB_CODE,
            latest))
        .hasMessageContaining("error.rerun.config_version_required");
  }

  @Test
  @DisplayName("原快照版本必须与源 JobInstance 版本一致")
  void shouldRejectSourceSnapshotVersionMismatch() {
    when(workflowRuns.selectByNodeJobInstanceId(TENANT, SOURCE_INSTANCE_ID))
        .thenReturn(runWithDefinition(2, "queue-v2", "timeout-v2"));

    assertThatThrownBy(() -> resolver.resolve(request(Map.of()), JOB_CODE, latest))
        .hasMessageContaining("error.rerun.original_config_unavailable");
  }

  @Test
  @DisplayName("普通 Workflow 启动不读取补跑来源或覆盖当前定义")
  void shouldLeaveNonRerunLaunchUnchanged() {
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .triggerType(TriggerType.API)
        .params(Map.of())
        .build();

    assertThat(resolver.resolve(request, JOB_CODE, latest)).isSameAs(latest);
  }

  private LaunchRequest request(Map<String, Object> additionalParams) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("operationType", "JOB_RERUN");
    params.put("parentInstanceId", SOURCE_INSTANCE_ID);
    params.putAll(additionalParams);
    return LaunchRequest.builder()
        .tenantId(TENANT)
        .triggerType(TriggerType.CATCH_UP)
        .params(params)
        .build();
  }

  private JobDefinitionEntity definition(int version, String queue, String timeout) {
    return JobDefinitionEntity.builder()
        .id(JOB_DEFINITION_ID)
        .tenantId(TENANT)
        .jobCode(JOB_CODE)
        .jobType("GENERAL")
        .version(version)
        .queueCode(queue)
        .timeoutSeconds(timeout.endsWith("v1") ? 600 : 30)
        .executionHandler(timeout)
        .defaultParams(Map.of("source", "v" + version))
        .enabled(true)
        .build();
  }

  private WorkflowRunEntity runWithDefinition(int version, String queue, String executionHandler) {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setTenantId(TENANT);
    node.setWorkflowDefinitionId(21L);
    node.setNodeCode("JOB");
    node.setNodeType(WorkflowNodeType.JOB.code());
    node.setRelatedJobCode(JOB_CODE);
    node.setEnabled(true);
    JobDefinitionEntity definition = definition(version, queue, executionHandler);
    Map<String, Object> jobMetadata = Map.of(
        "jobDefinitionId", JOB_DEFINITION_ID,
        "jobCode", JOB_CODE,
        "jobType", "GENERAL",
        "jobDefinitionVersion", version,
        "executionDefinition", definition);
    Map<String, Object> snapshot = Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        21L,
        "workflowVersion",
        1,
        "workflowCalendarCode",
        "CAL",
        "nodes",
        List.of(node),
        "edges",
        List.of(),
        "jobDefinitions",
        Map.of("JOB", jobMetadata),
        "systemPipelines",
        Map.of());
    var run = new WorkflowRunEntity();
    run.setTenantId(TENANT);
    run.setWorkflowDefinitionId(21L);
    run.setWorkflowVersionSnapshot(1);
    run.setExecutionSnapshot(JsonUtils.toJson(snapshot));
    return run;
  }

  private void assertDefinition(
      JobDefinitionEntity actual, int version, String queue, String executionHandler) {
    assertThat(actual.version()).isEqualTo(version);
    assertThat(actual.queueCode()).isEqualTo(queue);
    assertThat(actual.executionHandler()).isEqualTo(executionHandler);
    assertThat(actual.timeoutSeconds()).isEqualTo(version == 1 ? 600 : 30);
  }
}
