package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("整 Workflow 回放只从经过校验的加密快照恢复执行输入")
class WorkflowRunReplaySnapshotResolverTest {

  private static final String TENANT = "tenant-whole-replay";
  private static final Long SOURCE_RUN_ID = 901L;
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 20);

  @Mock
  private SecretPayloadProtector secretPayloadProtector;

  private WorkflowRunReplaySnapshotResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new WorkflowRunReplaySnapshotResolver(secretPayloadProtector);
  }

  @Test
  @DisplayName("校验来源身份和快照哈希后冻结选择配置并清除旧 replay session")
  void shouldRestoreFrozenGraphAndParams_whenOperationSnapshotIsAuthentic() throws Exception {
    Fixture fixture = fixture();
    when(secretPayloadProtector.revealEncrypted("sealed-operation-snapshot"))
        .thenReturn(fixture.snapshotJson());
    when(secretPayloadProtector.revealEncrypted("sealed-root-params"))
        .thenReturn("{\"taskType\":\"root-sql\",\"password\":\"private-root-secret\"}");

    WorkflowRunReplaySnapshotResolver.Snapshot resolved =
        resolver.resolve(fixture.operation(), "workflow-replay-808");

    assertThat(resolved.workflowDefinitionId()).isEqualTo(81L);
    assertThat(resolved.workflowVersion()).isEqualTo(4);
    assertThat(resolved.bizDate()).isEqualTo(BIZ_DATE);
    assertThat(resolved.calendarCode()).isEqualTo("SOURCE_CALENDAR");
    assertThat(resolved.dataIntervalStart()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
    assertThat(resolved.dataIntervalEnd()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
    assertThat(resolved.launchParams())
        .containsEntry("taskType", "root-sql")
        .containsEntry("password", "private-root-secret")
        .containsEntry("_rerunResultPolicy", "CREATE_NEW_VERSION")
        .containsEntry("_rerunConfigVersionPolicy", "USE_ORIGINAL_CONFIG");
    Map<String, Object> executionSnapshot =
        JsonUtils.fromJson(resolved.executionSnapshotJson(), new TypeReference<>() {});
    assertThat(executionSnapshot)
        .containsEntry("triggerRequestId", "workflow-replay-808")
        .containsEntry("replaySessionId", null);
    assertThat(((Number) executionSnapshot.get("replayOperationId")).longValue())
        .isEqualTo(808L);
    assertThat(((Number) executionSnapshot.get("sourceWorkflowRunId")).longValue())
        .isEqualTo(SOURCE_RUN_ID);
    Map<?, ?> selectedJob =
        (Map<?, ?>) ((Map<?, ?>) executionSnapshot.get("jobDefinitions")).get("FIRST");
    assertThat(selectedJob.get("jobDefinitionVersion")).isEqualTo(3);
    Map<?, ?> replayGuard = (Map<?, ?>) selectedJob.get("replayExecutionGuard");
    assertThat(((Number) replayGuard.get("replayOperationId")).longValue()).isEqualTo(808L);
    assertThat(((Map<?, ?>) replayGuard.get("declaration")).get("pluginId")).isEqualTo("root-sql");
    assertThat(selectedJob.get("frozenFileTemplateSnapshots"))
        .isEqualTo(Map.of("TPL_IMPORT", "sealed-template-snapshot"));
    verify(secretPayloadProtector).revealEncrypted("sealed-operation-snapshot");
    verify(secretPayloadProtector).revealEncrypted("sealed-root-params");
  }

  @Test
  @DisplayName("恢复整图重放快照时携带预览已批准的跨日结果版本")
  void shouldRestorePinnedCrossDayResultVersionIds_whenOperationSnapshotIsAuthentic()
      throws Exception {
    Fixture fixture = fixture();
    Map<String, Object> operationSnapshot =
        JsonUtils.fromJson(fixture.snapshotJson(), new TypeReference<>() {});
    Map<String, Object> sourceExecutionSnapshot = JsonUtils.fromJson(
        String.valueOf(operationSnapshot.get("sourceExecutionSnapshot")), new TypeReference<>() {});
    Map<String, Object> firstNode = node("FIRST", "JOB", "COMPUTE_A");
    firstNode.put(
        "crossDayDependencies",
        "[{\"alias\":\"previous\",\"jobCode\":\"COMPUTE_PREVIOUS\",\"bizDateOffset\":-1}]");
    sourceExecutionSnapshot.put(
        "nodes", List.of(node("START", "START", null), firstNode, node("END", "END", null)));
    operationSnapshot.put("sourceExecutionSnapshot", JsonUtils.toJson(sourceExecutionSnapshot));
    operationSnapshot.put(
        "selectedCrossDayDependencyResultVersionIdsByNode",
        Map.of("FIRST", Map.of("previous", List.of(907L))));
    String updatedSnapshotJson = JsonUtils.toJson(operationSnapshot);
    ReplayOperationEntity operation = fixture.operation().toBuilder()
        .snapshotHash(sha256(updatedSnapshotJson))
        .build();
    when(secretPayloadProtector.revealEncrypted("sealed-operation-snapshot"))
        .thenReturn(updatedSnapshotJson);
    when(secretPayloadProtector.revealEncrypted("sealed-root-params")).thenReturn("{}");

    WorkflowRunReplaySnapshotResolver.Snapshot resolved =
        resolver.resolve(operation, "workflow-replay-808");

    Map<String, Object> executionSnapshot =
        JsonUtils.fromJson(resolved.executionSnapshotJson(), new TypeReference<>() {});
    Map<?, ?> crossDayResultVersionIds =
        (Map<?, ?>) executionSnapshot.get("crossDayDependencyResultVersionIdsByNode");
    assertThat(crossDayResultVersionIds.get("FIRST")).isEqualTo(Map.of("previous", List.of(907)));
  }

  @Test
  @DisplayName("分发作业快照恢复审批选择的通道配置和密钥版本绑定")
  void shouldRestoreApprovedDispatchChannelAndSecretBindings() throws Exception {
    Fixture fixture = dispatchFixture();
    when(secretPayloadProtector.revealEncrypted("sealed-operation-snapshot"))
        .thenReturn(fixture.snapshotJson());
    when(secretPayloadProtector.revealEncrypted("sealed-root-params")).thenReturn("{}");

    WorkflowRunReplaySnapshotResolver.Snapshot resolved =
        resolver.resolve(fixture.operation(), "workflow-replay-808");

    Map<String, Object> executionSnapshot =
        JsonUtils.fromJson(resolved.executionSnapshotJson(), new TypeReference<>() {});
    Map<?, ?> selectedJob =
        (Map<?, ?>) ((Map<?, ?>) executionSnapshot.get("jobDefinitions")).get("FIRST");
    assertThat(selectedJob.get("frozenDispatchChannelSnapshots"))
        .isEqualTo(Map.of("CHANNEL_A", "sealed-dispatch-channel"));
    Map<?, ?> restoredBindings = (Map<?, ?>) selectedJob.get("dispatchSecretBindings");
    assertThat(restoredBindings)
        .isEqualTo(Map.of(
            "CHANNEL_A",
            Map.of(
                "secretRef", "dispatch/key",
                "secretVersionId", 72,
                "versionNo", 4)));
  }

  @Test
  @DisplayName("快照哈希不符时失败关闭")
  void shouldRejectSnapshot_whenHashDoesNotMatchDecryptedPayload() throws Exception {
    Fixture fixture = fixture();
    ReplayOperationEntity tampered =
        fixture.operation().toBuilder().snapshotHash("0".repeat(64)).build();
    when(secretPayloadProtector.revealEncrypted("sealed-operation-snapshot"))
        .thenReturn(fixture.snapshotJson());

    assertThatThrownBy(() -> resolver.resolve(tampered, "workflow-replay-808"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("租户、来源或业务日身份与操作记录不一致时拒绝恢复")
  void shouldRejectSnapshot_whenOperationIdentityDiffersFromFrozenPreview() throws Exception {
    Fixture fixture = fixture();
    ReplayOperationEntity wrongSource =
        fixture.operation().toBuilder().sourceWorkflowRunId(SOURCE_RUN_ID + 1).build();
    when(secretPayloadProtector.revealEncrypted("sealed-operation-snapshot"))
        .thenReturn(fixture.snapshotJson());
    when(secretPayloadProtector.revealEncrypted("sealed-root-params")).thenReturn("{}");

    assertThatThrownBy(() -> resolver.resolve(wrongSource, "workflow-replay-808"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("整 Workflow 重放不能复用日期区间操作的日期字段")
  void shouldRejectSnapshot_whenWorkflowReplayContainsDateRangeFields() throws Exception {
    Fixture fixture = fixture();
    ReplayOperationEntity wrongRange = fixture.operation().toBuilder()
        .fromDate(BIZ_DATE)
        .throughDate(BIZ_DATE.plusDays(1))
        .build();

    assertThatThrownBy(() -> resolver.resolve(wrongRange, "workflow-replay-808"))
        .isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "selectedFileTemplateSnapshots",
        "selectedDispatchChannelSnapshots",
        "selectedDispatchSecretBindings",
        "selectedCrossDayDependencyResultVersionIdsByNode"
      })
  @DisplayName("已批准配置绑定不能引用未选入重放的节点")
  void shouldRejectSnapshot_whenConfigurationBindingTargetsUnselectedNode(String bindingField)
      throws Exception {
    Fixture fixture = fixture();
    Map<String, Object> snapshot =
        JsonUtils.fromJson(fixture.snapshotJson(), new TypeReference<>() {});
    snapshot.put(bindingField, Map.of("OUTSIDE_REPLAY", Map.of()));
    String snapshotJson = JsonUtils.toJson(snapshot);
    String snapshotHash = sha256(snapshotJson);
    ReplayOperationEntity operation =
        fixture.operation().toBuilder().snapshotHash(snapshotHash).build();
    when(secretPayloadProtector.revealEncrypted("sealed-operation-snapshot"))
        .thenReturn(snapshotJson);
    when(secretPayloadProtector.revealEncrypted("sealed-root-params")).thenReturn("{}");

    assertThatThrownBy(() -> resolver.resolve(operation, "workflow-replay-808"))
        .isInstanceOf(BizException.class);
    verify(secretPayloadProtector).revealEncrypted("sealed-root-params");
  }

  private Fixture fixture() throws Exception {
    return fixture("IMPORT");
  }

  private Fixture dispatchFixture() throws Exception {
    return fixture("DISPATCH");
  }

  private Fixture fixture(String jobType) throws Exception {
    JobDefinitionView selectedDefinition = JobDefinitionView.builder()
        .id(101L)
        .tenantId(TENANT)
        .jobCode("COMPUTE_A")
        .jobType(jobType)
        .version(3)
        .defaultParams(
            jobType.equals("DISPATCH")
                ? Map.of("dispatchChannelCode", "CHANNEL_A")
                : Map.of("taskType", "root-sql"))
        .build();
    Map<String, Object> firstNode = node("FIRST", "JOB", "COMPUTE_A");
    Map<String, Object> sourceDefinition = definition(2, jobType);
    Map<String, Object> sourceSnapshot = new LinkedHashMap<>();
    sourceSnapshot.put("schemaVersion", 2);
    sourceSnapshot.put("workflowDefinitionId", 81L);
    sourceSnapshot.put("workflowVersion", 4);
    sourceSnapshot.put("workflowCalendarCode", "SOURCE_CALENDAR");
    sourceSnapshot.put("replaySessionId", 77L);
    sourceSnapshot.put(
        "nodes", List.of(node("START", "START", null), firstNode, node("END", "END", null)));
    sourceSnapshot.put("edges", List.of(edge("START", "FIRST"), edge("FIRST", "END")));
    Map<String, Object> sourceJobDefinition = new LinkedHashMap<>(sourceDefinition);
    sourceJobDefinition.put("systemPipelineCode", jobType);
    sourceJobDefinition.put("systemPipelineVersion", 1);
    sourceSnapshot.put("jobDefinitions", Map.of("FIRST", sourceJobDefinition));
    sourceSnapshot.put("initialNodes", List.of(Map.of("nodeCode", "FIRST", "nodeType", "JOB")));

    Map<String, Object> preview = new LinkedHashMap<>();
    preview.put("tenantId", TENANT);
    preview.put("sourceWorkflowRunId", SOURCE_RUN_ID);
    preview.put("workflowDefinitionId", 81L);
    preview.put("workflowVersion", 4);
    preview.put("bizDate", BIZ_DATE);
    preview.put("calendarCode", "SOURCE_CALENDAR");
    preview.put("dataIntervalStart", "2026-10-20T00:00:00Z");
    preview.put("dataIntervalEnd", "2026-10-21T00:00:00Z");
    preview.put("resultPolicy", "CREATE_NEW_VERSION");
    preview.put("configVersionPolicy", "USE_ORIGINAL_CONFIG");
    preview.put("configVersion", null);
    Map<String, Object> operationSnapshot = new LinkedHashMap<>();
    operationSnapshot.put("preview", preview);
    operationSnapshot.put("sourceExecutionSnapshot", JsonUtils.toJson(sourceSnapshot));
    operationSnapshot.put("sourceProtectedRootParams", "sealed-root-params");
    operationSnapshot.put("selectedJobDefinitions", Map.of("FIRST", selectedDefinition));
    if (jobType.equals("DISPATCH")) {
      operationSnapshot.put(
          "selectedDispatchChannelSnapshots",
          Map.of("FIRST", Map.of("CHANNEL_A", "sealed-dispatch-channel")));
      operationSnapshot.put(
          "selectedDispatchSecretBindings",
          Map.of(
              "FIRST",
              Map.of(
                  "CHANNEL_A",
                  Map.of("secretRef", "dispatch/key", "secretVersionId", 72, "versionNo", 4))));
    } else {
      operationSnapshot.put(
          "selectedFileTemplateSnapshots",
          Map.of("FIRST", Map.of("TPL_IMPORT", "sealed-template-snapshot")));
    }
    operationSnapshot.put(
        "selectedReplayCapabilities",
        Map.of(
            "FIRST",
            Map.of(
                "pluginId",
                jobType.equals("DISPATCH") ? "DISPATCH" : "root-sql",
                "pluginVersion",
                "1.0.0",
                "externalEffectCategories",
                List.of("BUSINESS_DATABASE"),
                "idempotencyCapability",
                "IDEMPOTENT_BY_PLUGIN_LOGIC",
                "idempotencyKeyScope",
                "tenant+business-key",
                "recoveryProtocolId",
                "SQL_V1",
                "contractVersion",
                1)));
    operationSnapshot.put("dryRun", false);
    operationSnapshot.put("singleJobDefinitionId", null);
    String snapshotJson = JsonUtils.toJson(operationSnapshot);
    String snapshotHash = sha256(snapshotJson);
    ReplayOperationEntity operation = ReplayOperationEntity.builder()
        .id(808L)
        .tenantId(TENANT)
        .operationType(ReplayOperationType.WORKFLOW_RUN.code())
        .status("PENDING_APPROVAL")
        .calendarCode("SOURCE_CALENDAR")
        .scope("FULL_WORKFLOW")
        .executionMode("REPLAY")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .configVersion(null)
        .sourceWorkflowRunId(SOURCE_RUN_ID)
        .snapshotHash(snapshotHash)
        .snapshotPayload(
            JsonUtils.toJson(Map.of("sealedSnapshotPayload", "sealed-operation-snapshot")))
        .build();
    return new Fixture(operation, snapshotJson);
  }

  private Map<String, Object> definition(int version, String jobType) {
    JobDefinitionView definition = JobDefinitionView.builder()
        .id(101L)
        .tenantId(TENANT)
        .jobCode("COMPUTE_A")
        .jobType(jobType)
        .version(version)
        .defaultParams(
            jobType.equals("DISPATCH")
                ? Map.of("dispatchChannelCode", "CHANNEL_A")
                : Map.of("taskType", "root-sql"))
        .build();
    return Map.of(
        "jobDefinitionId", 101L,
        "jobCode", "COMPUTE_A",
        "jobType", jobType,
        "jobDefinitionVersion", version,
        "systemPipelineCode", jobType,
        "systemPipelineVersion", 1,
        "executionDefinition", definition);
  }

  private Map<String, Object> node(String code, String type, String jobCode) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("tenantId", TENANT);
    node.put("workflowDefinitionId", 81L);
    node.put("nodeCode", code);
    node.put("nodeType", type);
    node.put("relatedJobCode", jobCode);
    node.put("enabled", true);
    return node;
  }

  private Map<String, Object> edge(String from, String to) {
    return Map.of(
        "tenantId",
        TENANT,
        "workflowDefinitionId",
        81L,
        "fromNodeCode",
        from,
        "toNodeCode",
        to,
        "edgeType",
        "SUCCESS",
        "enabled",
        true);
  }

  private String sha256(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
  }

  private record Fixture(ReplayOperationEntity operation, String snapshotJson) {}
}
