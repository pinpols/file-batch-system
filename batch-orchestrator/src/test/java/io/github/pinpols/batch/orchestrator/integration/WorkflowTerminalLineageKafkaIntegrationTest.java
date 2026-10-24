package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.openlineage.enabled=true", "batch.openlineage.retry-backoff-ms=200"})
@DisplayName("Workflow 终态真实 Outbox/Kafka/血缘 HTTP:故障重投及稳定运行身份")
class WorkflowTerminalLineageKafkaIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicBoolean AVAILABLE = new AtomicBoolean();
  private static final ConcurrentLinkedQueue<JsonNode> ATTEMPTS = new ConcurrentLinkedQueue<>();
  private static final Map<String, JsonNode> ACCEPTED = new ConcurrentHashMap<>();
  private static HttpServer endpoint;
  private static final String GROUP = "r32-lineage-integration";

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkflowTerminalOutboxService terminals;

  @Autowired
  @Qualifier("transactionManager")
  private PlatformTransactionManager transactionManager;

  @Autowired
  private DefaultScheduleForwarder forwarder;

  @Autowired
  private KafkaTemplate<String, String> kafka;

  @DynamicPropertySource
  static void lineageEndpoint(DynamicPropertyRegistry properties) throws Exception {
    endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/lineage", exchange -> {
      JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
      ATTEMPTS.add(body);
      boolean available = AVAILABLE.get();
      if (available) {
        // 仅供低基数运维指标使用的跨租户汇总。
        ACCEPTED.putIfAbsent(body.path("run").path("runId").asText(), body);
      }
      exchange.sendResponseHeaders(available ? 202 : 503, -1);
      exchange.close();
    });
    endpoint.start();
    properties.add(
        "batch.openlineage.endpoint",
        () -> "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/lineage");
    properties.add("batch.openlineage.consumer-group-id", () -> GROUP);
  }

  @AfterAll
  static void stopEndpoint() {
    endpoint.stop(0);
  }

  @Test
  @DisplayName("端点失败不确认 Kafka 位点,恢复和重复消息保留原业务日,终态及唯一外部运行身份")
  void shouldRetryTerminalDeliveryWithStableIdentity_afterEndpointFailureAndKafkaRedelivery()
      throws Exception {
        final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition
          (tenant_id,workflow_code,workflow_name,workflow_type,version,enabled)
        values (?, 'r32-workflow', 'r32 workflow', 'DAG', 1, true) returning id
        """;
        final String insertWorkflowRunSql = """
        insert into batch.workflow_run
          (tenant_id,workflow_definition_id,biz_date,run_status,workflow_version_snapshot,
           execution_snapshot,started_at,finished_at,trace_id)
        values (?, ?, '2026-10-19', 'FAILED', 1, '{}'::jsonb, ?, ?, 'r32-trace') returning id
        """;
        final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id=? and event_key=?
        """;
    final String selectPayloadJsonSql = """
        select payload_json::text from batch.outbox_event where tenant_id=? and event_key=?
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
        """;
    final String selectPublishStatusSql = """
        select publish_status from batch.outbox_event where tenant_id=? and event_key=?
        """;

    String tenant = "r32-lineage";
    Long definition = jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenant);
    Instant finished = Instant.parse("2026-10-20T01:00:00Z");
    Long runId = jdbc.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        tenant,
        definition,
        Timestamp.from(finished.minusSeconds(60)),
        Timestamp.from(finished));
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(runId);
    run.setTenantId(tenant);
    run.setWorkflowDefinitionId(definition);
    run.setBizDate(LocalDate.of(2026, 10, 19));
    run.setRunStatus("FAILED");
    run.setStartedAt(finished.minusSeconds(60));
    run.setTraceId("r32-trace");
    new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
      terminals.writeTerminalEvent(run, "FAILED", finished);
      terminals.writeTerminalEvent(run, "FAILED", finished);
    });
    String eventKey = tenant + ":workflow:" + runId + ":terminal";
    Integer selectedOutboxEventCount =
        jdbc.queryForObject(selectCountSql, Integer.class, tenant, eventKey);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId(tenant);
    plan.setShardTotal(1);
    plan.setShardIndex(0);
    forwarder.advance(plan);
    await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
      assertThat(ATTEMPTS).isNotEmpty();
      assertThat(ACCEPTED).isEmpty();
      assertThat(committedOffset()).isLessThanOrEqualTo(0);
    });
    AVAILABLE.set(true);
    await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
      assertThat(ACCEPTED).hasSize(1);
      assertThat(committedOffset()).isGreaterThan(0);
    });
    JsonNode accepted = ACCEPTED.values().iterator().next();
    assertThat(accepted.path("eventType").asText()).isEqualTo("FAIL");
    assertThat(accepted.path("eventTime").asText()).isEqualTo(finished.toString());
    String payload = jdbc.queryForObject(selectPayloadJsonSql, String.class, tenant, eventKey);
    int before = ATTEMPTS.size();
    var firstReplay = kafka
        .send(BatchTopics.WORKFLOW_TERMINAL_V1, eventKey, payload)
        .get(10, TimeUnit.SECONDS)
        .getRecordMetadata();
    var secondReplay = kafka
        .send(BatchTopics.WORKFLOW_TERMINAL_V1, eventKey, payload)
        .get(10, TimeUnit.SECONDS)
        .getRecordMetadata();
    assertThat(firstReplay.partition()).isEqualTo(secondReplay.partition());
    await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
      assertThat(ATTEMPTS.size()).isGreaterThanOrEqualTo(before + 2);
      assertThat(committedOffset()).isGreaterThan(secondReplay.offset());
    });
    assertThat(ACCEPTED).hasSize(1);
    assertThat(ATTEMPTS).allSatisfy(body -> assertThat(body).isEqualTo(accepted));
    String selectedRunStatus = jdbc.queryForObject(selectRunStatusSql, String.class, tenant, runId);
    assertThat(selectedRunStatus).isEqualTo("FAILED");
    String selectedPublishStatus =
        jdbc.queryForObject(selectPublishStatusSql, String.class, tenant, eventKey);
    assertThat(selectedPublishStatus).isEqualTo("PUBLISHED");
  }

  private long committedOffset() throws Exception {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
      var offsets = admin
          .listConsumerGroupOffsets(GROUP)
          .partitionsToOffsetAndMetadata()
          .get(10, TimeUnit.SECONDS);
      var offset = offsets.get(new TopicPartition(BatchTopics.WORKFLOW_TERMINAL_V1, 0));
      return EmptyChecks.isNull(offset) ? -1 : offset.offset();
    }
  }
}
