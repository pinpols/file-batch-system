package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("整 Workflow Replay 的 Job 定义按节点冻结解析")
@ExtendWith(MockitoExtension.class)
class WorkflowReplayJobDefinitionResolverTest {

  private static final String TENANT = "tenant-a";
  private static final String NODE_CODE = "LOAD_NODE";
  private static final String JOB_CODE = "LOAD_CUSTOMERS";
  private static final Long JOB_DEFINITION_ID = 101L;

  @Mock
  private WorkflowRunMapper workflowRuns;

  @InjectMocks
  private WorkflowReplayJobDefinitionResolver resolver;

  private WorkflowRunExecutionSnapshot sourceSnapshot;
  private JobDefinitionView sourceDefinition;
  private JobDefinitionView latestDefinition;

  @BeforeEach
  void setUp() {
    sourceDefinition = definition(2, "source-v2");
    latestDefinition = definition(4, "latest-v4");
    sourceSnapshot = snapshot(sourceDefinition);
  }

  @Test
  @DisplayName("原配置策略直接使用来源运行的节点定义快照")
  void shouldUseFrozenSourceNodeDefinition_whenOriginalPolicySelected() {
    JobDefinitionView selected = resolve(ConfigVersionPolicy.USE_ORIGINAL_CONFIG, null, null);

    assertThat(selected.id()).isEqualTo(sourceDefinition.id());
    assertThat(selected.version()).isEqualTo(sourceDefinition.version());
    assertThat(selected.queueCode()).isEqualTo(sourceDefinition.queueCode());
  }

  @Test
  @DisplayName("最新配置策略校验租户和 Job 身份后使用当前定义")
  void shouldUseLatestDefinition_whenTenantAndJobMatch() {
    JobDefinitionView selected =
        resolve(ConfigVersionPolicy.USE_LATEST_CONFIG, null, latestDefinition);

    assertThat(selected).isSameAs(latestDefinition);
  }

  @Test
  @DisplayName("同编码作业删除重建后不能把新身份作为来源作业的最新版本")
  void shouldRejectLatestDefinition_whenJobIdentityWasRecreated() {
    JobDefinitionView recreated = JobDefinitionView.builder()
        .id(999L)
        .tenantId(TENANT)
        .jobCode(JOB_CODE)
        .version(4)
        .build();

    assertThatThrownBy(() -> resolve(ConfigVersionPolicy.USE_LATEST_CONFIG, null, recreated))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("指定版本从同一 Job 身份的历史 WorkflowRun 快照读取")
  void shouldUseSpecifiedVersion_whenHistoricalSnapshotAvailable() {
    when(workflowRuns.selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 3))
        .thenReturn(runWith(definition(3, "historical-v3")));

    JobDefinitionView selected = resolve(ConfigVersionPolicy.USE_SPECIFIED_VERSION, 3, null);

    assertThat(selected.version()).isEqualTo(3);
    assertThat(selected.queueCode()).isEqualTo("historical-v3");
  }

  @Test
  @DisplayName("历史快照缺失时失败关闭")
  void shouldRejectSpecifiedVersion_whenHistoricalSnapshotUnavailable() {
    when(workflowRuns.selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 3))
        .thenReturn(null);

    assertThatThrownBy(() -> resolve(ConfigVersionPolicy.USE_SPECIFIED_VERSION, 3, null))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("历史查询返回错误版本时失败关闭")
  void shouldRejectSpecifiedVersion_whenHistoricalSnapshotVersionDiffers() {
    when(workflowRuns.selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 3))
        .thenReturn(runWith(definition(4, "wrong-v4")));

    assertThatThrownBy(() -> resolve(ConfigVersionPolicy.USE_SPECIFIED_VERSION, 3, null))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("同一 Job 在历史图中被多个节点引用时接受相同定义")
  void shouldResolveSpecifiedVersion_whenSameJobAppearsInMultipleHistoricalNodes() {
    when(workflowRuns.selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 3))
        .thenReturn(runWithRepeatedJob(false));

    JobDefinitionView selected = resolve(ConfigVersionPolicy.USE_SPECIFIED_VERSION, 3, null);

