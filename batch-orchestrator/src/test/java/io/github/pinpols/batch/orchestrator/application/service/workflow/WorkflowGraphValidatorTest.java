package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("工作流图校验器: 线性链路, 环, 不可达节点, 断头节点与汇聚网关等结构配置的校验口径")
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
  @DisplayName("起点到终点线性连通时校验通过,不产生任何错误")
  void shouldReportClean_whenGraphIsLinearChain() {
    seed(nodes("START", "TASK1", "END"), edges(edge("START", "TASK1"), edge("TASK1", "END")));
    var result = validator.validate(1L);
    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  @DisplayName("节点自环被判定为环结构错误")
  void shouldReportCycleError_whenNodeSelfLoops() {
    seed(nodes("START", "TASK1", "END"), edges(edge("START", "TASK1"), edge("TASK1", "TASK1")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V1"));
  }

  @Test
  @DisplayName("多个节点首尾相接成环时判定为环结构错误")
  void shouldReportCycleError_whenNodesFormCycle() {
    seed(
        nodes("START", "A", "B", "END"),
        edges(edge("START", "A"), edge("A", "B"), edge("B", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V1"));
  }

  @Test
  @DisplayName("存在从起点不可达的节点时判定为不可达错误,并定位到该节点")
  void shouldReportUnreachable_whenNodeNotReachableFromStart() {
    seed(nodes("START", "A", "ORPHAN", "END"), edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> {
      assertThat(i.code()).isEqualTo("V2");
      assertThat(i.nodeCode()).isEqualTo("ORPHAN");
    });
  }

  @Test
  @DisplayName("节点没有任何通往终点的路径时判定为断头错误")
  void shouldReportDeadEnd_whenNodeCannotReachEnd() {
    seed(
        nodes("START", "A", "DEAD", "END"),
        edges(edge("START", "A"), edge("A", "DEAD"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V3"));
  }

  @Test
  @DisplayName("节点参数引用了不存在的上游节点时判定为引用错误")
  void shouldReportMissingNodeReference_whenParamPointsToUnknownNode() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setNodeParams("{\"file\":\"$.nodes.MISSING.output.fileId\"}");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V4"));
  }

  @Test
  @DisplayName("跨天依赖配置格式非法时判定为配置错误")
  void shouldReportConfigError_whenCrossDayDependencyFormatInvalid() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setCrossDayDependencies("not-json{[");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V6"));
  }

  @Test
  @DisplayName("跨天依赖的营业日区间超过允许上限时判定为配置错误")
  void shouldReportConfigError_whenBizDateRangeExceedsLimit() {
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
  @DisplayName("汇聚网关声明全量等待但只有一条入边时判定为配置错误")
  void shouldReportConfigError_whenFullJoinGatewayHasSingleIncoming() {
    var nodes = nodes("START", "A", "END");
    nodes.get(1).setNodeType("GATEWAY");
    nodes.get(1).setNodeParams("{\"joinMode\":\"ALL_OF\"}");
    seed(nodes, edges(edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V9"));
  }

  @Test
  @DisplayName("汇聚网关要求的入边数量与实际入边不一致时判定为配置错误")
  void shouldReportConfigError_whenRequiredIncomingCountMismatches() {
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
  @DisplayName("起点节点存在入边时判定为结构错误")
  void shouldReportStructureError_whenStartNodeHasIncomingEdge() {
    seed(
        nodes("START", "A", "END"),
        edges(edge("A", "START"), edge("START", "A"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V11"));
  }

  @Test
  @DisplayName("同一流程内节点编码重复时判定为结构错误")
  void shouldReportStructureError_whenNodeCodesDuplicate() {
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
  @DisplayName("连线两端引用了不存在的节点时判定为结构错误")
  void shouldReportStructureError_whenEdgeReferencesUnknownNode() {
    seed(
        nodes("START", "A", "END"),
        edges(edge("START", "A"), edge("A", "GHOST"), edge("A", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).anySatisfy(i -> assertThat(i.code()).isEqualTo("V14"));
  }

  @Test
  @DisplayName("流程没有任何节点时校验通过,不产生错误")
  void shouldReportClean_whenWorkflowHasNoNodes() {
    when(nodeMapper.selectByWorkflowDefinitionId(1L)).thenReturn(List.of());
    when(edgeMapper.selectAllByWorkflowDefinitionId(1L)).thenReturn(List.of());
    var result = validator.validate(1L);
    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  @DisplayName("流程标识为空时直接返回通过,不查询节点与连线")
  void shouldReportClean_whenWorkflowIdMissing() {
    var result = validator.validate(null);
    assertThat(result.hasErrors()).isFalse();
  }

  // ── V5 / V8 / V12 / V15 ─────────────────────────────────────────────────

  @Test
  @DisplayName("引用了任务类型未声明的输出键时产生告警,但不出错")
  void shouldWarn_whenOutputKeyUnknownForJobType() {
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
  @DisplayName("下游引用了可选跨天依赖上游的产出时判定为错误")
  void shouldReportError_whenDownstreamReferencesOptionalUpstream() {
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
  @DisplayName("引用已知任务的输出键时产生类型提示告警")
  void shouldWarnTypeHint_whenKnownOutputKeyReferenced() {
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
  @DisplayName("跨天依赖上下游使用不同业务日历且时区不一致时产生告警")
  void shouldWarn_whenDependencySpansDifferentCalendars() {
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
  @DisplayName("等待节点传感配置完整时校验通过,不产生相关错误")
  void waitNodeWithValidSensorSpec_clean() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams("{\"sensor_type\":\"FILE_ARRIVAL\","
        + "\"sensor_spec\":{\"pattern\":\"x-*\",\"maxAgeSeconds\":3600},"
        + "\"timeout_seconds\":120,\"poll_interval_seconds\":30,\"on_timeout\":\"FAIL\"}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors()).noneMatch(i -> i.code().startsWith("V16"));
  }

  @Test
  @DisplayName("等待节点未声明传感类型时判定为配置错误")
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
  @DisplayName("等待节点传感类型不在支持范围内时判定为配置错误")
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
  @DisplayName("轮询型传感缺少地址配置时判定为配置错误并给出提示")
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
  @DisplayName("等待超时不大于轮询间隔时判定为配置错误并给出提示")
  void waitNodeTimeoutNotGreaterThanPoll_V16d() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams("{\"sensor_type\":\"FILE_ARRIVAL\","
        + "\"sensor_spec\":{\"pattern\":\"x\",\"maxAgeSeconds\":60},"
        + "\"timeout_seconds\":30,\"poll_interval_seconds\":30,\"on_timeout\":\"FAIL\"}");
    seed(
        List.of(node("START", "START"), wait, node("END", "END")),
        edges(edge("START", "WAIT1"), edge("WAIT1", "END")));
    var result = validator.validate(1L);
    assertThat(result.errors())
        .anySatisfy(i -> assertThat(i.message())
            .contains("timeout_seconds must be greater than poll_interval_seconds"));
  }

  @Test
  @DisplayName("超时处理策略取值非法时判定为配置错误")
  void waitNodeInvalidOnTimeout_V16e() {
    WorkflowNodeEntity wait = node("WAIT1", "WAIT");
    wait.setNodeParams("{\"sensor_type\":\"FILE_ARRIVAL\","
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
    when(nodeMapper.selectByWorkflowDefinitionId(1L)).thenReturn(nodes);
    when(edgeMapper.selectAllByWorkflowDefinitionId(1L)).thenReturn(edges);
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
