package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/workflow-definitions → DB(workflow_definition + workflow_node +
 * workflow_edge)。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 DAG 全字段写入数据库,3 张表行一致
 *   <li>嵌套 nodes[].nodeCode 含空格 → 400(@Valid 下钻生效)
 *   <li>嵌套 edges[].fromNodeCode 含中文 → 400
 *   <li>workflowCode 重复 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("工作流定义写路径: DAG 三表落库,嵌套节点与边编码校验及唯一约束")
class ConsoleWorkflowDefinitionMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String workflowCode, String node1Code, String fromCode) {
    return """
        {
          "tenantId": "int-wf-ta",
          "workflowCode": "%s",
          "workflowName": "wf-it",
          "workflowType": "DAG",
          "enabled": false,
          "nodes": [
            {"nodeCode": "start", "nodeName": "start", "nodeType": "START"},
            {"nodeCode": "%s", "nodeName": "t1", "nodeType": "TASK"},
            {"nodeCode": "end", "nodeName": "end", "nodeType": "END"}
          ],
          "edges": [
            {"fromNodeCode": "%s", "toNodeCode": "%s"},
            {"fromNodeCode": "%s", "toNodeCode": "end"}
          ]
        }
        """.formatted(workflowCode, node1Code, fromCode, node1Code, node1Code)
        .stripTrailing();
  }

  private void acquireDesignLock(Long definitionId, String user, String roles) {
    client
        .put()
        .uri(
            "/api/console/workflow-definitions/{id}/lock?tenantId={tenantId}",
            definitionId,
            "int-wf-ta")
        .header("X-Console-User", user)
        .header("X-Console-Roles", roles)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
        .exchange()
        .expectStatus()
        .isOk();
  }

  private String fullUpdateBody(String definitionBody, int expectedVersion) {
    return """
        {"definition": %s, "expectedVersion": %d}
        """.formatted(definitionBody, expectedVersion);
  }

  @Test
  @DisplayName("创建 DAG 工作流: 定义,节点与边三张表分别落 1,3,2 行")
  void shouldCreateWorkflowWithAllThreeTables() {
    final String selectIdSql = """
      SELECT id FROM batch.workflow_definition WHERE workflow_code = ?
  """;
    final String selectCountSql = """
      SELECT COUNT(*) FROM batch.workflow_node WHERE workflow_definition_id = ?
  """;
    final String selectCountSql2 = """
      SELECT COUNT(*) FROM batch.workflow_edge WHERE workflow_definition_id = ?
  """;
    final String deleteWorkflowEdgeSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE id = ?
    """;

    String wfCode = "int_wf_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-" + wfCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(wfCode, "task1", "start"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("\"code\":\"SUCCESS\""));

    Long defId = jdbcTemplate.queryForObject(selectIdSql, Long.class, wfCode);
    assertThat(defId).isNotNull();

    Long nodeCount = jdbcTemplate.queryForObject(selectCountSql, Long.class, defId);
    assertThat(nodeCount).isEqualTo(3L);

    Long edgeCount = jdbcTemplate.queryForObject(selectCountSql2, Long.class, defId);
    assertThat(edgeCount).isEqualTo(2L);

    // 清理(按外键反向顺序)
    jdbcTemplate.update(deleteWorkflowEdgeSql, defId);
    jdbcTemplate.update(deleteWorkflowNodeSql, defId);
    jdbcTemplate.update(deleteWorkflowDefinitionSql, defId);
  }

  @Test
  @DisplayName("Workflow 策略允许租户管理员读写并拒绝租户用户修改")
  void shouldAuthorizeWorkflowMonitoringPolicyByTenantRole() {
    final String selectIdSql = """
        SELECT id FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?
    """;
    final String selectPolicySql = """
        SELECT soft_runtime_seconds, start_grace_seconds
        FROM batch.workflow_monitoring_policy WHERE tenant_id = ? AND workflow_definition_id = ?
    """;
    final String deleteWorkflowEdgeSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE id = ?
    """;

    String workflowCode = "int_wf_policy_auth_" + System.currentTimeMillis();
    Long definitionId = null;
    String policyBody = """
        {
          "tenantId": "int-wf-ta",
          "softRuntimeSeconds": 300,
          "softRuntimeSeverity": "ERROR",
          "startGraceSeconds": 120,
          "startGraceSeverity": "WARN",
          "completionDeadlineLocalTime": "04:30",
          "completionDeadlineDayOffset": 1,
          "completionDeadlineSeverity": "CRITICAL"
        }
        """;
    try {
      client
          .post()
          .uri("/api/console/workflow-definitions")
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "idem-wf-policy-create-" + workflowCode)
          .header("X-Console-User", "workflow-policy-admin")
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body(workflowCode, "task1", "start"))
          .exchange()
          .expectStatus()
          .isOk();

      definitionId =
          jdbcTemplate.queryForObject(selectIdSql, Long.class, "int-wf-ta", workflowCode);
      String policyPath = "/api/console/workflow-definitions/{id}/monitoring-policy";
      client
          .put()
          .uri(policyPath, definitionId)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-policy-save-" + workflowCode)
          .header("X-Console-User", "workflow-policy-admin")
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(policyBody)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body).contains("\"softRuntimeSeconds\":300"));

      client
          .get()
          .uri(policyPath + "?tenantId={tenantId}", definitionId, "int-wf-ta")
          .header("X-Console-User", "workflow-policy-admin")
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body)
              .contains("\"softRuntimeSeconds\":300")
              .contains("\"startGraceSeconds\":120"));

      client
          .put()
          .uri(policyPath, definitionId)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-policy-user-" + workflowCode)
          .header("X-Console-User", "workflow-policy-user")
          .header("X-Console-Roles", "ROLE_TENANT_USER")
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(policyBody)
          .exchange()
          .expectStatus()
          .isForbidden();

      Map<String, Object> stored =
          jdbcTemplate.queryForMap(selectPolicySql, "int-wf-ta", definitionId);
      assertThat(stored)
          .containsEntry("soft_runtime_seconds", 300)
          .containsEntry("start_grace_seconds", 120);
    } finally {
      if (definitionId != null) {
        jdbcTemplate.update(deleteWorkflowEdgeSql, definitionId);
        jdbcTemplate.update(deleteWorkflowNodeSql, definitionId);
        jdbcTemplate.update(deleteWorkflowDefinitionSql, definitionId);
      }
    }
  }

  @Test
  @DisplayName("拒绝不带版本与设计锁的旧式工作流图写入")
  void shouldRejectUnversionedWorkflowGraphWrite() {
    final String selectIdSql = """
        SELECT id FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?
    """;
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.workflow_node WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowEdgeSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE id = ?
    """;

    String workflowCode = "int_wf_unversioned_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-" + workflowCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(workflowCode, "task1", "start"))
        .exchange()
        .expectStatus()
        .isOk();

    Long definitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, "int-wf-ta", workflowCode);
    try {
      client
          .put()
          .uri("/api/console/workflow-definitions/{id}", definitionId)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "idem-wf-unversioned-update-" + workflowCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body(workflowCode, "replacement", "start"))
          .exchange()
          .expectStatus()
          .isEqualTo(405);

      Long nodeCount = jdbcTemplate.queryForObject(selectCountSql, Long.class, definitionId);
      assertThat(nodeCount).isEqualTo(3L);
    } finally {
      jdbcTemplate.update(deleteWorkflowEdgeSql, definitionId);
      jdbcTemplate.update(deleteWorkflowNodeSql, definitionId);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, definitionId);
    }
  }

  @Test
  @DisplayName("工作流敏感节点参数读取脱敏且掩码更新保留原凭据")
  void shouldMaskAndPreserveSensitiveNodeParams() {
    final String selectIdSql = """
        SELECT id FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?
    """;
    final String selectVersionSql = """
        SELECT version FROM batch.workflow_definition WHERE id = ?
    """;
    final String selectNodeParamsSql = """
        SELECT node_params->>'apiKey' AS api_key, node_params->>'label' AS label FROM batch.workflow_node\s\
        WHERE workflow_definition_id = ? AND node_code = ?\
        """;
    final String deleteWorkflowEdgeSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE id = ?
    """;

    String workflowCode = "int_wf_secret_" + System.currentTimeMillis();
    Long definitionId = null;
    try {
      String createBody = body(workflowCode, "task1", "start")
          .replace(
              "\"nodeType\": \"TASK\"",
              "\"nodeType\": \"TASK\", \"nodeParams\": "
                  + "\"{\\\"apiKey\\\":\\\"workflow-secret\\\","
                  + "\\\"label\\\":\\\"old\\\"}\"");
      String created = client
          .post()
          .uri("/api/console/workflow-definitions")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-secret-" + workflowCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(createBody)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();

      definitionId =
          jdbcTemplate.queryForObject(selectIdSql, Long.class, "int-wf-ta", workflowCode);
      assertThat(created).contains("****").doesNotContain("workflow-secret");
      String detail = client
          .get()
          .uri(
              "/api/console/workflow-definitions/{id}?tenantId={tenantId}",
              definitionId,
              "int-wf-ta")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      assertThat(detail).contains("****").doesNotContain("workflow-secret");

      String updateBody = createBody.replace("workflow-secret", "****").replace("old", "new");
      acquireDesignLock(definitionId, "workflow-secret-admin", "ROLE_ADMIN");
      int currentVersion =
          jdbcTemplate.queryForObject(selectVersionSql, Integer.class, definitionId);
      String updated = client
          .put()
          .uri("/api/console/workflow-definitions/{id}/full", definitionId)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "idem-wf-secret-update-" + workflowCode)
          .header("X-Console-User", "workflow-secret-admin")
          .header("X-Console-Roles", "ROLE_ADMIN")
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(fullUpdateBody(updateBody, currentVersion))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();

      assertThat(updated).contains("****").doesNotContain("workflow-secret");
      var storedParams = jdbcTemplate.queryForMap(selectNodeParamsSql, definitionId, "task1");
      assertThat(storedParams).containsEntry("api_key", "workflow-secret");
      assertThat(storedParams).containsEntry("label", "new");
    } finally {
      if (definitionId != null) {
        jdbcTemplate.update(deleteWorkflowEdgeSql, definitionId);
        jdbcTemplate.update(deleteWorkflowNodeSql, definitionId);
        jdbcTemplate.update(deleteWorkflowDefinitionSql, definitionId);
      }
    }
  }

  @Test
  @DisplayName("租户管理员不能在工作流节点上指定物理 Worker 分组")
  void shouldRejectTenantAdminAssigningNodeWorkerGroup() {
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?
    """;

    String workflowCode = "int_wf_tenant_route_" + System.currentTimeMillis();
    String routedBody = body(workflowCode, "task1", "start")
        .replace("\"nodeType\": \"TASK\"", "\"nodeType\": \"TASK\", \"workerGroup\": \"IMPORT\"");

    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-route-" + workflowCode)
        .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(routedBody)
        .exchange()
        .expectStatus()
        .isForbidden();

    Long count = jdbcTemplate.queryForObject(selectCountSql, Long.class, "int-wf-ta", workflowCode);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("租户管理员更新工作流时省略物理路由字段不清除平台分配")
  void shouldPreserveNodeWorkerGroup_whenTenantAdminUpdatesWorkflow() {
    final String selectIdSql = """
        SELECT id FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?
    """;
    final String selectVersionSql = """
        SELECT version FROM batch.workflow_definition WHERE id = ?
    """;
    final String selectWorkerGroupSql = """
        SELECT worker_group FROM batch.workflow_node WHERE workflow_definition_id = ? AND node_code = ?
    """;
    final String deleteWorkflowEdgeSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE id = ?
    """;

    String workflowCode = "int_wf_route_preserve_" + System.currentTimeMillis();
    String routedBody = body(workflowCode, "task1", "start")
        .replace("\"nodeType\": \"TASK\"", "\"nodeType\": \"TASK\", \"workerGroup\": \"IMPORT\"");
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-route-seed-" + workflowCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(routedBody)
        .exchange()
        .expectStatus()
        .isOk();

    Long definitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, "int-wf-ta", workflowCode);
    try {
      acquireDesignLock(definitionId, "workflow-route-admin", "ROLE_TENANT_ADMIN");
      int currentVersion =
          jdbcTemplate.queryForObject(selectVersionSql, Integer.class, definitionId);
      client
          .put()
          .uri("/api/console/workflow-definitions/{id}/full", definitionId)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "idem-wf-route-update-" + workflowCode)
          .header("X-Console-User", "workflow-route-admin")
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(fullUpdateBody(body(workflowCode, "task1", "start"), currentVersion))
          .exchange()
          .expectStatus()
          .isOk();

      String workerGroup =
          jdbcTemplate.queryForObject(selectWorkerGroupSql, String.class, definitionId, "task1");
      assertThat(workerGroup).isEqualTo("IMPORT");
    } finally {
      jdbcTemplate.update(deleteWorkflowEdgeSql, definitionId);
      jdbcTemplate.update(deleteWorkflowNodeSql, definitionId);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, definitionId);
    }
  }

  @Test
  @DisplayName("嵌套节点编码含空格: 返回校验错误且不落库")
  void shouldRejectNestedInvalidNodeCode() {
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.workflow_definition WHERE workflow_code = ?
    """;

    // 嵌套 nodes[].nodeCode 含空格 → @Valid 下钻 → 400
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("int_wf_bad_node", "bad node", "start"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    // 守护:异常数据不入库
    Long cnt = jdbcTemplate.queryForObject(selectCountSql, Long.class, "int_wf_bad_node");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("创建 Workflow 时拒绝汇聚阈值小数或溢出且不写入定义")
  void shouldRejectInvalidJoinThresholdWithoutPersistingWorkflow() {
    String tenant = "wf-join-" + System.nanoTime();
    String count =
        "select count(*) from batch.workflow_definition where tenant_id = ? and workflow_code = ?";
    for (String threshold : List.of("1.5", "4294967297")) {
      String code = "wf_join_bad_" + threshold.replace('.', '_');
      Map<String, Object> request = joinThresholdRequest(tenant, code, threshold);
      client
          .post()
          .uri("/api/console/workflow-definitions")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + tenant + "-" + code)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(request)
          .exchange()
          .expectStatus()
          .isBadRequest();
      assertThat(jdbcTemplate.queryForObject(count, Long.class, tenant, code)).isZero();
    }
    String validCode = "wf_join_valid";
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + tenant + "-valid")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(joinThresholdRequest(tenant, validCode, "1.0"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(jdbcTemplate.queryForObject(count, Long.class, tenant, validCode))
        .isEqualTo(1L);
  }

  private Map<String, Object> joinThresholdRequest(String tenant, String code, String threshold) {
    return Map.of(
        "tenantId",
        tenant,
        "workflowCode",
        code,
        "workflowName",
        "join test",
        "workflowType",
        "DAG",
        "enabled",
        false,
        "nodes",
        List.of(
            Map.of("nodeCode", "start", "nodeName", "start", "nodeType", "START"),
            Map.of("nodeCode", "a", "nodeName", "a", "nodeType", "TASK"),
            Map.of("nodeCode", "b", "nodeName", "b", "nodeType", "TASK"),
            Map.of(
                "nodeCode",
                "gw",
                "nodeName",
                "gw",
                "nodeType",
                "GATEWAY",
                "nodeParams",
                "{\"joinMode\":\"N_OF\",\"joinThreshold\":" + threshold + "}"),
            Map.of("nodeCode", "end", "nodeName", "end", "nodeType", "END")),
        "edges",
        List.of(
            Map.of("fromNodeCode", "start", "toNodeCode", "a"),
            Map.of("fromNodeCode", "start", "toNodeCode", "b"),
            Map.of("fromNodeCode", "a", "toNodeCode", "gw"),
            Map.of("fromNodeCode", "b", "toNodeCode", "gw"),
            Map.of("fromNodeCode", "gw", "toNodeCode", "end")));
  }

  @Test
  @DisplayName("单入边网关显式无效汇聚模式时 HTTP 拒绝且不写入")
  void shouldRejectInvalidBranchJoinModeWithoutPersisting() {
    String code = "wf_branch_bad_" + System.nanoTime();
    String count =
        "select count(*) from batch.workflow_definition where tenant_id = ? and workflow_code = ?";
    String request = body(code, "gateway", "start")
        .replace(
            "\"nodeType\": \"TASK\"",
            "\"nodeType\": \"GATEWAY\", \"nodeParams\": \"{\\\"joinMode\\\":\\\"BAD\\\"}\"");
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isBadRequest();
    assertThat(jdbcTemplate.queryForObject(count, Long.class, "int-wf-ta", code))
        .isZero();
  }

  @Test
  @DisplayName("普通节点显式无效汇聚模式时 HTTP 拒绝且不写入")
  void shouldRejectInvalidTaskJoinModeWithoutPersisting() {
    String code = "wf_task_join_bad_" + System.nanoTime();
    String count =
        "select count(*) from batch.workflow_definition where tenant_id = ? and workflow_code = ?";
    String request = body(code, "task1", "start")
        .replace(
            "\"nodeType\": \"TASK\"",
            "\"nodeType\": \"TASK\", \"nodeParams\": \"{\\\"joinMode\\\":\\\"BAD\\\"}\"");
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isBadRequest();
    assertThat(jdbcTemplate.queryForObject(count, Long.class, "int-wf-ta", code))
        .isZero();
  }

  @Test
  @DisplayName("嵌套边起点编码为中文: 返回请求不合法")
  void shouldRejectNestedChineseEdgeNodeCode() {
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("int_wf_bad_edge", "task1", "中文"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  @DisplayName("工作流编码重复: 第二次写入失败,不产生重复定义")
  void shouldRejectDuplicateWorkflowCode() {
    final String selectIdSql = """
        SELECT id FROM batch.workflow_definition WHERE workflow_code = ?
    """;
    final String deleteWorkflowEdgeSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE id = ?
    """;

    String wfCode = "int_wf_dup_" + System.currentTimeMillis();
    // 首次成功
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-dup-1-" + wfCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(wfCode, "task1", "start"))
        .exchange()
        .expectStatus()
        .isOk();

    // 重复创建 → 撞 (tenant_id, workflow_code) 唯一约束
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-dup-2-" + wfCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(wfCode, "task1", "start"))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    // 清理
    Long defId = jdbcTemplate.queryForObject(selectIdSql, Long.class, wfCode);
    if (defId != null) {
      jdbcTemplate.update(deleteWorkflowEdgeSql, defId);
      jdbcTemplate.update(deleteWorkflowNodeSql, defId);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, defId);
    }
  }

  @Test
  @DisplayName("工作流编码含空格: 返回请求不合法")
  void shouldRejectInvalidWorkflowCode() {
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-bad-code")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q", "task1", "start"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
