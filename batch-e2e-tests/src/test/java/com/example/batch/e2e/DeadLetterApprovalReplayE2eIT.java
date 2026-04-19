package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.e2e.apps.E2eConsoleImportApplication;
import com.example.batch.e2e.support.E2eOutboxPublishSupport;
import com.example.batch.e2e.support.E2eScenarioFixture;
import com.example.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import com.example.batch.e2e.support.E2eTestSql;
import com.example.batch.testing.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * E2E: 任务进入死信后，通过 console 审批 + replay 完成人工闭环。
 *
 * <p>链路：
 *
 * <pre>
 * API launch → orchestrator 创建 job/task/partition → 手工 seed dead_letter_task
 *   → console 查询死信 → console 发起 replay 审批 → console 审批通过
 *   → replay 重新入队 → dead letter 状态更新为 SUCCESS
 * </pre>
 */
@SpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.security.bypass-mode=true",
      "batch.console.security.default-tenant-id=t1",
      "batch.console.security.allowed-tenants=t1",
      "batch.console.orchestrator.base-url=http://127.0.0.1:${local.server.port}",
      "batch.console.trigger.base-url=http://127.0.0.1:${local.server.port}"
    })
@ActiveProfiles({"test", "e2e"})
@Sql(
    scripts = {
      E2eTestSql.BIZ_SCHEMA,
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("e2e")
class DeadLetterApprovalReplayE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String BASE_TEMPLATE_CODE = "IMP-CUSTOMER-JSON-ARRAY";
  private static final String REPLAY_TEMPLATE_CODE = "IMP-CUSTOMER-JSON-ARRAY-DLQ-E2E";
  private static final String APPROVAL_OPERATOR = "e2e-ops";

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Autowired private ObjectMapper objectMapper;

  @Value("${local.server.port}")
  private int localServerPort;

  @Test
  void deadLetterCanBeApprovedAndReplayedThroughConsoleHttp() throws Exception {
    String initialTraceId = traceId("dlq");
    LaunchSeed seed =
        E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
            jdbcTemplate,
            TENANT,
            "IMPORT",
            "import",
            com.example.batch.common.enums.TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", REPLAY_TEMPLATE_CODE);
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        "[{\"customerNo\":\"DLQ-E2E-001\",\"customerName\":\"Dead Letter User\","
            + "\"customerType\":\"PERSONAL\",\"certificateNo\":\"ID-20260402-0001\","
            + "\"mobileNo\":\"13800000001\",\"email\":\"dlq@example.com\",\"status\":\"ACTIVE\"}]");

    long deadLetterId = seedReplayGraph(seed, initialTraceId, params);

    JsonNode deadLetters =
        getConsoleJson(
            "/api/console/queries/dead-letters?tenantId=t1&traceId="
                + encode(initialTraceId)
                + "&pageNo=1&pageSize=10");
    JsonNode deadLetter = firstItem(deadLetters);
    String deadLetterTraceId = deadLetter.path("traceId").asText();
    assertThat(deadLetterId).isPositive();
    assertThat(deadLetterTraceId).isEqualTo(initialTraceId);
    assertThat(deadLetter.path("sourceType").asText()).isEqualTo("JOB_PARTITION");
    assertThat(deadLetter.path("replayStatus").asText()).isEqualTo("NEW");

    insertReplayTemplate();

    String replayApprovalNo =
        extractDataText(
            postConsoleJson(
                "/api/console/jobs/dead-letters/replay",
                """
                {"tenantId":"t1","deadLetterId":%d,"reason":"fix template and replay","operatorId":"%s"}
                """
                    .formatted(deadLetterId, APPROVAL_OPERATOR),
                idempotencyKey("dead-letter-replay")));

    JsonNode pendingApprovals =
        getConsoleJson(
            "/api/console/queries/approvals?tenantId=t1&approvalNo="
                + encode(replayApprovalNo)
                + "&approvalStatus=PENDING&pageNo=1&pageSize=10");
    JsonNode pendingApproval = firstItem(pendingApprovals);
    assertThat(pendingApproval.path("approvalNo").asText()).isEqualTo(replayApprovalNo);
    assertThat(pendingApproval.path("approvalStatus").asText()).isEqualTo("PENDING");
    assertThat(pendingApproval.path("actionType").asText()).isEqualTo("DLQ_REPLAY");

    JsonNode approveResponse =
        postConsoleJson(
            "/api/console/approvals/" + encode(replayApprovalNo) + "/approve",
            """
            {"tenantId":"t1","operatorId":"%s","reason":"approved for replay"}
            """
                .formatted(APPROVAL_OPERATOR),
            idempotencyKey("dead-letter-approve"));
    String replayCommandNo = extractDataText(approveResponse);
    assertThat(replayCommandNo).isNotBlank();

    // approve() 已同步触发补偿并将 dead letter 置为 SUCCESS；
    // publishAllPending 驱动 outbox 投递，findItemById 精确定位原始记录，
    // 避免 import worker 失败后新生成的同 traceId dead letter 干扰断言。
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              e2eOutboxPublishSupport.publishAllPending(TENANT);
              JsonNode completedDeadLetter =
                  findItemById(
                      getConsoleJson(
                          "/api/console/queries/dead-letters?tenantId=t1&traceId="
                              + encode(deadLetterTraceId)
                              + "&pageNo=1&pageSize=10"),
                      deadLetterId);
              assertThat(completedDeadLetter.path("replayStatus").asText()).isEqualTo("SUCCESS");
              assertThat(completedDeadLetter.path("replayCount").asInt()).isEqualTo(1);
            });

    JsonNode executedApproval =
        firstItem(
            getConsoleJson(
                "/api/console/queries/approvals?tenantId=t1&approvalNo="
                    + encode(replayApprovalNo)
                    + "&pageNo=1&pageSize=10"));
    assertThat(executedApproval.path("approvalStatus").asText()).isEqualTo("EXECUTED");
  }

  private void insertReplayTemplate() {
    jdbcTemplate.update(
        """
        insert into batch.file_template_config
          (tenant_id, template_code, template_name, template_type, biz_type,
           file_format_type, charset, target_charset, with_bom,
           delimiter, quote_char, escape_char,
           record_length, header_rows, footer_rows,
           checksum_type, compress_type, encrypt_type,
           field_mappings, validation_rule_set, query_param_schema,
           streaming_enabled, page_size, fetch_size, chunk_size,
           content_encryption_enabled, encryption_key_ref,
           preview_masking_enabled, download_requires_approval,
           enabled, version, created_by, load_target_ref)
        select tenant_id,
               ?,
               ?,
               template_type,
               biz_type,
               file_format_type,
               charset,
               target_charset,
               with_bom,
               delimiter,
               quote_char,
               escape_char,
               record_length,
               header_rows,
               footer_rows,
               checksum_type,
               compress_type,
               encrypt_type,
               field_mappings,
               validation_rule_set,
               query_param_schema,
               streaming_enabled,
               page_size,
               fetch_size,
               chunk_size,
               content_encryption_enabled,
               encryption_key_ref,
               preview_masking_enabled,
               download_requires_approval,
               enabled,
               version,
               created_by,
               load_target_ref
        from batch.file_template_config
        where tenant_id = ?
          and template_code = ?
        """,
        REPLAY_TEMPLATE_CODE,
        "Customer Import JSON Array DLQ E2E",
        TENANT,
        BASE_TEMPLATE_CODE);
  }

  private JsonNode getConsoleJson(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + localServerPort + path))
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId("get"))
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId("get"))
            .GET()
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private JsonNode postConsoleJson(String path, String body, String idempotencyKey)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + localServerPort + path))
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
            .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, APPROVAL_OPERATOR)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId("post"))
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId("post"))
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private JsonNode firstItem(JsonNode response) {
    JsonNode items = response.path("data").path("items");
    assertThat(items.isArray()).isTrue();
    assertThat(items.size()).isGreaterThan(0);
    return items.get(0);
  }

  private JsonNode findItemById(JsonNode response, long id) {
    JsonNode items = response.path("data").path("items");
    assertThat(items.isArray()).isTrue();
    for (JsonNode item : items) {
      if (item.path("id").asLong() == id) {
        return item;
      }
    }
    throw new AssertionError("dead letter id=" + id + " not found in response");
  }

  private String extractDataText(JsonNode response) {
    JsonNode data = response.path("data");
    assertThat(data.isMissingNode()).isFalse();
    return data.asText();
  }

  private String idempotencyKey(String suffix) {
    return "e2e-dlq-" + suffix + "-" + UUID.randomUUID();
  }

  private String requestId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private String traceId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private long seedReplayGraph(LaunchSeed seed, String traceId, Map<String, Object> params)
      throws Exception {
    Long jobDefinitionId =
        jdbcTemplate.queryForObject(
            """
            select id
            from batch.job_definition
            where tenant_id = ?
              and job_code = ?
            """,
            Long.class,
            TENANT,
            seed.jobCode());
    assertThat(jobDefinitionId).isNotNull();

    Long triggerRequestId =
        jdbcTemplate.queryForObject(
            """
            select id
            from batch.trigger_request
            where tenant_id = ?
              and request_id = ?
            """,
            Long.class,
            TENANT,
            seed.requestId());
    assertThat(triggerRequestId).isNotNull();

    String instanceNo = "inst-dlq-e2e-" + UUID.randomUUID();
    String paramsSnapshot = objectMapper.writeValueAsString(params);
    String resultSummary = "{}";
    Long jobInstanceId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.job_instance (
                tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date,
                trigger_type, instance_status, batch_no, operator_id, rerun_flag, retry_flag,
                rerun_reason, related_file_id, parent_instance_id, queue_code, worker_group, priority,
                dedup_key, version, expected_partition_count, success_partition_count, failed_partition_count,
                trace_id, params_snapshot, result_summary, deadline_at, expected_duration_seconds, sla_alerted_at
            ) values (
                ?, ?, ?, ?, ?, date '2026-01-15',
                'API', 'READY', null, 'e2e-ops', false, false,
                null, null, null, 'e2e-q', 'import', 5,
                ?, 0, 1, 0, 0,
                ?, ?::jsonb, ?::jsonb, null, 300, null
            )
            returning id
            """,
            Long.class,
            TENANT,
            jobDefinitionId,
            triggerRequestId,
            seed.jobCode(),
            instanceNo,
            seed.dedupKey(),
            traceId,
            paramsSnapshot,
            resultSummary);
    assertThat(jobInstanceId).isNotNull();

    Long partitionId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.job_partition (
                tenant_id, job_instance_id, partition_no, partition_key, partition_status,
                worker_group, worker_code, lease_expire_at, retry_count, business_key,
                idempotency_key, input_snapshot, output_summary
            ) values (
                ?, ?, 1, 'e2e-partition-1', 'READY',
                'import', null, null, 0, 'e2e-business-key',
                ?, ?::jsonb, null
            )
            returning id
            """,
            Long.class,
            TENANT,
            jobInstanceId,
            seed.dedupKey() + ":partition",
            paramsSnapshot);
    assertThat(partitionId).isNotNull();

    Long taskId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.job_task (
                tenant_id, job_instance_id, job_partition_id, task_type, task_seq, task_status,
                assigned_worker_code, task_payload, result_summary, error_code, error_message,
                started_at, finished_at
            ) values (
                ?, ?, ?, 'EXECUTION', 1, 'READY',
                null, ?::jsonb, null, null, null,
                null, null
            )
            returning id
            """,
            Long.class,
            TENANT,
            jobInstanceId,
            partitionId,
            paramsSnapshot);
    assertThat(taskId).isNotNull();

    jdbcTemplate.update(
        """
        insert into batch.dead_letter_task
          (tenant_id, source_type, source_id, dead_letter_reason, payload_ref,
           replay_status, replay_count, last_replay_at, last_replay_result, trace_id)
        values (?, 'JOB_PARTITION', ?, ?, ?, 'NEW', 0, null, null, ?)
        """,
        TENANT,
        partitionId,
        "manual dead letter for e2e",
        instanceNo + ":" + taskId,
        traceId);

    return jdbcTemplate.queryForObject(
        """
        select id
        from batch.dead_letter_task
        where tenant_id = ?
          and source_type = 'JOB_PARTITION'
          and source_id = ?
          and trace_id = ?
        order by id desc
        limit 1
        """,
        Long.class,
        TENANT,
        partitionId,
        traceId);
  }
}