    assertThat(selected.version()).isEqualTo(3);
    assertThat(selected.queueCode()).isEqualTo("historical-v3");
  }

  @Test
  @DisplayName("历史图中相同 Job 身份版本对应不同配置时拒绝")
  void shouldRejectSpecifiedVersion_whenRepeatedJobDefinitionsConflict() {
    when(workflowRuns.selectLatestJobDefinitionSnapshot(TENANT, JOB_DEFINITION_ID, JOB_CODE, 3))
        .thenReturn(runWithRepeatedJob(true));

    assertThatThrownBy(() -> resolve(ConfigVersionPolicy.USE_SPECIFIED_VERSION, 3, null))
        .isInstanceOf(BizException.class);
  }

  @SuppressWarnings("unchecked")
  private WorkflowRunEntity runWithRepeatedJob(boolean conflictingDefinition) {
    WorkflowRunEntity run = runWith(definition(3, "historical-v3"));
    Map<String, Object> snapshot =
        JsonUtils.fromJson(run.getExecutionSnapshot(), new TypeReference<Map<String, Object>>() {});
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) snapshot.get("nodes");
    Map<String, Object> repeatedNode = new LinkedHashMap<>(nodes.getFirst());
    repeatedNode.put("nodeCode", "LOAD_AGAIN");
    List<Map<String, Object>> repeatedNodes = new ArrayList<>(nodes);
    repeatedNodes.add(repeatedNode);
    snapshot.put("nodes", repeatedNodes);
    Map<String, Object> jobs = (Map<String, Object>) snapshot.get("jobDefinitions");
    Map<String, Object> metadata = new LinkedHashMap<>((Map<String, Object>) jobs.get(NODE_CODE));
    if (conflictingDefinition) {
      Map<String, Object> execution =
          new LinkedHashMap<>((Map<String, Object>) metadata.get("executionDefinition"));
      execution.put("queueCode", "conflicting-queue");
      metadata.put("executionDefinition", execution);
    }
    jobs.put("LOAD_AGAIN", metadata);
    run.setExecutionSnapshot(JsonUtils.toJson(snapshot));
    return run;
  }

  private JobDefinitionView resolve(
      ConfigVersionPolicy policy, Integer specifiedVersion, JobDefinitionView latest) {
    WorkflowReplayJobDefinitionRequest request = WorkflowReplayJobDefinitionRequest.builder()
        .tenantId(TENANT)
        .nodeCode(NODE_CODE)
        .jobCode(JOB_CODE)
        .sourceSnapshot(sourceSnapshot)
        .latestDefinition(latest)
        .policy(policy)
        .specifiedVersion(specifiedVersion)
        .build();
    return resolver.resolve(request);
  }

  private WorkflowRunExecutionSnapshot snapshot(JobDefinitionView definition) {
    return WorkflowRunExecutionSnapshot.from(runWith(definition));
  }

  private WorkflowRunEntity runWith(JobDefinitionView definition) {
    Map<String, Object> node = Map.of(
        "nodeCode", NODE_CODE,
        "nodeType", "JOB",
        "tenantId", TENANT,
        "workflowDefinitionId", 81,
        "relatedJobCode", JOB_CODE);
    Map<String, Object> executionDefinition = Map.of(
        "id", definition.id(),
        "tenantId", definition.tenantId(),
        "jobCode", definition.jobCode(),
        "jobType", "GENERAL",
        "version", definition.version(),
        "queueCode", definition.queueCode());
    Map<String, Object> jobMetadata = Map.of(
        "jobDefinitionId",
        definition.id(),
        "jobCode",
        JOB_CODE,
        "jobType",
        "GENERAL",
        "jobDefinitionVersion",
        definition.version(),
        "executionDefinition",
        executionDefinition);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setTenantId(TENANT);
    run.setWorkflowDefinitionId(81L);
    run.setWorkflowVersionSnapshot(1);
    run.setExecutionSnapshot(JsonUtils.toJson(Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        81,
        "workflowVersion",
        1,
        "workflowCalendarCode",
        "CAL",
        "nodes",
        List.of(node),
        "edges",
        List.of(),
        "jobDefinitions",
        Map.of(NODE_CODE, jobMetadata),
        "systemPipelines",
        Map.of())));
    return run;
  }

  private JobDefinitionView definition(int version, String queueCode) {
    return JobDefinitionView.builder()
        .id(JOB_DEFINITION_ID)
        .tenantId(TENANT)
        .jobCode(JOB_CODE)
        .version(version)
        .queueCode(queueCode)
        .build();
  }
}
