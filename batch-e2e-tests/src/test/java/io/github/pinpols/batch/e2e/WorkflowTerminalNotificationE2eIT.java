package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "batch.outbox.poll-interval-millis=200",
      "batch.outbox.min-poll-interval-millis=200",
      "batch.webhook.relay.poll-interval-millis=200",
      "batch.sensor.scan-interval=PT0.2S"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@Tag("critical")
@DisplayName("真实 DAG 终态到业务通知:子 Job 成功但根失败,RERUN 独立身份及接收后响应丢失")
class WorkflowTerminalNotificationE2eIT extends AbstractIntegrationTest {
  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);
  private static final Map<String, JsonNode> ACCEPTED = new ConcurrentHashMap<>();
  private static final ConcurrentLinkedQueue<JsonNode> ATTEMPTS = new ConcurrentLinkedQueue<>();
  private static final AtomicBoolean DROP_FIRST_RESPONSE = new AtomicBoolean(true);
  private static final AtomicBoolean SENSOR_READY = new AtomicBoolean(false);
  private static HttpServer endpoint;
  private static String previousAllowPrivate;

  private final JdbcTemplate jdbc;

  private final WorkflowRunLaunchService launch;

  private final WorkflowRunManagementApplicationService workflowRunManagement;

  private final DataSource businessDataSource;

  @Autowired
  WorkflowTerminalNotificationE2eIT(
      JdbcTemplate jdbc,
      WorkflowRunLaunchService launch,
      WorkflowRunManagementApplicationService workflowRunManagement,
      @Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.jdbc = jdbc;
    this.launch = launch;
    this.workflowRunManagement = workflowRunManagement;
    this.businessDataSource = businessDataSource;
  }

  @BeforeAll
  static void startReceiver() throws Exception {
    previousAllowPrivate = System.getProperty("batch.security.ssrf-guard.allow-private");
    System.setProperty("batch.security.ssrf-guard.allow-private", "true");
    endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/terminal", exchange -> {
      JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
      ATTEMPTS.add(body);
      String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      if (EmptyChecks.isBlank(key)) {
        exchange.sendResponseHeaders(400, -1);
        exchange.close();
        return;
      }
      ACCEPTED.putIfAbsent(key, body);
      if (DROP_FIRST_RESPONSE.getAndSet(false)) {
        // 用于通过独立 JVM 执行生产卡滞协调器调度器的入口。
        exchange.close();
        return;
      }
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    endpoint.createContext("/sensor", exchange -> {
      byte[] body = (SENSOR_READY.get() ? "{\"status\":\"READY\"}" : "{\"status\":\"WAIT\"}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    endpoint.start();
  }

  @BeforeEach
  void resetReceiverState() {
    ACCEPTED.clear();
    ATTEMPTS.clear();
    DROP_FIRST_RESPONSE.set(true);
    SENSOR_READY.set(false);
  }

  @AfterAll
  static void stopReceiver() {
    if (EmptyChecks.isNotNull(endpoint)) endpoint.stop(0);
    if (EmptyChecks.isNull(previousAllowPrivate))
      System.clearProperty("batch.security.ssrf-guard.allow-private");
    else System.setProperty("batch.security.ssrf-guard.allow-private", previousAllowPrivate);
  }

  @Test
  @DisplayName("实际业务写入成功后后继解析失败,只通知 Workflow 失败;重跑和旧终态重投保持独立且无重复逻辑动作")
  void shouldNotifyFailedWorkflowAndDistinctRerun_afterSuccessfulJobAndLostReceiverResponse()
      throws Exception {
    final String insertWebhookSubscriptionSql = """
        insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types,enabled)
        values (?, 'r32-dag-terminal', ?, 'WORKFLOW_SUCCESS,WORKFLOW_FAILED,JOB_SUCCESS,JOB_FAILED', true)
        """;
    final String insertNotificationChannelSql = """
        insert into batch.notification_channel\s\
        (tenant_id,channel_code,channel_name,channel_type,config_json,enabled)
        values (?, 'r32-dag-terminal', 'r32 terminal', 'WEBHOOK', ?::jsonb, true)
        """;
    final String insertSubscriptionRuleSql = """
        insert into batch.subscription_rule (tenant_id,rule_name,channel_code,event_types,enabled)
        values (?, 'r32-dag-terminal', 'r32-dag-terminal',\s\
        'WORKFLOW_SUCCESS,WORKFLOW_FAILED,JOB_SUCCESS,JOB_FAILED', true)
        """;
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id=? and event_type='JOB_TERMINAL'
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_terminal_notification_receipt where tenant_id=?
        """;
    final String selectCountSql3 = """
        select count(*) from batch.webhook_delivery_log where tenant_id=? and terminal_event_key is not null
        """;
    final String selectCountSql4 = """
        select count(*) from batch.notification_delivery_log where tenant_id=?\s\
        and terminal_event_key is not null
        """;
    final String selectCountSql5 = """
        select count(*) from batch.webhook_delivery_log
        where tenant_id=? and event_type='WORKFLOW_SUCCESS'
        """;
    final String selectCountSql6 = """
        select count(*) from batch.notification_delivery_log
        where tenant_id=? and event_type='WORKFLOW_SUCCESS'
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request
          (tenant_id,request_id,trigger_type,workflow_definition_id,biz_date,dedup_key,request_status,trace_id,dry_run)
        values (?, ?, 'RERUN', ?, ?, ?, 'ACCEPTED', 'r32-rerun', false)
        """;
    final String selectCountSql7 = """
        select count(*) from biz.customer_account where tenant_id=? and customer_no=?
        """;
    final String selectEventKeySql = """
        select event_key,payload_json::text as payload from batch.outbox_event
        where tenant_id=? and aggregate_type='WORKFLOW_RUN' and aggregate_id=?\s\
        and event_type='WORKFLOW_TERMINAL'
        """;
    final String selectEventKeySql2 = """
          select event_key,payload_json::text as payload from batch.outbox_event
          where tenant_id=? and aggregate_type='JOB_INSTANCE' and event_type='JOB_TERMINAL'
            and aggregate_id in (
              select n.job_instance_id from batch.workflow_node_run n
              where n.workflow_run_id=? and n.job_instance_id is not null)
          order by id limit 1
          """;

    LaunchSeed first = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    LaunchSeed second = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    String customer = "NOTIFY" + Long.toUnsignedString(System.nanoTime());
    configureJob(
        first,
        JsonUtils.toJson(List.of(Map.of(
            "customerNo",
            customer,
            "customerName",
            "Notification proof",
            "customerType",
            "PERSONAL",
            "certificateNo",
            customer,
            "status",
            "ACTIVE"))));
    configureJob(second, "this is not valid JSON");
    configureSequentialDag(first, second);
    String url = "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/terminal";
    jdbc.update(insertWebhookSubscriptionSql, TENANT, url);
    jdbc.update(insertNotificationChannelSql, TENANT, JsonUtils.toJson(Map.of("url", url)));
    jdbc.update(insertSubscriptionRuleSql, TENANT);

    int jobTerminalOutboxBaseline = jdbc.queryForObject(selectCountSql, Integer.class, TENANT);
    int terminalReceiptBaseline = jdbc.queryForObject(selectCountSql2, Integer.class, TENANT);
    int webhookDeliveryBaseline = jdbc.queryForObject(selectCountSql3, Integer.class, TENANT);
    int notificationDeliveryBaseline = jdbc.queryForObject(selectCountSql4, Integer.class, TENANT);
    int workflowSuccessWebhookBaseline =
        jdbc.queryForObject(selectCountSql5, Integer.class, TENANT);
    int workflowSuccessNotificationBaseline =
        jdbc.queryForObject(selectCountSql6, Integer.class, TENANT);

    String originalRun = launch
        .launch(new LaunchRequest(
            TENANT,
            first.workflowDefinitionId(),
            BIZ_DATE,
            TriggerType.API,
            first.requestId(),
            "r32-original",
            Map.of()))
        .instanceNo();
    awaitTerminalAndNotifications(Long.valueOf(originalRun));
    Integer selectedOutboxEventCount = jdbc.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(selectedOutboxEventCount).isEqualTo(jobTerminalOutboxBaseline + 2);
    awaitJobNotifications(Long.valueOf(originalRun));
    assertThat(ACCEPTED).hasSize(3);
    assertThat(DROP_FIRST_RESPONSE.get()).isFalse();
    assertThat(ATTEMPTS.size()).isGreaterThanOrEqualTo(3);
    JsonNode firstNotification = ACCEPTED.values().stream()
        .filter(b -> b.path("eventType").asText().equals("WORKFLOW_FAILED"))
        .findFirst()
        .orElseThrow();
    assertThat(ATTEMPTS.stream()
            .filter(body -> body.path("eventType").asText().equals("WORKFLOW_FAILED"))
            .toList())
        .allSatisfy(body -> assertThat(body).isEqualTo(firstNotification));

    String rerunRequest = "r32-rerun-" + System.nanoTime();
    jdbc.update(
        insertTriggerRequestSql,
        TENANT,
        rerunRequest,
        first.workflowDefinitionId(),
        BIZ_DATE,
        rerunRequest);
    String rerun = launch
        .launch(new LaunchRequest(
            TENANT,
            first.workflowDefinitionId(),
            BIZ_DATE,
            TriggerType.RERUN,
            rerunRequest,
            "r32-rerun",
            Map.of("rerunReason", "R32 independent terminal identity")))
        .instanceNo();
    assertThat(rerun).isNotEqualTo(originalRun);
    awaitTerminalAndNotifications(Long.valueOf(rerun));
    awaitJobNotifications(Long.valueOf(rerun));
    assertThat(ACCEPTED).hasSize(6);
    var workflowNotifications = ACCEPTED.values().stream()
        .filter(b -> b.path("stream").asText().equals("workflow-runs"))
        .toList();
    assertThat(workflowNotifications).hasSize(2).allSatisfy(body -> {
      assertThat(body.path("eventType").asText()).isEqualTo("WORKFLOW_FAILED");
      assertThat(body.path("data").path("runStatus").asText()).isEqualTo("FAILED");
      assertThat(body.path("data").path("bizDate").asText()).isEqualTo(BIZ_DATE.toString());
    });
    assertThat(workflowNotifications.stream()
            .map(b -> b.path("data").path("workflowRunId").asLong())
            .toList())
        .containsExactlyInAnyOrder(Long.valueOf(originalRun), Long.valueOf(rerun));
    Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
        .queryForObject(selectCountSql7, Integer.class, TENANT, customer);
    assertThat(selectedCustomerAccountCount).isEqualTo(1);

    Map<String, Object> original =
        jdbc.queryForMap(selectEventKeySql, TENANT, Long.valueOf(originalRun));
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
        "bootstrap.servers",
        kafkaBootstrapServers(),
        "key.serializer",
        StringSerializer.class,
        "value.serializer",
        StringSerializer.class))) {
      producer
          .send(new ProducerRecord<>(
              BatchTopics.WORKFLOW_TERMINAL_V1,
              original.get("event_key").toString(),
              original.get("payload").toString()))
          .get(10, TimeUnit.SECONDS);
      var replay = producer
          .send(new ProducerRecord<>(
              BatchTopics.WORKFLOW_TERMINAL_V1,
              original.get("event_key").toString(),
              original.get("payload").toString()))
          .get(10, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
          var offset = admin
              .listConsumerGroupOffsets("batch-console-workflow-terminal-notifications")
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(new TopicPartition(replay.topic(), replay.partition()));
          assertThat(offset).isNotNull();
          assertThat(offset.offset()).isGreaterThan(replay.offset());
        }
      });
      Map<String, Object> jobOriginal =
          jdbc.queryForMap(selectEventKeySql2, TENANT, Long.valueOf(originalRun));
      producer
          .send(new ProducerRecord<>(
              BatchTopics.JOB_TERMINAL_V1,
              jobOriginal.get("event_key").toString(),
              jobOriginal.get("payload").toString()))
          .get(10, TimeUnit.SECONDS);
      var jobReplay = producer
          .send(new ProducerRecord<>(
              BatchTopics.JOB_TERMINAL_V1,
              jobOriginal.get("event_key").toString(),
              jobOriginal.get("payload").toString()))
          .get(10, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
          var offset = admin
              .listConsumerGroupOffsets("batch-console-job-terminal-notifications")
              .partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS)
              .get(new TopicPartition(jobReplay.topic(), jobReplay.partition()));
          assertThat(offset).isNotNull();
          assertThat(offset.offset()).isGreaterThan(jobReplay.offset());
        }
      });
    }
    assertThat(ACCEPTED).hasSize(6);
    Integer selectedWorkflowTerminalNotificationReceiptCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, TENANT);
    assertThat(selectedWorkflowTerminalNotificationReceiptCount)
        .isEqualTo(terminalReceiptBaseline + 6);
    for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
      int baseline = table.equals("webhook_delivery_log")
          ? webhookDeliveryBaseline
          : notificationDeliveryBaseline;
      int workflowSuccessBaseline = table.equals("webhook_delivery_log")
          ? workflowSuccessWebhookBaseline
          : workflowSuccessNotificationBaseline;
      final String selectCountSql8 = """
          select count(*) from batch.%s where tenant_id=? and terminal_event_key is not null\
          """.formatted(table);
      Integer selectedCount = jdbc.queryForObject(selectCountSql8, Integer.class, TENANT);
      assertThat(selectedCount).isEqualTo(baseline + 6);
      final String selectWorkflowSuccessCountSql = """
          select count(*) from batch.%s where tenant_id=? and event_type='WORKFLOW_SUCCESS'\
          """.formatted(table);
      Integer selectedWorkflowSuccessCount =
          jdbc.queryForObject(selectWorkflowSuccessCountSql, Integer.class, TENANT);
      assertThat(selectedWorkflowSuccessCount).isEqualTo(workflowSuccessBaseline);
    }
  }

  @Test
  @DisplayName("人工 skip 产生单次 Workflow 终态事件并经 Kafka 消费者成功投递")
  void manualSkip_shouldDeliverTerminalOutboxToKafkaConsumer() throws Exception {
    final String insertWebhookSubscriptionSql = """
        insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types,enabled)
        values (?, ?, ?, 'WORKFLOW_SUCCESS', true)
        """;
    final String insertNotificationChannelSql = """
        insert into batch.notification_channel\s\
        (tenant_id,channel_code,channel_name,channel_type,config_json,enabled)
        values (?, ?, 'r29 manual skip', 'WEBHOOK', ?::jsonb, true)
        """;
    final String insertSubscriptionRuleSql = """
        insert into batch.subscription_rule (tenant_id,rule_name,channel_code,event_types,enabled)
        values (?, ?, ?, 'WORKFLOW_SUCCESS', true)
        """;
    final String selectNodeStatusSql = """
          select node_status from batch.workflow_node_run
          where workflow_run_id=? and node_code='JOB' order by run_seq desc limit 1
          """;
    final String selectNodeStatusSql2 = """
          select node_status from batch.workflow_node_run
          where workflow_run_id=? and node_code='WAIT_RELEASE' order by run_seq desc limit 1
          """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.outbox_event
        where tenant_id=? and aggregate_type='WORKFLOW_RUN' and aggregate_id=?
          and event_type='WORKFLOW_TERMINAL'
        """;
    final String selectCountSql2 = """
          select count(*) from batch.outbox_event
          where tenant_id=? and aggregate_type='WORKFLOW_RUN' and aggregate_id=?
            and event_type='WORKFLOW_TERMINAL' and publish_status='PUBLISHED'
          """;
    final String selectCountSql3 = """
          select count(*) from batch.workflow_terminal_notification_receipt
          where tenant_id=? and event_key=?
          """;
    final String selectDeliveryStatusSql = """
          select delivery_status from batch.webhook_delivery_log
          where tenant_id=? and terminal_event_key=?
          """;
    final String selectDeliveryStatusSql2 = """
          select delivery_status from batch.notification_delivery_log
          where tenant_id=? and terminal_event_key=?
          """;
    final String selectCountSql4 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id=? and node_code='JOB' and node_status='SKIPPED'
          and skip_reason_code='OPERATOR_SKIPPED'
        """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    configureJob(seed, "this is not valid JSON");
    configureManualSkipWaitDag(seed);

    String suffix = Long.toUnsignedString(System.nanoTime());
    String url = "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/terminal";
    jdbc.update(insertWebhookSubscriptionSql, TENANT, "r29-manual-skip-" + suffix, url);
    jdbc.update(
        insertNotificationChannelSql,
        TENANT,
        "r29-manual-skip-" + suffix,
        JsonUtils.toJson(Map.of("url", url)));
    jdbc.update(
        insertSubscriptionRuleSql,
        TENANT,
        "r29-manual-skip-" + suffix,
        "r29-manual-skip-" + suffix);

    long runId = Long.parseLong(launch
        .launch(new LaunchRequest(
            TENANT,
            seed.workflowDefinitionId(),
            BIZ_DATE,
            TriggerType.API,
            seed.requestId(),
            "r29-manual-skip-" + suffix,
            Map.of()))
        .instanceNo());
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedNodeStatus = jdbc.queryForObject(selectNodeStatusSql, String.class, runId);
          assertThat(selectedNodeStatus).isEqualTo("FAILED");
          String selectedNodeStatusRead2 =
              jdbc.queryForObject(selectNodeStatusSql2, String.class, runId);
          assertThat(selectedNodeStatusRead2).isEqualTo("RUNNING");
          String selectedRunStatus =
              jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, runId);
          assertThat(selectedRunStatus).isEqualTo("RUNNING");
        });
    Integer selectedOutboxEventCount =
        jdbc.queryForObject(selectCountSql, Integer.class, TENANT, runId);
    assertThat(selectedOutboxEventCount).isZero();

    workflowRunManagement.skipNode(
        TENANT, runId, "JOB", "operator-r29-e2e", "approved failure bypass");
    String selectedRunStatusRead2 =
        jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, runId);
    assertThat(selectedRunStatusRead2).isEqualTo("RUNNING");
    Integer selectedOutboxEventCountRead2 =
        jdbc.queryForObject(selectCountSql, Integer.class, TENANT, runId);
    assertThat(selectedOutboxEventCountRead2).isZero();

    SENSOR_READY.set(true);
    String eventKey = TENANT + ":workflow:" + runId + ":terminal";
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedRunStatusRead3 =
              jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, runId);
          assertThat(selectedRunStatusRead3).isEqualTo("SUCCESS");
          Integer selectedOutboxEventCountRead3 =
              jdbc.queryForObject(selectCountSql2, Integer.class, TENANT, runId);
          assertThat(selectedOutboxEventCountRead3).isEqualTo(1);
          Integer selectedWorkflowTerminalNotificationReceiptCount =
              jdbc.queryForObject(selectCountSql3, Integer.class, TENANT, eventKey);
          assertThat(selectedWorkflowTerminalNotificationReceiptCount).isEqualTo(1);
          List<String> webhookStatuses =
              jdbc.queryForList(selectDeliveryStatusSql, String.class, TENANT, eventKey);
          assertThat(webhookStatuses)
              .isNotEmpty()
              .allSatisfy(status -> assertThat(status).isEqualTo("SUCCESS"));
          List<String> notificationStatuses =
              jdbc.queryForList(selectDeliveryStatusSql2, String.class, TENANT, eventKey);
          assertThat(notificationStatuses)
              .isNotEmpty()
              .allSatisfy(status -> assertThat(status).isEqualTo("SUCCESS"));
          assertThat(ACCEPTED.values().stream()
                  .filter(body -> body.path("eventType").asText().equals("WORKFLOW_SUCCESS"))
                  .toList())
              .isNotEmpty()
              .allSatisfy(body -> {
                assertThat(body.path("data").path("workflowRunId").asLong()).isEqualTo(runId);
                assertThat(body.path("data").path("runStatus").asText()).isEqualTo("SUCCESS");
                assertThat(body.path("data").path("bizDate").asText())
                    .isEqualTo(BIZ_DATE.toString());
              });
        });
    Integer selectedWorkflowNodeRunCount =
        jdbc.queryForObject(selectCountSql4, Integer.class, runId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
  }

  private void awaitJobNotifications(Long runId) {
    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          var notifications = ACCEPTED.values().stream()
              .filter(body -> body.path("stream").asText().equals("job-instances")
                  && body.path("data").path("workflowRunId").asLong() == runId)
              .toList();
          assertThat(notifications).hasSize(2);
          assertThat(notifications.stream()
                  .map(body -> body.path("eventType").asText())
                  .toList())
              .containsExactlyInAnyOrder("JOB_SUCCESS", "JOB_FAILED");
          for (JsonNode body : notifications) {
            assertThat(body.path("data").path("jobInstanceId").asLong()).isPositive();
            assertThat(body.path("data").path("terminalVersion").asLong()).isPositive();
            assertThat(body.path("data").path("bizDate").asText()).isEqualTo(BIZ_DATE.toString());
            for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
              final String selectDeliveryStatusSql = """
                  select delivery_status from batch.%s where tenant_id=? and terminal_event_key=?\
                  """.formatted(table);
              List<String> selectedDeliveryStatusValues = jdbc.queryForList(
                  selectDeliveryStatusSql,
                  String.class,
                  TENANT,
                  body.path("cursor").asText());
              assertThat(selectedDeliveryStatusValues).containsExactly("SUCCESS");
            }
          }
        });
  }

  private void awaitTerminalAndNotifications(Long runId) {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectInstanceStatusSql = """
          select ji.instance_status from batch.workflow_node_run n join batch.job_instance ji
          on ji.id=n.job_instance_id where n.workflow_run_id=? and n.node_code='JOB' and ji.tenant_id=?
          """;
    final String selectInstanceStatusSql2 = """
          select ji.instance_status from batch.workflow_node_run n join batch.job_instance ji
          on ji.id=n.job_instance_id where n.workflow_run_id=? and n.node_code='JOB_FAIL' and ji.tenant_id=?
          """;
    final String selectCountSql = """
          select count(*) from batch.outbox_event where tenant_id=? and aggregate_type='WORKFLOW_RUN'
          and aggregate_id=? and event_type='WORKFLOW_TERMINAL' and publish_status='PUBLISHED'
          """;
    final String selectCountSql2 = """
          select count(*) from batch.workflow_node_run where workflow_run_id=? and node_code='END'\s\
          and node_status='SUCCESS'
          """;

    String key = TENANT + ":workflow:" + runId + ":terminal";
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedRunStatus =
              jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, runId);
          assertThat(selectedRunStatus).isEqualTo("FAILED");
          String selectedInstanceStatus =
              jdbc.queryForObject(selectInstanceStatusSql, String.class, runId, TENANT);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          String selectedInstanceStatusRead2 =
              jdbc.queryForObject(selectInstanceStatusSql2, String.class, runId, TENANT);
          assertThat(selectedInstanceStatusRead2).isEqualTo("FAILED");
          for (String table : List.of("webhook_delivery_log", "notification_delivery_log")) {
            final String selectDeliveryStatusSql = """
                select delivery_status from batch.%s where tenant_id=? and terminal_event_key=?\
                """.formatted(table);
            List<String> selectedDeliveryStatusValues =
                jdbc.queryForList(selectDeliveryStatusSql, String.class, TENANT, key);
            assertThat(selectedDeliveryStatusValues).containsExactly("SUCCESS");
          }
          Integer selectedOutboxEventCount =
              jdbc.queryForObject(selectCountSql, Integer.class, TENANT, runId);
          assertThat(selectedOutboxEventCount).isEqualTo(1);
          Integer selectedWorkflowNodeRunCount =
              jdbc.queryForObject(selectCountSql2, Integer.class, runId);
          assertThat(selectedWorkflowNodeRunCount).isZero();
        });
  }

  private void configureJob(LaunchSeed seed, String content) {
    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params=?::jsonb where tenant_id=? and job_code=?
    """;

    jdbc.update(
        updateJobDefinitionSql,
        JsonUtils.toJson(Map.of(
            "fileFormatType",
            "JSON",
            "templateCode",
            "IMP-CUSTOMER-JSON-ARRAY",
            "bizType",
            "CUSTOMER",
            "content",
            content)),
        TENANT,
        seed.jobCode());
  }

  private void configureSequentialDag(LaunchSeed first, LaunchSeed second) {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set single_job_definition_id=null where tenant_id=? and id=?
    """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order=3 where tenant_id=? and workflow_definition_id=?\s\
        and node_code='END'
    """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (tenant_id,workflow_definition_id,node_code,node_name,node_type,
          related_job_code,node_order,retry_policy,retry_max_count,timeout_seconds,enabled)
        values (?, ?, 'JOB_FAIL', 'Expected parse failure', 'JOB', ?, 2, 'NONE', 0, 0, true)
        """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id=? and workflow_definition_id=? and from_node_code='JOB'
    """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge\s\
        (tenant_id,workflow_definition_id,from_node_code,to_node_code,edge_type,enabled)
        values (?, ?, 'JOB', 'JOB_FAIL', 'SUCCESS', true), (?, ?, 'JOB_FAIL', 'END', 'SUCCESS', true)
        """;

    jdbc.update(updateWorkflowDefinitionSql, TENANT, first.workflowDefinitionId());
    jdbc.update(updateWorkflowNodeSql, TENANT, first.workflowDefinitionId());
    jdbc.update(insertWorkflowNodeSql, TENANT, first.workflowDefinitionId(), second.jobCode());
    jdbc.update(deleteWorkflowEdgeSql, TENANT, first.workflowDefinitionId());
    jdbc.update(
        insertWorkflowEdgeSql,
        TENANT,
        first.workflowDefinitionId(),
        TENANT,
        first.workflowDefinitionId());
  }

  private void configureManualSkipWaitDag(LaunchSeed seed) {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition
        set single_job_definition_id=null, version=version+1
        where tenant_id=? and id=?
        """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order=3
        where tenant_id=? and workflow_definition_id=? and node_code='END'
        """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node
          (tenant_id,workflow_definition_id,node_code,node_name,node_type,node_params,
           node_order,retry_policy,retry_max_count,timeout_seconds,enabled)
        values (?, ?, 'WAIT_RELEASE', 'Controlled release', 'WAIT', ?::jsonb,
                2, 'NONE', 0, 300, true)
        """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge
        where tenant_id=? and workflow_definition_id=?
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge
          (tenant_id,workflow_definition_id,from_node_code,to_node_code,edge_type,enabled)
        values (?, ?, 'START', 'JOB', 'ALWAYS', true),
               (?, ?, 'START', 'WAIT_RELEASE', 'ALWAYS', true),
               (?, ?, 'JOB', 'END', 'SUCCESS', true),
               (?, ?, 'WAIT_RELEASE', 'END', 'SUCCESS', true)
        """;

    String sensorUrl = "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/sensor";
    String waitParams = JsonUtils.toJson(Map.of(
        "sensor_type",
        "HTTP_POLL",
        "sensor_spec",
        Map.of("url", sensorUrl, "matchExpr", "$.status==READY"),
        "timeout_seconds",
        300,
        "poll_interval_seconds",
        1,
        "on_timeout",
        "FAIL"));
    jdbc.update(updateWorkflowDefinitionSql, TENANT, seed.workflowDefinitionId());
    jdbc.update(updateWorkflowNodeSql, TENANT, seed.workflowDefinitionId());
    jdbc.update(insertWorkflowNodeSql, TENANT, seed.workflowDefinitionId(), waitParams);
    jdbc.update(deleteWorkflowEdgeSql, TENANT, seed.workflowDefinitionId());
    jdbc.update(
        insertWorkflowEdgeSql,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        seed.workflowDefinitionId());
  }
}
