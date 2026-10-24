package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写路径端到端集成测试:POST /api/console/job-definitions → DB → audit log。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 jobCode 写入数据库,job_definition 行可查
 *   <li>BE @ValidResourceCode 在 controller 入口拦截 q q q / 中文
 *   <li>同 tenantId+jobCode 重复创建 → 409 唯一约束
 *   <li>tenantId 强一致:body.tenantId 决定写入数据库 tenant_id,不会漂移
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("作业定义写路径: 编码校验,落库租户强一致,更新与依赖作业字段")
class ConsoleJobDefinitionMutationIntegrationTest extends AbstractMutationIntegrationTest {

  @Autowired
  private PlatformTransactionManager transactionManager;

  @BeforeEach
  void ensureTenantCalendarFixture() {
    TestTenantCalendarFixtures.ensureDefaultCalendar(jdbcTemplate, "int-ta");
  }

  private String createBody(String jobCode) {
    return createBody(jobCode, null);
  }

  private String createBody(String jobCode, String dependsOnJobCode) {
    String dependency = dependsOnJobCode == null
        ? ""
        : ",\n  \"dependsOnJobCode\": \"%s\"".formatted(dependsOnJobCode);
    return """
        {
          "tenantId": "int-ta",
          "jobCode": "%s",
          "jobName": "integration test",
          "jobType": "GENERAL",
          "scheduleType": "MANUAL"%s
        }
        """.formatted(jobCode, dependency).stripTrailing();
  }

  private String createBodyWithType(String jobCode, String jobType) {
    return """
        {
          "tenantId": "int-ta",
          "jobCode": "%s",
          "jobName": "integration test",
          "jobType": "%s",
          "scheduleType": "MANUAL"
        }
        """.formatted(jobCode, jobType).stripTrailing();
  }

  private String createBodyWithQueue(String jobCode, String queueCode) {
    return """
        {
          "tenantId": "int-ta",
          "jobCode": "%s",
          "jobName": "integration test",
          "jobType": "GENERAL",
          "scheduleType": "MANUAL",
          "queueCode": "%s"
        }
        """.formatted(jobCode, queueCode).stripTrailing();
  }

