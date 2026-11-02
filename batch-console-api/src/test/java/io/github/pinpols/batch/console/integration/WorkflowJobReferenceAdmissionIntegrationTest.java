package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec.EdgeSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec.NodeSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/** 引用校验必须覆盖实际写入口，并由真实 PostgreSQL 证明失败没有部分提交。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Workflow JOB 引用的 API 与配置导入准入")
class WorkflowJobReferenceAdmissionIntegrationTest extends AbstractMutationIntegrationTest {

  private static final String TENANT = "int-wf-ta";
  private static final String OTHER_TENANT = "int-wf-tb";

  private final ConsoleTenantConfigInitApplicationService initService;
  private final TenantConfigPackageExcelService excelService;
  private final TenantConfigPackageExcelImportStore excelStore;

  private String prefix;

  @Autowired
  WorkflowJobReferenceAdmissionIntegrationTest(
      ConsoleTenantConfigInitApplicationService initService,
      TenantConfigPackageExcelService excelService,
      TenantConfigPackageExcelImportStore excelStore) {
    this.initService = initService;
    this.excelService = excelService;
    this.excelStore = excelStore;
  }

  @BeforeEach
  void seedJobReferences() {
    prefix = "ref_" + UUID.randomUUID().toString().replace("-", "");
    insertJob(TENANT, "valid", true);
    insertJob(TENANT, "disabled", false);
    insertJob(OTHER_TENANT, "foreign", true);
  }

  @AfterEach
  void removeOwnedFixture() {
    final String selectIdSql = """
        select id from batch.workflow_definition where tenant_id = ? and workflow_code like ?
    """;
    final String deleteWorkflowDefinitionVersionSql = """
        delete from batch.workflow_definition_version where workflow_definition_id = ?
    """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        delete from batch.workflow_node where workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id in (?, ?) and job_code like ?
    """;

    List<Long> ids = jdbcTemplate.queryForList(selectIdSql, Long.class, TENANT, prefix + "%");
    for (Long id : ids) {
      jdbcTemplate.update(deleteWorkflowDefinitionVersionSql, id);
      jdbcTemplate.update(deleteWorkflowEdgeSql, id);
      jdbcTemplate.update(deleteWorkflowNodeSql, id);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, TENANT, id);
    }
    jdbcTemplate.update(deleteJobDefinitionSql, TENANT, OTHER_TENANT, prefix + "%");
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "disabled"})
  @DisplayName("启用态创建拒绝缺失、异租户与停用 Job，三张定义表均不落库")
  void shouldRejectEnabledCreateWithoutPartialWrites(String reference) {
    create("create", reference, true, 400);
    assertThat(definitionCount()).isZero();
    assertThat(nodeCount()).isZero();
  }

  @Test
  @DisplayName("缺少节点名称的创建请求返回参数错误且不产生定义或节点")
  void shouldRejectMissingNodeName_withoutPartialWrites() {
    String request = body("no_name", "valid", true).replace(",\"nodeName\":\"Start\"", "");
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_ERROR");
    assertThat(definitionCount()).isZero();
    assertThat(nodeCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "disabled"})
  @DisplayName("草稿保留未就绪 Job 引用，启用定义和日程均失败关闭")
  void shouldAllowDraftButRejectBothEnableEntrances(String reference) {
    final String selectEnabledSql = """
        select enabled, schedule_enabled, version from batch.workflow_definition where id = ?
    """;

    Long id = create("draft", reference, false, 200);
    client
        .patch()
        .uri("/api/console/workflow-definitions/{id}/schedule-enabled", id)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"tenantId\":\"int-wf-ta\",\"enabled\":false}")
        .exchange()
        .expectStatus()
        .isOk();
    for (String suffix : List.of("", "/schedule-enabled")) {
      client
          .patch()
          .uri("/api/console/workflow-definitions/" + id + suffix)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"tenantId\":\"int-wf-ta\",\"enabled\":true}")
          .exchange()
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("VALIDATION_ERROR");
    }
    Map<String, Object> selectedEnabledRow = jdbcTemplate.queryForMap(selectEnabledSql, id);
    assertThat(selectedEnabledRow)
        .containsEntry("enabled", false)
        .containsEntry("schedule_enabled", false)
        .containsEntry("version", 1);
    assertThat(nodeCount()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "disabled"})
  @DisplayName("全量更新启用失败时保留旧图、版本及草稿状态")
  void shouldRejectFullUpdateWithoutReplacingDraft(String reference) {
    final String selectEnabledSql = """
        select enabled, version from batch.workflow_definition where id = ?
    """;
    final String selectRelatedJobCodeSql = """
        select related_job_code from batch.workflow_node where workflow_definition_id = ? and node_type = 'JOB'
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_definition_version where workflow_definition_id = ?
    """;

    Long id = create("update", "valid", false, 200);
    client
        .put()
        .uri("/api/console/workflow-definitions/{id}/lock?tenantId={tenant}", id, TENANT)
        .header("X-Console-User", "ref-admin")
        .header("X-Console-Roles", "ROLE_ADMIN")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .exchange()
        .expectStatus()
        .isOk();
    client
        .put()
        .uri("/api/console/workflow-definitions/{id}/full", id)
        .header("X-Console-User", "ref-admin")
        .header("X-Console-Roles", "ROLE_ADMIN")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"expectedVersion\":1,\"definition\":" + body("update", reference, true) + "}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> selectedEnabledRow = jdbcTemplate.queryForMap(selectEnabledSql, id);
    assertThat(selectedEnabledRow).containsEntry("enabled", false).containsEntry("version", 1);
    String selectedRelatedJobCode =
        jdbcTemplate.queryForObject(selectRelatedJobCodeSql, String.class, id);
    assertThat(selectedRelatedJobCode).isEqualTo(prefix + "_valid");
    // create 已写入草稿的初始版本快照；被拒绝的 full update 不得再追加版本行。
    Integer selectedWorkflowDefinitionVersionCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, id);
    assertThat(selectedWorkflowDefinitionVersionCount).isOne();
  }

  @Test
  @DisplayName("草稿可继续编辑未就绪引用，不因数据库默认日程标志而被误当发布")
  void shouldKeepUnreadyDraftEditable() {
    final String selectEnabledSql = """
        select enabled, version from batch.workflow_definition where id = ?
    """;

    Long id = create("editable", "missing", false, 200);
    client
        .put()
        .uri("/api/console/workflow-definitions/{id}/lock?tenantId={tenant}", id, TENANT)
        .header("X-Console-User", "ref-admin")
        .header("X-Console-Roles", "ROLE_ADMIN")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .exchange()
        .expectStatus()
        .isOk();
    client
        .put()
        .uri("/api/console/workflow-definitions/{id}/full", id)
        .header("X-Console-User", "ref-admin")
        .header("X-Console-Roles", "ROLE_ADMIN")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"expectedVersion\":1,\"definition\":" + body("editable", "disabled", false) + "}")
        .exchange()
        .expectStatus()
        .isOk();
    Map<String, Object> selectedEnabledRow = jdbcTemplate.queryForMap(selectEnabledSql, id);
    assertThat(selectedEnabledRow).containsEntry("enabled", false).containsEntry("version", 2);
  }

  @Test
  @DisplayName("已启用 Workflow 的 Job 后续停用时拒绝调度更新，但允许停用 Workflow")
  void shouldRecheckLiveReferenceAndAllowDisabling() {
    final String updateJobDefinitionSql = """
        update batch.job_definition set enabled=false where tenant_id=? and job_code=?
    """;
    final String selectVersionSql = """
        select version from batch.workflow_definition where id=?
    """;
    final String selectEnabledSql = """
        select enabled from batch.workflow_definition where id=?
    """;

    Long id = create("schedule", "valid", true, 200);
    jdbcTemplate.update(updateJobDefinitionSql, TENANT, prefix + "_valid");
    client
        .put()
        .uri("/api/console/workflow-definitions/{id}/schedule", id)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"tenantId\":\"int-wf-ta\",\"expectedVersion\":1,\"scheduleType\":\"MANUAL\"}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_ERROR");
    Integer selectedVersion = jdbcTemplate.queryForObject(selectVersionSql, Integer.class, id);
    assertThat(selectedVersion).isEqualTo(1);
    client
        .patch()
        .uri("/api/console/workflow-definitions/{id}", id)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"tenantId\":\"int-wf-ta\",\"enabled\":false}")
        .exchange()
        .expectStatus()
        .isOk();
    Boolean selectedEnabled = jdbcTemplate.queryForObject(selectEnabledSql, Boolean.class, id);
    assertThat(selectedEnabled).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "disabled"})
  @DisplayName("严格配置导入拒绝未就绪 Job 并回滚 Workflow 与节点")
  void shouldRejectInvalidPublishedImport(String reference) {
    TenantConfigBatchInitResponse response = importWorkflow(reference, true);
    assertThat(response.successTenants()).isZero();
    assertThat(definitionCount()).isZero();
    assertThat(nodeCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "disabled"})
  @DisplayName("非严格配置导入逐项失败也不能留下半个 Workflow")
  void shouldRollbackInvalidItemInNonStrictImport(String reference) {
    TenantConfigBatchInitResponse response = importWorkflow(reference, true, false);
    assertThat(response.results())
        .singleElement()
        .satisfies(result -> assertThat(result.workflowDefinitions().failed()).isEqualTo(1));
    assertThat(definitionCount()).isZero();
    assertThat(nodeCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("配置导入和演练都拒绝缺少节点名称且无部分落库")
  void shouldRejectImportedNodeWithoutName_withoutPartialWrites(boolean dryRun) {
    NodeSpec node = new NodeSpec();
    node.setNodeCode("start");
    node.setNodeType("START");
    WorkflowDefinitionSpec workflow = new WorkflowDefinitionSpec();
    workflow.setWorkflowCode(prefix + "_no_node_name");
    workflow.setWorkflowName("Missing node name");
    workflow.setWorkflowType("DAG");
    workflow.setNodes(List.of(node));
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT));
    request.setDryRun(dryRun);
    request.setWorkflowDefinitions(List.of(workflow));

    TenantConfigBatchInitResponse response = initService.batchInit(request, "ref-admin", prefix);

    assertThat(response.results().getFirst().workflowDefinitions().failed()).isOne();
    assertThat(response.results().getFirst().workflowDefinitions().created()).isZero();
    assertThat(definitionCount()).isZero();
    assertThat(nodeCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("超长节点和边在正式导入及演练逐项失败且不落库")
  void shouldRejectOverlongImportedGraphWithoutPartialWrites(boolean dryRun) {
    WorkflowDefinitionSpec spec = workflowWithTexts("😀".repeat(257), "😀".repeat(1025));
    TenantConfigBatchInitRequest request = workflowRequest(spec, dryRun);

    TenantConfigBatchInitResponse response = initService.batchInit(request, "ref-admin", prefix);

    assertThat(response.results().getFirst().workflowDefinitions().failed()).isOne();
    assertThat(definitionCount()).isZero();
    assertThat(nodeCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"NODE", "EDGE"})
  @DisplayName("创建和完整更新接口拒绝超长文本且保留旧图")
  void shouldRejectOverlongTextsAtCreateAndFullUpdate(String invalidField) {
    Long id = create("http_old", "valid", false, 200);
    String invalid = body("http_new", "valid", false);
    if ("NODE".equals(invalidField)) {
      invalid =
          invalid.replace("\"nodeName\":\"Start\"", "\"nodeName\":\"" + "😀".repeat(257) + "\"");
    } else {
      invalid = invalid.replace(
          "\"edgeType\":\"SUCCESS\"",
          "\"edgeType\":\"SUCCESS\",\"conditionExpr\":\"" + "😀".repeat(1025) + "\"");
    }
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalid)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_ERROR");
    client
        .put()
        .uri("/api/console/workflow-definitions/{id}/full", id)
        .header("X-Console-User", "ref-admin")
        .header("X-Console-Roles", "ROLE_ADMIN")
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"expectedVersion\":1,\"definition\":" + invalid + "}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_ERROR");
    assertThat(definitionCount()).isOne();
    assertThat(nodeCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("PostgreSQL 接受 Unicode 码点边界，跳过既存图时不校验未应用的值")
  void shouldPersistUnicodeBoundariesAndSkipExistingGraph() {
    WorkflowDefinitionSpec spec = workflowWithTexts("😀".repeat(256), "😀".repeat(1024));
    TenantConfigBatchInitRequest request = workflowRequest(spec, false);
    TenantConfigBatchInitResponse created = initService.batchInit(request, "ref-admin", prefix);
    assertThat(created.results().getFirst().workflowDefinitions().created()).isOne();

    Map<String, Object> lengths = jdbcTemplate.queryForMap("""
        select char_length(n.node_name) as node_length, char_length(e.condition_expr) as edge_length
        from batch.workflow_definition d
        join batch.workflow_node n on n.workflow_definition_id = d.id and n.node_code = 'start'
        join batch.workflow_edge e on e.workflow_definition_id = d.id
        where d.tenant_id = ? and d.workflow_code = ?
        """, TENANT, spec.getWorkflowCode());
    assertThat(lengths).containsEntry("node_length", 256).containsEntry("edge_length", 1024);

    spec.getNodes().getFirst().setNodeName("😀".repeat(257));
    spec.getEdges().getFirst().setConditionExpr("😀".repeat(1025));
    TenantConfigBatchInitResponse skipped = initService.batchInit(request, "ref-admin", prefix);
    assertThat(skipped.results().getFirst().workflowDefinitions().skipped()).isOne();
    assertThat(definitionCount()).isOne();
  }

  @ParameterizedTest
  @CsvSource({"false,NODE", "false,EDGE", "true,NODE", "true,EDGE"})
  @DisplayName("Excel 新建和更新均在预览及提交时拒绝超长文本")
  void shouldRejectOverlongExcelForCreateAndUpdate(boolean existing, String invalidField) {
    WorkflowDefinitionSpec spec = workflowWithTexts("Start", "true");
    if (existing) {
      TenantConfigBatchInitResponse seeded =
          initService.batchInit(workflowRequest(spec, false), "ref-admin", prefix);
      assertThat(seeded.results().getFirst().workflowDefinitions().created()).isOne();
    }
    String name = "NODE".equals(invalidField) ? "😀".repeat(257) : "Start";
    String expression = "EDGE".equals(invalidField) ? "😀".repeat(1025) : "true";
    Map<String, String> definition = Map.of(
        "workflow_code",
        spec.getWorkflowCode(),
        "workflow_name",
        "Length boundary",
        "workflow_type",
        "DAG",
        "version",
        "1");
    Map<String, String> start = Map.of(
        "workflow_code",
        spec.getWorkflowCode(),
        "workflow_version",
        "1",
        "node_code",
        "start",
        "node_name",
        name,
        "node_type",
        "START");
    Map<String, String> end = Map.of(
        "workflow_code",
        spec.getWorkflowCode(),
        "workflow_version",
        "1",
        "node_code",
        "end",
        "node_name",
        "End",
        "node_type",
        "END");
    Map<String, String> edge = Map.of(
        "workflow_code",
        spec.getWorkflowCode(),
        "workflow_version",
        "1",
        "from_node_code",
        "start",
        "to_node_code",
        "end",
        "edge_type",
        "SUCCESS",
        "condition_expr",
        expression);
    PackageExcelSession session = new PackageExcelSession(
        "workflow.xlsx",
        TENANT,
        Instant.now(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(definition),
        List.of(start, end),
        List.of(edge));
    String token = excelStore.save(session);
    try {
      var preview = excelService.preview(token);
      assertThat(preview.errorRows())
          .anyMatch(row -> row.messages().stream()
              .anyMatch(message -> message.contains(
                  "NODE".equals(invalidField) ? "node_name too long" : "condition_expr too long")));
      TenantConfigPackageExcelApplyRequest request = new TenantConfigPackageExcelApplyRequest();
      request.setPreviewToken(preview.previewToken());
      assertThatThrownBy(() -> excelService.apply(token, request))
          .hasMessage("error.excel.invalid_rows");
      assertThat(definitionCount()).isEqualTo(existing ? 1 : 0);
      assertThat(nodeCount()).isEqualTo(existing ? 2 : 0);
    } finally {
      excelStore.remove(token);
    }
  }

  private WorkflowDefinitionSpec workflowWithTexts(String nodeName, String condition) {
    WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec();
    spec.setWorkflowCode(prefix + "_length");
    spec.setWorkflowName("Length boundary");
    spec.setWorkflowType("DAG");
    spec.setEnabled(false);
    NodeSpec start = new NodeSpec();
    start.setNodeCode("start");
    start.setNodeName(nodeName);
    start.setNodeType("START");
    NodeSpec end = new NodeSpec();
    end.setNodeCode("end");
    end.setNodeName("End");
    end.setNodeType("END");
    spec.setNodes(List.of(start, end));
    EdgeSpec edge = new EdgeSpec();
    edge.setFromNodeCode("start");
    edge.setToNodeCode("end");
    edge.setEdgeType("SUCCESS");
    edge.setConditionExpr(condition);
    spec.setEdges(List.of(edge));
    return spec;
  }

  private TenantConfigBatchInitRequest workflowRequest(
      WorkflowDefinitionSpec spec, boolean dryRun) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT));
    request.setDryRun(dryRun);
    request.setWorkflowDefinitions(List.of(spec));
    return request;
  }

  @Test
  @DisplayName("合法同租户 Job 可创建启用 Workflow，草稿导入可保留未就绪引用")
  void shouldKeepValidAndDraftPathsUsable() {
    Long id = create("valid", "valid", true, 200);
    assertThat(id).isNotNull();
    TenantConfigBatchInitResponse response = importWorkflow("missing", false);
    assertThat(response.successTenants()).isEqualTo(1);
    assertThat(definitionCount()).isEqualTo(2);
  }

  private TenantConfigBatchInitResponse importWorkflow(String reference, boolean enabled) {
    return importWorkflow(reference, enabled, true);
  }

  private TenantConfigBatchInitResponse importWorkflow(
      String reference, boolean enabled, boolean strict) {
    WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec();
    spec.setWorkflowCode(prefix + "_import");
    spec.setWorkflowName("Reference admission");
    spec.setWorkflowType("DAG");
    spec.setEnabled(enabled);
    NodeSpec node = new NodeSpec();
    node.setNodeCode("job");
    node.setNodeName("Job");
    node.setNodeType("JOB");
    node.setRelatedJobCode(prefix + "_" + reference);
    NodeSpec start = new NodeSpec();
    start.setNodeCode("start");
    start.setNodeName("Start");
    start.setNodeType("START");
    NodeSpec end = new NodeSpec();
    end.setNodeCode("end");
    end.setNodeName("End");
    end.setNodeType("END");
    spec.setNodes(List.of(start, node, end));
    EdgeSpec first = new EdgeSpec();
    first.setFromNodeCode("start");
    first.setToNodeCode("job");
    first.setEdgeType("SUCCESS");
    EdgeSpec second = new EdgeSpec();
    second.setFromNodeCode("job");
    second.setToNodeCode("end");
    second.setEdgeType("SUCCESS");
    spec.setEdges(List.of(first, second));
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(TENANT));
    request.setStrict(strict);
    request.setWorkflowDefinitions(List.of(spec));
    return initService.batchInit(request, "ref-admin", prefix + "_import");
  }

  private Long create(String suffix, String reference, boolean enabled, int status) {
    final String selectIdSql = """
        select id from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;

    client
        .post()
        .uri("/api/console/workflow-definitions")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(suffix, reference, enabled))
        .exchange()
        .expectStatus()
        .isEqualTo(status);
    List<Long> ids =
        jdbcTemplate.queryForList(selectIdSql, Long.class, TENANT, prefix + "_" + suffix);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private String body(String suffix, String reference, boolean enabled) {
    return """
        {"tenantId":"int-wf-ta","workflowCode":"%s","workflowName":"Reference admission",
         "workflowType":"DAG","enabled":%s,
         "nodes":[{"nodeCode":"start","nodeName":"Start","nodeType":"START"},
                  {"nodeCode":"job","nodeName":"Job","nodeType":"JOB","relatedJobCode":"%s"},
                  {"nodeCode":"end","nodeName":"End","nodeType":"END"}],
         "edges":[{"fromNodeCode":"start","toNodeCode":"job","edgeType":"SUCCESS"},
                  {"fromNodeCode":"job","toNodeCode":"end","edgeType":"SUCCESS"}]}
        """.formatted(prefix + "_" + suffix, enabled, prefix + "_" + reference);
  }

  private void insertJob(String tenant, String suffix, boolean enabled) {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone,enabled)
          values (?,?,'Reference fixture','GENERAL','MANUAL','Asia/Shanghai',?)
          """;

    jdbcTemplate.update(insertJobDefinitionSql, tenant, prefix + "_" + suffix, enabled);
  }

  private int definitionCount() {
    final String selectCountSql = """
        select count(*) from batch.workflow_definition where tenant_id = ? and workflow_code like ?
    """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, prefix + "%");
  }

  private int nodeCount() {
    final String selectCountSql = """
          select count(*) from batch.workflow_node n\s\
          join batch.workflow_definition d on d.id=n.workflow_definition_id
          where d.tenant_id = ? and d.workflow_code like ?
          """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, prefix + "%");
  }
}
