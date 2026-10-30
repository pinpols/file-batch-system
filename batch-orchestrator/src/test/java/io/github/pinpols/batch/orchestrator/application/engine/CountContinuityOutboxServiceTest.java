package io.github.pinpols.batch.orchestrator.application.engine;

import static io.github.pinpols.batch.orchestrator.application.engine.CountContinuityFixtures.accountedInput;
import static io.github.pinpols.batch.orchestrator.application.engine.CountContinuityFixtures.output;
import static io.github.pinpols.batch.orchestrator.application.engine.CountContinuityFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("条数连续性：冻结的数据关系、范围证据、差额和执行身份")
@ExtendWith(MockitoExtension.class)
class CountContinuityOutboxServiceTest {
  @Mock
  private WorkflowRunMapper runs;

  @Mock
  private WorkflowNodeRunMapper nodes;

  @Mock
  private DomainEventPublisher publisher;

  private CountContinuityOutboxService service;
  private WorkflowRunEntity run;
  private WorkflowNodeRunEntity upstream;
  private WorkflowNodeRunEntity current;

  @Test
  @DisplayName("普通控制边导入一行后导出六行，不构成数据丢失告警")
  void shouldSkipControlEdge_whenContinuityIsNotDeclared() {
    fixture(
        Map.of(),
        output("output", 1, "batch-1", "RECORDS"),
        output("input", 6, "all-tenant", "RECORDS"));

    check();

    verifyNoInteractions(publisher);
    verify(nodes, never()).selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT");
  }

  @Test
  @DisplayName("显式停用核对时不发核对结果")
  void shouldSkipCheck_whenDeclarationIsDisabled() {
    fixture(
        Map.of("countContinuity", Map.of("enabled", false)),
        output("output", 1, "batch-1", "RECORDS"),
        output("input", 6, "batch-1", "RECORDS"));

    check();

    verifyNoInteractions(publisher);
  }