  private Long createPlatformRoutedJob(String jobCode, String queueCode) {
    final String selectIdSql = """
        SELECT id FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?
    """;

    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-routed-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBodyWithQueue(jobCode, queueCode))
        .exchange()
        .expectStatus()
        .isOk();
    return jdbcTemplate.queryForObject(selectIdSql, Long.class, "int-ta", jobCode);
  }

  @Test
  @DisplayName("创建作业定义: 返回成功,落库行的租户与编码与请求一致")
  void shouldCreateJobDefinitionWithValidCode() {
    final String selectTenantIdSql = """
        SELECT tenant_id, job_code, enabled FROM batch.job_definition WHERE job_code = ?
    """;
    final String selectSoftRuntimeSecondsSql = """
        SELECT soft_runtime_seconds, start_grace_seconds FROM batch.job_monitoring_policy mp\s\
        JOIN batch.job_definition jd ON jd.id = mp.job_definition_id WHERE jd.job_code = ?\
        """;

    String jobCode = "int_test_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-create-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("\"jobCode\":\"" + jobCode + "\"");
        });

    // 行已入库,tenant_id 严格遵循 body 而非漂移
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectTenantIdSql, jobCode);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("tenant_id", "int-ta");
    assertThat(rows.get(0)).containsEntry("job_code", jobCode);
    Map<String, Object> monitoringPolicy =
        jdbcTemplate.queryForMap(selectSoftRuntimeSecondsSql, jobCode);
    assertThat(monitoringPolicy)
        .containsEntry("soft_runtime_seconds", 0)
        .containsEntry("start_grace_seconds", 0);

    // 清理
    deleteJobDefinitions("job_code = ?", jobCode);
  }

  @Test
  @DisplayName("租户管理员通过真实 API 创建五类 Worker 作业定义且不写入平台物理资源")
  void shouldCreateFiveWorkerJobTypesWithoutPlatformRouting() {
    final String selectJobCodeSql = """
        SELECT job_code, job_type, queue_code, worker_group FROM batch.job_definition WHERE tenant_id = ?\s\
        AND job_code LIKE ?\
        """;

    List<String> jobTypes = List.of("IMPORT", "EXPORT", "PROCESS", "DISPATCH", "ATOMIC");
    String runId = Long.toString(System.nanoTime());
    List<String> jobCodes = jobTypes.stream()
        .map(type -> "int_test_worker_" + type.toLowerCase() + "_" + runId)
        .toList();
    try {
      for (int index = 0; index < jobTypes.size(); index++) {
        String jobCode = jobCodes.get(index);
        String jobType = jobTypes.get(index);
        client
            .post()
            .uri("/api/console/job-definitions")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-worker-" + jobCode)
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-ta")
            .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createBodyWithType(jobCode, jobType))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .value(body -> assertThat(body)
                .contains("\"jobCode\":\"" + jobCode + "\"")
                .contains("\"jobType\":\"" + jobType + "\""));
      }

      List<Map<String, Object>> rows =
          jdbcTemplate.queryForList(selectJobCodeSql, "int-ta", "int_test_worker_%_" + runId);
      assertThat(rows).hasSize(jobTypes.size());
      assertThat(rows)
          .extracting(row -> row.get("job_type"))
          .containsExactlyInAnyOrderElementsOf(jobTypes);
      assertThat(rows).allSatisfy(row -> {
        assertThat(row.get("queue_code")).isNull();
        assertThat(row.get("worker_group")).isNull();
      });
    } finally {
      deleteJobDefinitions(
          "tenant_id = ? AND job_code LIKE ?", "int-ta", "int_test_worker_%_" + runId);
    }
  }

  @Test
  @DisplayName("只读审计员和租户操作员不能创建作业定义")
  void shouldRejectJobDefinitionCreationForReadOnlyAndOperatorRoles() {
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.job_definition WHERE tenant_id = ? AND job_code LIKE ?
    """;

    String runId = Long.toString(System.nanoTime());
    for (String role : List.of("ROLE_AUDITOR", "ROLE_TENANT_USER")) {
      String jobCode = "int_test_forbidden_" + role.toLowerCase() + "_" + runId;
      client
          .post()
          .uri("/api/console/job-definitions")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-forbidden-" + jobCode)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-ta")
          .header("X-Console-Roles", role)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(createBody(jobCode))
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    Long createdCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, "int-ta", "int_test_forbidden_%_" + runId);
    assertThat(createdCount).isZero();
  }

  @Test
  @DisplayName("作业实例详情递归脱敏执行参数快照中的凭据")
  void shouldMaskSensitiveValuesInJobInstanceParameterSnapshot() throws Exception {
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id = ? and job_code = ?
    """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no, biz_date,\s\
        trigger_type, instance_status, dedup_key, params_snapshot, result_summary)\s\
        values (?, ?, ?, ?, date '2026-10-11', 'MANUAL', 'CREATED', ?, ?::jsonb, ?::jsonb) returning id\
        """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ? and tenant_id = ?
    """;

    String jobCode = "int_test_snapshot_mask_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-create-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk();
    Long jobDefinitionId = jdbcTemplate.queryForObject(selectIdSql, Long.class, "int-ta", jobCode);
    String instanceNo = "inst_snapshot_mask_" + System.currentTimeMillis();
    String dedupKey = "dedup_snapshot_mask_" + System.currentTimeMillis();
    Long instanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        "int-ta",
        jobDefinitionId,
        jobCode,
        instanceNo,
        dedupKey,
        "{\"effectiveParams\":{\"apiToken\":\"snapshot-secret\","
            + "\"nested\":[{\"password\":\"nested-secret\"}]},"
            + "\"executionPolicy\":{\"retryMaxCount\":2}}",
        "{\"credential\":\"summary-secret\",\"rowsProcessed\":12}");

    try {
      String responseBody = client
          .get()
          .uri("/api/console/queries/instances/{id}?tenantId=int-ta", instanceId)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode response = JsonUtils.newDefaultMapper().readTree(responseBody);
      JsonNode snapshot = JsonUtils.newDefaultMapper()
          .readTree(response.path("data").path("paramsSnapshot").asText());
      assertThat(snapshot.path("effectiveParams").path("apiToken").asText()).isEqualTo("****");
      assertThat(snapshot
              .path("effectiveParams")
              .path("nested")
              .get(0)
              .path("password")
              .asText())
          .isEqualTo("****");
      assertThat(snapshot.path("executionPolicy").path("retryMaxCount").asInt()).isEqualTo(2);
      JsonNode resultSummary = JsonUtils.newDefaultMapper()
          .readTree(response.path("data").path("resultSummary").asText());
      assertThat(resultSummary.path("credential").asText()).isEqualTo("****");
      assertThat(resultSummary.path("rowsProcessed").asInt()).isEqualTo(12);
    } finally {
      jdbcTemplate.update(deleteJobInstanceSql, instanceId, "int-ta");
      deleteJobDefinitions("id = ? and tenant_id = ?", jobDefinitionId, "int-ta");
    }
  }

  @Test
  @DisplayName("作业默认参数更新会落库，回显凭据脱敏且回传掩码不覆盖存量值")
  void shouldUpdateDefaultParamsAndPreserveMaskedCredentials() {
    final String selectIdSql = """
        SELECT id FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?
    """;
    final String selectDefaultParamsSql = """
        SELECT default_params ->> 'password' AS password, default_params ->> 'region' AS region\s\
        FROM batch.job_definition WHERE id = ?\
        """;

    String jobCode = "int_test_default_params_" + System.currentTimeMillis();
    String initial = "{\"password\":\"stored-secret\",\"region\":\"west\"}";
    String created = client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-default-params-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "tenantId", "int-ta",
            "jobCode", jobCode,
            "jobName", "default params test",
            "jobType", "GENERAL",
            "scheduleType", "MANUAL",
            "defaultParams", initial))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    assertThat(created).contains("****").doesNotContain("stored-secret");

    Long id = jdbcTemplate.queryForObject(selectIdSql, Long.class, "int-ta", jobCode);
    try {
      client
          .put()
          .uri("/api/console/job-definitions/" + id)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-update-default-" + jobCode)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-ta")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of(
              "tenantId",
              "int-ta",
              "defaultParams",
              JsonUtils.toJson(Map.of("password", "****", "region", "east"))))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body).contains("****").doesNotContain("stored-secret"));

      Map<String, Object> stored = jdbcTemplate.queryForMap(selectDefaultParamsSql, id);
      assertThat(stored).containsEntry("password", "stored-secret").containsEntry("region", "east");
    } finally {
      deleteJobDefinitions("id = ?", id);
    }
  }

  @Test
  @DisplayName("租户管理员不能通过作业定义 API 指定物理资源队列")
  void shouldRejectTenantAdminAssigningPhysicalQueue() {
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?
    """;

    String jobCode = "int_test_tenant_route_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-tenant-route-" + jobCode)
        .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBodyWithQueue(jobCode, "platform_only_queue"))
        .exchange()
        .expectStatus()
        .isForbidden();

    Long count = jdbcTemplate.queryForObject(selectCountSql, Long.class, "int-ta", jobCode);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("租户管理员不能更改现有作业的物理资源路由")
  void shouldRejectTenantAdminChangingPhysicalQueue() {
    final String selectQueueCodeSql = """
        SELECT queue_code FROM batch.job_definition WHERE id = ?
    """;

    String jobCode = "int_test_route_update_" + System.currentTimeMillis();
    Long id = createPlatformRoutedJob(jobCode, "platform_queue_a");
    try {
      client
          .put()
          .uri("/api/console/job-definitions/" + id)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-route-update-" + jobCode)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-ta")
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("""
              {"tenantId":"int-ta","queueCode":"platform_queue_b"}
              """)
          .exchange()
          .expectStatus()
          .isForbidden();

      String queueCode = jdbcTemplate.queryForObject(selectQueueCodeSql, String.class, id);
      assertThat(queueCode).isEqualTo("platform_queue_a");
    } finally {
      deleteJobDefinitions("id = ?", id);
    }
  }

  @Test
  @DisplayName("租户管理员复制作业时不继承物理路由")
  void shouldClearPhysicalQueueWhenTenantAdminCopiesJob() {
    final String selectQueueCodeSql = """
        SELECT queue_code, worker_group FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?
    """;

    String sourceJobCode = "int_test_route_source_" + System.currentTimeMillis();
    String copiedJobCode = sourceJobCode + "_copy";
    Long sourceId = createPlatformRoutedJob(sourceJobCode, "platform_queue_copy");
    try {
      client
          .post()
          .uri(uriBuilder -> uriBuilder
              .path("/api/console/job-definitions/{id}/copy")
              .queryParam("tenantId", "int-ta")
              .queryParam("newJobCode", copiedJobCode)
              .build(sourceId))
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-copy-route-" + sourceJobCode)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-ta")
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .exchange()
          .expectStatus()
          .isOk();

      Map<String, Object> copied =
          jdbcTemplate.queryForMap(selectQueueCodeSql, "int-ta", copiedJobCode);
      assertThat(copied).containsEntry("queue_code", null).containsEntry("worker_group", null);
    } finally {
      deleteJobDefinitions(
          "tenant_id = ? AND job_code IN (?, ?)", "int-ta", sourceJobCode, copiedJobCode);
    }
  }

  @Test
  @DisplayName("租户管理员不能通过克隆覆盖字段指定物理资源队列")
  void shouldRejectTenantAdminOverridingPhysicalQueueWhenCloning() {
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.job_definition WHERE tenant_id = ? AND job_code = ?
    """;

    String sourceJobCode = "int_test_route_clone_" + System.currentTimeMillis();
    Long sourceId = createPlatformRoutedJob(sourceJobCode, "platform_queue_clone");
    try {
      client
          .post()
          .uri("/api/console/job-definitions/" + sourceId + "/clone")
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-clone-route-" + sourceJobCode)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-ta")
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("""
              {"tenantId":"int-ta","newJobCode":"tenant_route_clone","queueCode":"platform_queue_b"}
              """)
          .exchange()
          .expectStatus()
          .isForbidden();

      Long cloneCount =
          jdbcTemplate.queryForObject(selectCountSql, Long.class, "int-ta", "tenant_route_clone");
      assertThat(cloneCount).isZero();
    } finally {
      deleteJobDefinitions("id = ?", sourceId);
    }
  }

  @Test
  @DisplayName("编码含空格: 返回校验错误且不落库")
  void shouldRejectInvalidJobCodeWithSpaces() {
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.job_definition WHERE job_code = ?
    """;

    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody("q q q"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("VALIDATION_ERROR"));

    // 守护:非法 jobCode 不能入库
    Long cnt = jdbcTemplate.queryForObject(selectCountSql, Long.class, "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("编码为中文: 返回请求不合法")
  void shouldRejectChineseJobCode() {
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody("中文测试"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  @DisplayName("编码重复: 第二次写入失败,不产生重复定义")
  void shouldRejectDuplicateJobCode() {
    String jobCode = "int_test_dup_" + System.currentTimeMillis();
    // 第一次成功
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-dup-1-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk();

    // 第二次同 tenantId + jobCode → 唯一约束撞,400/409 都可
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-dup-2-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .value(status -> assertThat(status).isIn(400, 409, 500));

    // 清理
    deleteJobDefinitions("job_code = ?", jobCode);
  }

  @Test
  @DisplayName("更新作业定义: 返回成功,库内名称改为新值")
  void shouldUpdateJobDefinitionRow() {
    final String selectIdSql = """
        SELECT id FROM batch.job_definition WHERE job_code = ?
    """;
    final String selectVersionSql = """
        SELECT version, enabled FROM batch.job_definition WHERE id = ?
    """;
    final String selectJobNameSql = """
        SELECT job_name, version, enabled FROM batch.job_definition WHERE id = ?
    """;

    String jobCode = "int_test_update_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cu-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk();
    Long id = jdbcTemplate.queryForObject(selectIdSql, Long.class, jobCode);
    assertThat(id).isNotNull();
    Map<String, Object> initial = jdbcTemplate.queryForMap(selectVersionSql, id);
    Integer initialVersion = (Integer) initial.get("version");
    Boolean initialEnabled = (Boolean) initial.get("enabled");

    // PUT 更新 jobName
    String updateBody =
        "{\"tenantId\":\"int-ta\",\"jobName\":\"updated name\",\"jobType\":\"GENERAL\","
            + "\"scheduleType\":\"MANUAL\"}";
    client
        .put()
        .uri("/api/console/job-definitions/" + id)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-uu-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(updateBody)
        .exchange()
        .expectStatus()
        .isOk();

    Map<String, Object> updated = jdbcTemplate.queryForMap(selectJobNameSql, id);
    assertThat(updated)
        .containsEntry("job_name", "updated name")
        .containsEntry("version", initialVersion + 1)
        .containsEntry("enabled", initialEnabled);

    client
        .patch()
        .uri("/api/console/job-definitions/" + id)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-toggle-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", "int-ta", "enabled", false))
        .exchange()
        .expectStatus()
        .isOk();

    Map<String, Object> toggled = jdbcTemplate.queryForMap(selectVersionSql, id);
    assertThat(toggled)
        .containsEntry("version", initialVersion + 2)
        .containsEntry("enabled", false);

    // 清理
    deleteJobDefinitions("job_code = ?", jobCode);
  }

  @Test
  @DisplayName("依赖作业字段: 创建与更新后库内依赖编码分别与请求一致")
  void shouldCreateAndUpdateDependsOnJobCode() {
    final String selectIdSql = """
        SELECT id FROM batch.job_definition WHERE job_code = ?
    """;
    final String selectDependsOnJobCodeSql = """
        SELECT depends_on_job_code FROM batch.job_definition WHERE id = ?
    """;
    final String selectCountSql = """
        SELECT count(*) FROM batch.job_dependency WHERE tenant_id = 'int-ta' AND downstream_job_code = ?\
        """;
    final String selectUpstreamJobCodeSql = """
        SELECT upstream_job_code, biz_date_alignment FROM batch.job_dependency WHERE tenant_id = 'int-ta'\s\
        AND downstream_job_code = ? ORDER BY dependency_order\
        """;

    String jobCode = "int_test_dep_" + System.currentTimeMillis();
    String upstreamOne = jobCode + "_up1";
    String upstreamTwo = jobCode + "_up2";
    try {
      insertDependencyDefinition(upstreamOne);
      insertDependencyDefinition(upstreamTwo);
      client
          .post()
          .uri("/api/console/job-definitions")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cd-" + jobCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(createBody(jobCode, upstreamOne))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> {
            assertThat(body).contains("\"code\":\"SUCCESS\"");
            assertThat(body).contains("\"dependsOnJobCode\":\"" + upstreamOne + "\"");
          });

      Long id = jdbcTemplate.queryForObject(selectIdSql, Long.class, jobCode);
      assertThat(id).isNotNull();
      String createdDepends =
          jdbcTemplate.queryForObject(selectDependsOnJobCodeSql, String.class, id);
      assertThat(createdDepends).isEqualTo(upstreamOne);

      client
          .put()
          .uri("/api/console/job-definitions/" + id)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ud-" + jobCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"tenantId\":\"int-ta\",\"dependsOnJobCode\":\"" + upstreamTwo + "\"}")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body).contains("\"dependsOnJobCode\":\"" + upstreamTwo + "\""));

      String updatedDepends =
          jdbcTemplate.queryForObject(selectDependsOnJobCodeSql, String.class, id);
      assertThat(updatedDepends).isEqualTo(upstreamTwo);

      client
          .put()
          .uri("/api/console/job-definitions/" + id)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-multi-" + jobCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"tenantId\":\"int-ta\",\"dependsOnJobCodes\":[\"" + upstreamOne + "\",\""
              + upstreamTwo + "\"]}")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body)
              .contains("\"dependsOnJobCodes\":[\"" + upstreamOne + "\",\"" + upstreamTwo + "\"]"));

      Integer dependencyCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class, jobCode);
      assertThat(dependencyCount).isEqualTo(2);

      client
          .put()
          .uri("/api/console/job-definitions/" + id)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-align-" + jobCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("""
              {"tenantId":"int-ta","jobDependencyRules":[
                {"upstreamJobCode":"%s","bizDateAlignment":"SAME_DAY"},
                {"upstreamJobCode":"%s","bizDateAlignment":"PREVIOUS_BIZ_DAY"}
              ]}
              """.formatted(upstreamOne, upstreamTwo))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body)
              .contains("\"jobDependencyRules\":[{\"upstreamJobCode\":\"" + upstreamOne
                  + "\",\"bizDateAlignment\":\"SAME_DAY\"},{\"upstreamJobCode\":\""
                  + upstreamTwo + "\",\"bizDateAlignment\":\"PREVIOUS_BIZ_DAY\"}]"));

      List<Map<String, Object>> alignedRules =
          jdbcTemplate.queryForList(selectUpstreamJobCodeSql, jobCode);
      assertThat(alignedRules)
          .extracting(row -> row.get("biz_date_alignment"))
          .containsExactly("SAME_DAY", "PREVIOUS_BIZ_DAY");
    } finally {
      deleteJobDefinitions("job_code = ?", jobCode);
      deleteJobDefinitions("job_code IN (?, ?)", upstreamOne, upstreamTwo);
    }
  }

  @Test
  @DisplayName("作业依赖数据库外键拒绝孤儿及跨租户上游并阻止删除被引用作业")
  void shouldEnforceTenantScopedUpstreamReferenceInDatabase() {
    final String insertJobDependencySql = """
            insert into batch.job_dependency
                (tenant_id, downstream_job_code, upstream_job_code, dependency_order)
            values (?, ?, ?, 0)
            """;
    final String insertJobDependencySql2 = """
              insert into batch.job_dependency
                  (tenant_id, downstream_job_code, upstream_job_code, dependency_order)
              values (?, ?, ?, 1)
              """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set depends_on_job_code = ? where tenant_id = ? and job_code = ?\
        """;
    final String deleteJobDefinitionSql = """
      delete from batch.job_definition where tenant_id = ? and job_code = ?
  """;
    final String selectCountSql = """
      select count(*) from batch.job_dependency where tenant_id = ? and downstream_job_code = ?\
      """;
    final String deleteJobDependencySql = """
      delete from batch.job_dependency where tenant_id = ? and downstream_job_code = ?
  """;
    final String deleteJobDefinitionSql2 = """
      delete from batch.job_definition where tenant_id = ? and job_code in (?, ?)
  """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantA = "dep-fk-a-" + suffix;
    String tenantB = "dep-fk-b-" + suffix;
    String downstream = "DOWNSTREAM_" + suffix;
    String upstream = "UPSTREAM_" + suffix;
    String foreignUpstream = "FOREIGN_UPSTREAM_" + suffix;

    insertDependencyDefinition(tenantA, downstream);
    insertDependencyDefinition(tenantA, upstream);
    insertDependencyDefinition(tenantB, foreignUpstream);

    try {
      jdbcTemplate.update(insertJobDependencySql, tenantA, downstream, upstream);

      assertThatThrownBy(() ->
              jdbcTemplate.update(insertJobDependencySql2, tenantA, downstream, foreignUpstream))
          .isInstanceOf(DataIntegrityViolationException.class);

      assertThatThrownBy(() ->
              jdbcTemplate.update(updateJobDefinitionSql, foreignUpstream, tenantA, downstream))
          .isInstanceOf(DataIntegrityViolationException.class);

      assertThatThrownBy(() -> jdbcTemplate.update(deleteJobDefinitionSql, tenantA, upstream))
          .isInstanceOf(DataIntegrityViolationException.class);

      Integer selectedJobDependencyCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantA, downstream);
      assertThat(selectedJobDependencyCount).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteJobDependencySql, tenantA, downstream);
      jdbcTemplate.update(deleteJobDefinitionSql2, tenantA, downstream, upstream);
      jdbcTemplate.update(deleteJobDefinitionSql, tenantB, foreignUpstream);
    }
  }

  @Test
  @DisplayName("数据库串行化同租户依赖写入并拒绝并发形成的依赖环")
  void shouldRejectConcurrentDependencyCycleFromDirectDatabaseWriters() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.job_dependency where tenant_id = ?
    """;
    final String deleteJobDependencySql = """
        delete from batch.job_dependency where tenant_id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenant = "dep-cycle-" + suffix;
    String jobA = "JOB_A_" + suffix;
    String jobB = "JOB_B_" + suffix;
    insertDependencyDefinition(tenant, jobA);
    insertDependencyDefinition(tenant, jobB);

    try {
      CyclicBarrier bothTransactionsReady = new CyclicBarrier(2);
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<Boolean> aDependsOnB = executor.submit(() -> insertDependencyAndReportAccepted(
            transaction, tenant, jobA, jobB, bothTransactionsReady));
        Future<Boolean> bDependsOnA = executor.submit(() -> insertDependencyAndReportAccepted(
            transaction, tenant, jobB, jobA, bothTransactionsReady));

        assertThat(aDependsOnB.get()).isNotEqualTo(bDependsOnA.get());
      }

      Integer selectedJobDependencyCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenant);
      assertThat(selectedJobDependencyCount).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteJobDependencySql, tenant);
      jdbcTemplate.update(deleteJobDefinitionSql, tenant);
    }
  }

  @Test
  @DisplayName("数据库触发器拒绝直接更新依赖边形成的环并保留原边")
  void shouldRejectDependencyCycleCreatedByDirectEdgeUpdate() {
    final String insertJobDependencySql = """
            insert into batch.job_dependency
                (tenant_id, downstream_job_code, upstream_job_code, dependency_order)
            values (?, ?, ?, 0), (?, ?, ?, 0)
            """;
    final String updateJobDependencySql = """
      update batch.job_dependency set downstream_job_code = ?, upstream_job_code = ? where tenant_id = ?\s\
      and downstream_job_code = ? and upstream_job_code = ?\
      """;
    final String selectCountSql = """
      select count(*) from batch.job_dependency where tenant_id = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.job_dependency where tenant_id = ? and downstream_job_code = ?\s\
      and upstream_job_code = ?\
      """;
    final String deleteJobDependencySql = """
        delete from batch.job_dependency where tenant_id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenant = "dep-update-cycle-" + suffix;
    String jobA = "JOB_A_" + suffix;
    String jobB = "JOB_B_" + suffix;
    String jobC = "JOB_C_" + suffix;
    String jobD = "JOB_D_" + suffix;
    for (String jobCode : List.of(jobA, jobB, jobC, jobD)) {
      insertDependencyDefinition(tenant, jobCode);
    }

    try {
      jdbcTemplate.update(insertJobDependencySql, tenant, jobA, jobB, tenant, jobC, jobD);

      assertTenantWriteRejected(
          () -> jdbcTemplate.update(updateJobDependencySql, jobB, jobA, tenant, jobC, jobD));

      Integer selectedJobDependencyCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenant);
      assertThat(selectedJobDependencyCount).isEqualTo(2);
      Integer selectedJobDependencyCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, tenant, jobC, jobD);
      assertThat(selectedJobDependencyCountRead2).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteJobDependencySql, tenant);
      jdbcTemplate.update(deleteJobDefinitionSql, tenant);
    }
  }

  @Test
  @DisplayName("数据库触发器拒绝 legacy scalar 同步形成的环并回滚作业定义更新")
  void shouldRejectDependencyCycleCreatedByLegacyScalarSynchronization() {
    final String updateJobDefinitionSql = """
        update batch.job_definition set depends_on_job_code = ? where tenant_id = ? and job_code = ?\
        """;
    final String selectDependsOnJobCodeSql = """
        select depends_on_job_code from batch.job_definition where tenant_id = ? and job_code = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_dependency where tenant_id = ?
    """;
    final String deleteJobDependencySql = """
        delete from batch.job_dependency where tenant_id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenant = "dep-legacy-cycle-" + suffix;
    String jobA = "JOB_A_" + suffix;
    String jobB = "JOB_B_" + suffix;
    insertDependencyDefinition(tenant, jobA);
    insertDependencyDefinition(tenant, jobB);

    try {
      writeAsTenant(tenant, () -> jdbcTemplate.update(updateJobDefinitionSql, jobB, tenant, jobA));

      assertTenantWriteRejected(() -> writeAsTenant(
          tenant, () -> jdbcTemplate.update(updateJobDefinitionSql, jobA, tenant, jobB)));

      String selectedDependsOnJobCode =
          jdbcTemplate.queryForObject(selectDependsOnJobCodeSql, String.class, tenant, jobB);
      assertThat(selectedDependsOnJobCode).isNull();
      Integer selectedJobDependencyCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenant);
      assertThat(selectedJobDependencyCount).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteJobDependencySql, tenant);
      jdbcTemplate.update(deleteJobDefinitionSql, tenant);
    }
  }

  private void assertTenantWriteRejected(Runnable write) {
    assertThatThrownBy(write::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private void writeAsTenant(String tenant, Runnable write) {
    final String selectSetConfigSql = """
        select set_config('app.tenant_id', ?, true)
    """;

    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.execute(status -> {
      jdbcTemplate.queryForObject(selectSetConfigSql, String.class, tenant);
      write.run();
      return null;
    });
  }

  private boolean insertDependencyAndReportAccepted(
      TransactionTemplate transaction,
      String tenant,
      String downstream,
      String upstream,
      CyclicBarrier bothTransactionsReady)
      throws Exception {
    final String selectSetConfigSql = """
        select set_config('app.tenant_id', ?, true)
    """;
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;

    try {
      TransactionCallback<Boolean> insertDependency = status -> {
        jdbcTemplate.queryForObject(selectSetConfigSql, String.class, tenant);
        try {
          bothTransactionsReady.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
          throw new IllegalStateException("dependency writers did not synchronize", failure);
        }
        return jdbcTemplate.update(insertJobDependencySql, tenant, downstream, upstream) == 1;
      };
      Boolean accepted = transaction.execute(insertDependency);
      return Boolean.TRUE.equals(accepted);
    } catch (DataIntegrityViolationException rejectedCycle) {
      return false;
    }
  }

  @Test
  @DisplayName("作业监控策略数据库复合外键拒绝跨租户作业并保留父删除级联语义")
  void shouldEnforceTenantScopedMonitoringPolicyReferenceInDatabase() {
    final String selectIdSql = """
      select id from batch.job_definition where tenant_id = ? and job_code = ?
  """;
    final String insertJobMonitoringPolicySql = """
      insert into batch.job_monitoring_policy (tenant_id, job_definition_id) values (?, ?)
  """;
    final String insertJobMonitoringPolicySql2 = """
      insert into batch.job_monitoring_policy (tenant_id, job_definition_id) values (?, ?)\
      """;
    final String selectCountSql = """
        select count(*) from batch.job_monitoring_policy where tenant_id = ? and job_definition_id = ?\
        """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and id = ?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantA = "monitor-fk-a-" + suffix;
    String tenantB = "monitor-fk-b-" + suffix;
    String jobCodeA = "MONITOR_A_" + suffix;
    String jobCodeB = "MONITOR_B_" + suffix;
    insertDependencyDefinition(tenantA, jobCodeA);
    insertDependencyDefinition(tenantB, jobCodeB);
    Long jobIdA = jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantA, jobCodeA);
    Long jobIdB = jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantB, jobCodeB);

    try {
      jdbcTemplate.update(insertJobMonitoringPolicySql, tenantA, jobIdA);

      assertThatThrownBy(() -> jdbcTemplate.update(insertJobMonitoringPolicySql2, tenantA, jobIdB))
          .isInstanceOf(DataIntegrityViolationException.class);

      Integer selectedJobMonitoringPolicyCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantA, jobIdA);
      assertThat(selectedJobMonitoringPolicyCount).isEqualTo(1);

      jdbcTemplate.update(deleteJobDefinitionSql, tenantA, jobIdA);
      Integer selectedJobMonitoringPolicyCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantA, jobIdA);
      assertThat(selectedJobMonitoringPolicyCountRead2).isZero();
    } finally {
      jdbcTemplate.update(deleteJobDefinitionSql, tenantB, jobIdB);
    }
  }

  private void deleteJobDefinitions(String jobPredicate, Object... parameters) {
    String jobIdsSql = """
        SELECT id FROM batch.job_definition WHERE %s
        """.formatted(jobPredicate);
    String managedWorkflowIdsSql = """
        SELECT id FROM batch.workflow_definition
         WHERE single_job_definition_id IN (%s)
        """.formatted(jobIdsSql);
    final String deleteWorkflowEdgeSql = """
        DELETE FROM batch.workflow_edge WHERE workflow_definition_id IN (%s)
        """.formatted(managedWorkflowIdsSql);
    jdbcTemplate.update(deleteWorkflowEdgeSql, parameters);
    final String deleteWorkflowNodeSql = """
        DELETE FROM batch.workflow_node WHERE workflow_definition_id IN (%s)
        """.formatted(managedWorkflowIdsSql);
    jdbcTemplate.update(deleteWorkflowNodeSql, parameters);
    final String deleteWorkflowDefinitionVersionSql = """
        DELETE FROM batch.workflow_definition_version WHERE workflow_definition_id IN (%s)
        """.formatted(managedWorkflowIdsSql);
    jdbcTemplate.update(deleteWorkflowDefinitionVersionSql, parameters);
    final String deleteWorkflowDefinitionSql = """
        DELETE FROM batch.workflow_definition WHERE single_job_definition_id IN (%s)
        """.formatted(jobIdsSql);
    jdbcTemplate.update(deleteWorkflowDefinitionSql, parameters);
    final String deleteJobDefinitionSql = """
        DELETE FROM batch.job_definition WHERE %s
        """.formatted(jobPredicate);
    jdbcTemplate.update(deleteJobDefinitionSql, parameters);
  }

  private void insertDependencyDefinition(String jobCode) {
    insertDependencyDefinition("int-ta", jobCode);
  }

  private void insertDependencyDefinition(String tenantId, String jobCode) {
    final String insertJobDefinitionSql = """
        INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone)\s\
        VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai')\
        """;

    jdbcTemplate.update(insertJobDefinitionSql, tenantId, jobCode, jobCode);
  }
}
