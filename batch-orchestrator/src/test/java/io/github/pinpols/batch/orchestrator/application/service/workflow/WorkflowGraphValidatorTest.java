package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowGraphValidatorTest {

  private WorkflowNodeMapper nodeMapper;
  private WorkflowEdgeMapper edgeMapper;
  private JobDefinitionMapper jobDefMapper;
  private WorkflowGraphValidator validator;

  @BeforeEach
  void setUp() {
    nodeMapper = mock(WorkflowNodeMapper.class);
    edgeMapper = mock(WorkflowEdgeMapper.class);
    jobDefMapper = mock(JobDefinitionMapper.class);
    validator = new WorkflowGraphValidator(nodeMapper, edgeMapper, jobDefMapper);
  }

  @Test
  void linearStartTaskEndIsClean() {
    seed(nodes("START", "TASK1", "END"), edges(edge("START", "TASK1"), edge("TASK1", "END")));
    var result = validator.validate(1L);
    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void selfLoopReportsV1() {
    seed(nodes("START", "TASK1", "END"), edges(edge("START", "TASK1"), edge("TASK1", "TASK1")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V1"));
  }

  @Test
  void cycleReportsV1() {
    seed(
        nodes("START", "A", "B", "END"),
        edges(edge("START", "A"), edge("A", "B"), edge("B", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V1"));
  }

  @Test
  void unreachableNodeReportsV2() {
    seed(nodes("START", "A", "ORPHAN", "END"), edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors())
        .anySatisfy(
            i -> {
              assertThat(i.code()).isEqualTo("V2");
              assertThat(i.nodeCode()).isEqualTo("ORPHAN");
            });
  }

  @Test
  void deadEndNodeReportsV3() {
    seed(
        nodes("START", "A", "DEAD", "END"),
        edges(edge("START", "A"), edge("A", "DEAD"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V3"));
  }

  @Test
  void dslReferencesMissingNodeReportsV4() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setNodeParams("{\"file\":\"$.nodes.MISSING.output.fileId\"}");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V4"));
  }

  @Test
  void invalidCrossDayDepJsonReportsV6() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setCrossDayDependencies("not-json{[");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V6"));
  }

  @Test
  void crossDayDepRangeOver90DaysReportsV7() {
    var nodes = nodes("START", "A", "END");
    nodes
        .get(1)
        .setCrossDayDependencies(
            "[{\"jobCode\":\"X\",\"bizDateRange\":\"PREV_120_BIZ_DAYS\",\"scope\":\"REQUIRED\"}]");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V7"));
  }

  @Test
  void gatewayAllOfWithSingleIncomingReportsV9() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setNodeType("GATEWAY");
    nodes.get(1).setNodeParams("{\"joinMode\":\"ALL_OF\"}");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V9"));
  }

  @Test
  void gatewayNOfMInconsistentReportsV10() {
    var nodes = nodes("START", "B", "C", "GW", "END");
    nodes.get(3).setNodeType("GATEWAY");
    nodes.get(3).setNodeParams("{\"joinMode\":\"3_OF_4\"}");
    seed(
        nodes,
        edges(
            edge("START", "B"),
            edge("START", "C"),
            edge("B", "GW"),
            edge("C", "GW"),
            edge("GW", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V10"));
  }

  @Test
  void startWithIncomingReportsV11() {
    seed(
        nodes("START", "A", "END"),
        edges(edge("A", "START"), edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V11"));
  }

  @Test
  void duplicateNodeCodeReportsV13() {
    var nodes = new ArrayList<WorkflowNodeEntity>();
    nodes.add(node("START", "START"));
    nodes.add(node("A", "TASK"));
    nodes.add(node("A", "TASK")); // dup
    nodes.add(node("END", "END"));
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V13"));
  }

  @Test
  void edgeRefsMissingNodeReportsV14() {
    seed(
        nodes("START", "A", "END"),
        edges(edge("START", "A"), edge("A", "GHOST"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V14"));
  }

  @Test
  void emptyWorkflowIsClean() {
    when(nodeMapper.selectByWorkflowDefinitionId(eq(1L))).thenReturn(List.of());
    when(edgeMapper.selectAllByWorkflowDefinitionId(eq(1L))).thenReturn(List.of());
    var result = validator.validate(1L);
    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void nullWorkflowIdReturnsClean() {
    var result = validator.validate(null);
    assertThat(result.hasErrors()).isFalse();
  }

  // ── V5 / V8 / V12 / V15 ─────────────────────────────────────────────────

  @Test
  void v5UnknownOutputKeyEmitsWarn() {
    WorkflowNodeEntity start = node("START", "START");
    WorkflowNodeEntity loadFile = node("LOAD_FILE", "TASK");
    loadFile.setRelatedJobCode("import_daily");
    WorkflowNodeEntity downstream = node("DOWNSTREAM", "TASK");
    downstream.setNodeParams("{\"src\": \"$.nodes.LOAD_FILE.output.unknownKey\"}");
    WorkflowNodeEntity end = node("END", "END");
    seed(
        List.of(start, loadFile, downstream, end),
        edges(
            edge("START", "LOAD_FILE"),
            edge("LOAD_FILE", "DOWNSTREAM"),
            edge("DOWNSTREAM", "END")));
    when(jobDefMapper.selectFirstByTenantAndCodeAndEnabled(null, "import_daily", true))
        .thenReturn(jobDef("import_daily", "IMPORT", null, null));

    var result = validator.validate(1L);

    assertThat(result.hasErrors()).isFalse();
    assertThat(result.warnings()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V5"));
  }

  @Test
  void v8OptionalUpstreamReferenceEmitsError() {
    WorkflowNodeEntity start = node("START", "START");
    WorkflowNodeEntity optionalUpstream = node("DEP_ON_PREV", "TASK");
    optionalUpstream.setRelatedJobCode("opt_dep_job");
    optionalUpstream.setCrossDayDependencies(
        "[{\"jobCode\":\"prev_day_pnl\",\"bizDateOffset\":-1,\"scope\":\"OPTIONAL\"}]");
    WorkflowNodeEntity downstream = node("DOWN", "TASK");
    downstream.setNodeParams("{\"src\": \"$.nodes.DEP_ON_PREV.output.fileId\"}");
    WorkflowNodeEntity end = node("END", "END");
    seed(
        List.of(start, optionalUpstream, downstream, end),
        edges(edge("START", "DEP_ON_PREV"), edge("DEP_ON_PREV", "DOWN"), edge("DOWN", "END")));

    var result = validator.validate(1L);

    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V8"));
  }

  @Test
  void v12KnownOutputKeyEmitsTypeHintWarn() {
    WorkflowNodeEntity start = node("START", "START");
    WorkflowNodeEntity src = node("SRC", "TASK");
    src.setRelatedJobCode("import_daily");
    WorkflowNodeEntity downstream = node("DOWN", "TASK");
    downstream.setNodeParams("{\"fid\": \"$.nodes.SRC.output.fileId\"}");
    WorkflowNodeEntity end = node("END", "END");
    seed(
        List.of(start, src, downstream, end),
        edges(edge("START", "SRC"), edge("SRC", "DOWN"), edge("DOWN", "END")));
    when(jobDefMapper.selectFirstByTenantAndCodeAndEnabled(null, "import_daily", true))
        .thenReturn(jobDef("import_daily", "IMPORT", null, null));

    var result = validator.validate(1L);

    assertThat(result.warnings()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V12"));
  }

  @Test
  void v15MixedCalendarEmitsWarn() {
    WorkflowNodeEntity start = node("START", "START");
    WorkflowNodeEntity hk = node("HK_JOB", "TASK");
    hk.setRelatedJobCode("hk_pnl");
    WorkflowNodeEntity us = node("US_JOB", "TASK");
    us.setRelatedJobCode("us_pnl");
    WorkflowNodeEntity end = node("END", "END");
    seed(
        List.of(start, hk, us, end),
        edges(edge("START", "HK_JOB"), edge("HK_JOB", "US_JOB"), edge("US_JOB", "END")));
    when(jobDefMapper.selectFirstByTenantAndCodeAndEnabled(null, "hk_pnl", true))
        .thenReturn(jobDef("hk_pnl", "GENERAL", "CAL_HK", "Asia/Hong_Kong"));
    when(jobDefMapper.selectFirstByTenantAndCodeAndEnabled(null, "us_pnl", true))
        .thenReturn(jobDef("us_pnl", "GENERAL", "CAL_US", "America/New_York"));

    var result = validator.validate(1L);

    assertThat(result.warnings())
        .filteredOn(i -> "V15".equals(i.code()))
        .hasSizeGreaterThanOrEqualTo(1);
  }

  // ── ADR-028 V16 sensor 校验 ────────────────────────────────────────────────

  @Test
  void waitNodeWithValidSensorSpec_clean() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams(
        "{\"sensor_type\":\"FILE_ARRIVAL\","
            + "\"sensor_spec\":{\"pattern\":\"x-*\",\"maxAgeSeconds\":3600},"
            + "\"timeout_seconds\":120,\"poll_interval_seconds\":30,\"on_timeout\":\"FAIL\"}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).noneMatch(i -> i.code().startsWith("V16"));
  }

  @Test
  void waitNodeMissingSensorType_V16a() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams("{}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V16-a"));
  }

  @Test
  void waitNodeInvalidSensorType_V16b() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams("{\"sensor_type\":\"BOGUS\"}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V16-b"));
  }

  @Test
  void waitNodeHttpPollMissingUrl_V16c() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams(
        "{\"sensor_type\":\"HTTP_POLL\",\"sensor_spec\":{\"matchExpr\":\"status==200\"},"
            + "\"timeout_seconds\":120,\"poll_interval_seconds\":30,\"on_timeout\":\"FAIL\"}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors())
        .anySatisfy(i -> assertThat(i.message()).contains("HTTP_POLL sensor_spec.url required"));
  }

  @Test
  void waitNodeTimeoutNotGreaterThanPoll_V16d() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams(
        "{\"sensor_type\":\"FILE_ARRIVAL\","
            + "\"sensor_spec\":{\"pattern\":\"x\",\"maxAgeSeconds\":60},"
            + "\"timeout_seconds\":30,\"poll_interval_seconds\":30,\"on_timeout\":\"FAIL\"}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors())
        .anySatisfy(
            i ->
                assertThat(i.message())
                    .contains("timeout_seconds must be greater than poll_interval_seconds"));
  }

  @Test
  void waitNodeInvalidOnTimeout_V16e() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams(
        "{\"sensor_type\":\"FILE_ARRIVAL\","
            + "\"sensor_spec\":{\"pattern\":\"x\",\"maxAgeSeconds\":60},"
            + "\"timeout_seconds\":120,\"poll_interval_seconds\":30,\"on_timeout\":\"BOGUS\"}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V16-e"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void seed(List<WorkflowNodeEntity> nodes, List<WorkflowEdgeEntity> edges) {
    when(nodeMapper.selectByWorkflowDefinitionId(eq(1L))).thenReturn(nodes);
    when(edgeMapper.selectAllByWorkflowDefinitionId(eq(1L))).thenReturn(edges);
  }

  private static List<WorkflowNodeEntity> nodes(String... codes) {
    List<WorkflowNodeEntity> list = new ArrayList<>();
    for (String code : codes) {
      String type = "START".equals(code) ? "START" : "END".equals(code) ? "END" : "TASK";
      list.add(node(code, type));
    }
    return list;
  }

  private static WorkflowNodeEntity node(String code, String type) {
    WorkflowNodeEntity n = new WorkflowNodeEntity();
    n.setNodeCode(code);
    n.setNodeType(type);
    return n;
  }

  private static List<WorkflowEdgeEntity> edges(WorkflowEdgeEntity... e) {
    return new ArrayList<>(List.of(e));
  }

  private static WorkflowEdgeEntity edge(String from, String to) {
    WorkflowEdgeEntity e = new WorkflowEdgeEntity();
    e.setFromNodeCode(from);
    e.setToNodeCode(to);
    e.setEnabled(true);
    return e;
  }

  private static JobDefinitionEntity jobDef(
      String code, String type, String calendarCode, String timezone) {
    return JobDefinitionEntity.builder()
        .id(1L)
        .jobCode(code)
        .jobType(type)
        .calendarCode(calendarCode)
        .timezone(timezone)
        .enabled(true)
        .build();
  }
}