  @Test
  @DisplayName("同范围同单位的不一致仍产生真实差异告警")
  void shouldPublishMismatch_whenComparableCountsDiffer() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 950, "version-1", "RECORDS"));

    check();

    DomainEvent event = event();
    assertThat(event.eventType()).isEqualTo("workflow.node.count.continuity.v1");
    assertThat(event.payload())
        .containsEntry("status", "MISMATCH")
        .containsEntry("upstreamOutputCount", 1000L)
        .containsEntry("currentInputCount", 950L)
        .containsEntry("expectedDelta", 0L)
        .containsEntry("nodeRunId", 22L)
        .containsEntry("upstreamNodeRunId", 11L);
    assertThat(event.eventKey()).contains(":22:11:");
  }

  @Test
  @DisplayName("同范围同单位且计数相同，写入明确的匹配审计")
  void shouldRecordMatchedEvidence_whenComparableCountsMatch() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));

    check();

    assertThat(event().eventType()).isEqualTo("workflow.node.count.continuity.checked.v1");
    assertThat(eventPayload()).containsEntry("status", "MATCHED");
  }

  @Test
  @DisplayName("合法过滤差额要求精确匹配并保留声明原因")
  void shouldAccountForExactDeclaredDelta_whenFilteringIsDeclared() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(50, "rejected rows accounted in validation"),
        output("output", 1000, "version-1", "RECORDS"),
        accountedInput(950, 50, "rejection-artifact:version-1:50"));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "MATCHED")
        .containsEntry("expectedDelta", 50L)
        .containsEntry("deltaReason", "rejected rows accounted in validation");
  }

  @Test
  @DisplayName("声明五十行过滤仍多丢一行，继续产生差异告警")
  void shouldRejectExtraDifference_whenDeltaDoesNotMatchExactly() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(50, "documented rejection"),
        output("output", 1000, "version-1", "RECORDS"),
        accountedInput(949, 50, "rejection-artifact:version-1:50"));

    check();

    assertThat(eventPayload()).containsEntry("status", "MISMATCH");
  }

  @Test
  @DisplayName("仅声明允许差额但未上报排除记账时不能称为匹配")
  void shouldRecordUnverified_whenDeclaredDeltaHasNoReportedAccounting() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(50, "documented rejection"),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 950, "version-1", "RECORDS"));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "DELTA_EVIDENCE_MISSING");
  }

  @Test
  @DisplayName("排除计数与声明差额不一致时保持未验证")
  void shouldRecordUnverified_whenReportedExclusionsDoNotMatchDeclaration() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(50, "documented rejection"),
        output("output", 1000, "version-1", "RECORDS"),
        accountedInput(950, 49, "rejection-artifact:version-1:49"));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "DELTA_EVIDENCE_MISMATCH");
  }

  @Test
  @DisplayName("排除计数相同但没有取证引用时保持未验证")
  void shouldRecordUnverified_whenReportedExclusionsHaveNoEvidenceReference() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(50, "documented rejection"),
        output("output", 1000, "version-1", "RECORDS"),
        accountedInput(950, 50, null));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "DELTA_EVIDENCE_MISSING");
  }

  @ParameterizedTest
  @MethodSource("unverifiedContexts")
  @DisplayName("缺范围、范围不同、单位不同均不能判为计数匹配")
  void shouldRecordUnverified_whenContextsAreMissingOrDifferent(
      String upstreamScope,
      String upstreamUnit,
      String inputScope,
      String inputUnit,
      String reason) {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, upstreamScope, upstreamUnit),
        output("input", 1000, inputScope, inputUnit));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", reason);
  }

  static Stream<Arguments> unverifiedContexts() {
    return Stream.of(
        Arguments.of(null, null, "version-1", "RECORDS", "COUNT_CONTEXT_MISSING"),
        Arguments.of("version-1", "RECORDS", null, null, "COUNT_CONTEXT_MISSING"),
        Arguments.of("version-1", "RECORDS", "version-2", "RECORDS", "SCOPE_MISMATCH"),
        Arguments.of("version-1", "RECORDS", "version-1", "FILES", "UNIT_MISMATCH"));
  }

  @ParameterizedTest
  @MethodSource("invalidCounts")
  @DisplayName("计数为负、小数、溢出、字符串或缺失时保持未验证")
  void shouldRejectInvalidCounts_whenNumericEvidenceIsNotAnExactNonnegativeLong(Object value) {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", value, "version-1", "RECORDS"),
        output("input", 5, "version-1", "RECORDS"));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "COUNT_INVALID");
  }

  static Stream<Arguments> invalidCounts() {
    return Stream.of(
        Arguments.of(-1),
        Arguments.of(5.5),
        Arguments.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
        Arguments.of("5"),
        Arguments.of((Object) null));
  }

  @Test
  @DisplayName("冻结图中没有声明的启用边，不能核对实时图中新加的关系")
  void shouldRejectUndeclaredDataRelation_whenFrozenIncomingEdgeIsDisabled() {
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));
    freeze(policy(0, ""), false);

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "DATA_EDGE_MISSING");
  }

  @Test
  @DisplayName("存在多条控制依赖时，只查询声明的数据来源")
  void shouldUseOnlyDeclaredUpstream_whenOtherControlDependenciesExist() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));

    check();

    verify(nodes).selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT");
    verify(nodes, never()).selectLatestByWorkflowRunIdAndNodeCode(7L, "CONTROL");
    assertThat(eventPayload()).containsEntry("status", "MATCHED");
  }

  @Test
  @DisplayName("上游失败的重试记录不能提供成功产出的核对依据")
  void shouldRecordUnverified_whenLatestUpstreamIsNotSuccessful() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));
    upstream.setNodeStatus("FAILED");

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "UPSTREAM_NOT_SUCCESS");
  }

  @Test
  @DisplayName("缺冻结图时保持未验证，不读取当前 Workflow 定义代替")
  void shouldRecordUnverified_whenFrozenGraphIsMissing() {
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));
    run.setExecutionSnapshot(null);

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "SNAPSHOT_UNAVAILABLE");
  }

  @Test
  @DisplayName("损坏或不支持的冻结图保持未验证，不允许回退实时定义")
  void shouldRecordUnverified_whenSnapshotIsInvalid() {
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));
    run.setExecutionSnapshot("{\"schemaVersion\":99}");

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "SNAPSHOT_INVALID");
    verify(nodes, never()).selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT");
  }

  @Test
  @DisplayName("节点产出 JSON 损坏时保持未验证")
  void shouldRecordUnverified_whenPersistedOutputIsMalformed() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));
    upstream.setOutput("{invalid");

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "OUTPUT_INVALID");
  }

  @Test
  @DisplayName("当前执行未成功时，已写入的部分计数也不能称为匹配")
  void shouldRecordUnverified_whenCurrentExecutionIsNotSuccessful() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1000, "version-1", "RECORDS"));
    current.setNodeStatus("RUNNING");

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "CURRENT_NOT_SUCCESS");
  }

  @ParameterizedTest
  @MethodSource("invalidCounts")
  @DisplayName("差额声明为负、小数、溢出、字符串或空值时失败关闭")
  void shouldRecordUnverified_whenExpectedDeltaIsInvalid(Object value) {
    Map<String, Object> declaration = new LinkedHashMap<>();
    declaration.put("enabled", true);
    declaration.put("upstreamNodeCode", "IMPORT");
    declaration.put("expectedDelta", value);
    declaration.put("deltaReason", "explicit filtering declaration");
    fixture(
        Map.of("countContinuity", declaration),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 1001, "version-1", "RECORDS"));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "DECLARATION_INVALID");
  }

  @Test
  @DisplayName("重复执行节点使用新执行身份，核对事件不被首次执行遮盖")
  void shouldUseNewEventIdentity_whenNodeHasAnotherRun() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 950, "version-1", "RECORDS"));
    current.setId(23L);
    current.setRunSeq(2);

    check();

    assertThat(event().eventKey()).contains(":23:11:");
  }

  @Test
  @DisplayName("已持久化产出与本次回报不一致时不误判匹配")
  void shouldRecordUnverified_whenReportDoesNotMatchPersistedOutput() {
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "IMPORT")).thenReturn(upstream);
    fixture(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 950, "version-1", "RECORDS"));

    service.checkContinuity(
        7L, "PROCESS", JsonUtils.toJson(output("input", 1000, "version-1", "RECORDS")));

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "CURRENT_OUTPUT_CHANGED");
  }

  @Test
  @DisplayName("缺少差额说明时不能将非零过滤量当作合法")
  void shouldRecordUnverified_whenNonzeroDeltaHasNoReason() {
    fixture(
        policy(50, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 950, "version-1", "RECORDS"));

    check();

    assertThat(eventPayload())
        .containsEntry("status", "UNVERIFIED")
        .containsEntry("reason", "DECLARATION_INVALID");
  }

  private void check() {
    service.checkContinuity(7L, "PROCESS", current.getOutput());
  }

  private DomainEvent event() {
    ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(publisher).publish(captor.capture());
    return captor.getValue();
  }

  private Map<String, Object> eventPayload() {
    return event().payload();
  }

  private void fixture(
      Map<String, Object> params, Map<String, Object> fromOutput, Map<String, Object> input) {
    run.setId(7L);
    run.setTenantId("t1");
    run.setWorkflowDefinitionId(3L);
    run.setWorkflowVersionSnapshot(1);
    freeze(params, true);
    upstream.setOutput(JsonUtils.toJson(fromOutput));
    current.setOutput(JsonUtils.toJson(input));
    when(runs.selectByIdAnyTenant(7L)).thenReturn(run);
    when(nodes.selectLatestByWorkflowRunIdAndNodeCode(7L, "PROCESS")).thenReturn(current);
  }

  private void freeze(Map<String, Object> params, boolean edgeEnabled) {
    run.setExecutionSnapshot(CountContinuityFixtures.snapshot("t1", 3L, params, edgeEnabled));
  }

  private static WorkflowNodeRunEntity nodeRun(long id, String code) {
    WorkflowNodeRunEntity node = new WorkflowNodeRunEntity();
    node.setId(id);
    node.setWorkflowRunId(7L);
    node.setNodeCode(code);
    node.setNodeStatus("SUCCESS");
    node.setRunSeq(1);
    return node;
  }

  @BeforeEach
  void initializeSubject() {
    service = new CountContinuityOutboxService(runs, nodes, publisher, new ObjectMapper());
    run = new WorkflowRunEntity();
    upstream = nodeRun(11L, "IMPORT");
    current = nodeRun(22L, "PROCESS");
  }
}
