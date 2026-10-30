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
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.outbox.poll-interval-millis=200",
      "batch.outbox.min-poll-interval-millis=200"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@Tag("critical")
@DisplayName("独立 Console JVM 在通知接收提交后强杀,持久认领自然到期恢复")
class WorkflowTerminalNotificationProcessRecoveryE2eIT extends AbstractIntegrationTest {
  private static final String TENANT = "t1";
  private static final String GROUP = "batch-console-workflow-terminal-notifications";
  private static final Map<String, JsonNode> ACCEPTED = new ConcurrentHashMap<>();
  private static final ConcurrentLinkedQueue<Attempt> ATTEMPTS = new ConcurrentLinkedQueue<>();
  private static final CountDownLatch FIRST_ACCEPTED = new CountDownLatch(1);
  private static final CountDownLatch RELEASE_RESPONSE = new CountDownLatch(1);
  private static HttpServer endpoint;

  private final JdbcTemplate jdbc;

  private final WorkflowRunLaunchService launch;

  private final DataSource businessDataSource;

  @LocalServerPort
  private int port;

  @Autowired
  WorkflowTerminalNotificationProcessRecoveryE2eIT(
      JdbcTemplate jdbc,
      WorkflowRunLaunchService launch,
      @Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.jdbc = jdbc;
    this.launch = launch;
    this.businessDataSource = businessDataSource;
  }

  @BeforeAll
  static void startReceiver() throws Exception {
    endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/terminal", exchange -> {
      try {
        JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (EmptyChecks.isNull(key) || key.isBlank()) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        ATTEMPTS.add(new Attempt(key, body, Instant.now()));
        ACCEPTED.putIfAbsent(key, body);
        if (FIRST_ACCEPTED.getCount() > 0) {
          FIRST_ACCEPTED.countDown();
          // 接收端已提交逻辑操作；发送方不会收到 HTTP ACK。
          if (!RELEASE_RESPONSE.await(90, TimeUnit.SECONDS)) {
            throw new IOException("test did not release the response gate");
          }
        }
        exchange.sendResponseHeaders(200, -1);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (IOException closedSender) {
        // 接收端已提交逻辑操作，但发送方没有可持久化的响应。
        if (RELEASE_RESPONSE.getCount() > 0) throw closedSender;
      } finally {
        exchange.close();
      }
    });
    endpoint.start();
  }

  @AfterAll
  static void stopReceiver() {
    RELEASE_RESPONSE.countDown();
    if (EmptyChecks.isNotNull(endpoint)) endpoint.stop(0);
  }

  @DisplayName("Console 进程终止后恢复已提交意图且不改变领取和锁语义")
  @Test
  void shouldRecoverCommittedIntentAfterConsoleKill_withoutChangingClaimsOrLocks()
      throws Exception {
    final String updateJobDefinitionSql = """
            update batch.job_definition set default_params=?::jsonb where tenant_id=? and job_code=?
        """;
    final String insertWebhookSubscriptionSql = """
        insert into batch.webhook_subscription (tenant_id,name,callback_url,event_types,enabled)
        values (?, 'r32-console-crash', ?, 'WORKFLOW_SUCCESS,WORKFLOW_FAILED', true)
        """;
    final String selectDeliveryStatusSql = """
          select delivery_status,attempt,delivery_claim_token,delivery_claim_until
          from batch.webhook_delivery_log where tenant_id=? and terminal_event_key=?
          """;
    final String selectDeliveryStatusSql2 = """
            select delivery_status from batch.webhook_delivery_log where tenant_id=? and terminal_event_key=?
            """;
    final String selectAttemptSql = """
          select attempt,delivery_claim_token,delivery_claim_until from batch.webhook_delivery_log
          where tenant_id=? and terminal_event_key=?
          """;

    var seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API);
    String customer = "CRASH" + Long.toUnsignedString(System.nanoTime());
    String content = JsonUtils.toJson(List.of(Map.of(
        "customerNo",
        customer,
        "customerName",
        "Console recovery",
        "customerType",
        "PERSONAL",
        "certificateNo",
        customer,
        "status",
        "ACTIVE")));
    String parameters = JsonUtils.toJson(Map.of(
        "fileFormatType",
        "JSON",
        "templateCode",
        "IMP-CUSTOMER-JSON-ARRAY",
        "bizType",
        "CUSTOMER",
        "content",
        content));
    jdbc.update(updateJobDefinitionSql, parameters, TENANT, seed.jobCode());
    jdbc.update(
        insertWebhookSubscriptionSql,
        TENANT,
        "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/terminal");
    List<Child> children = new ArrayList<>();
    try {
      Child first = startConsole(children);
      Long runId = Long.valueOf(launch
          .launch(new LaunchRequest(
              TENANT,
              seed.workflowDefinitionId(),
              LocalDate.of(2026, 1, 15),
              TriggerType.API,
              seed.requestId(),
              "r32-console-crash",
              Map.of()))
          .instanceNo());
      String eventKey = TENANT + ":workflow:" + runId + ":terminal";
      assertThat(FIRST_ACCEPTED.await(90, TimeUnit.SECONDS))
          .as("receiver accepted the actual Workflow notification")
          .isTrue();
      first.kill();
      RELEASE_RESPONSE.countDown();
      Map<String, Object> interrupted = jdbc.queryForMap(selectDeliveryStatusSql, TENANT, eventKey);
      assertThat(interrupted.get("delivery_status")).isEqualTo("PENDING");
      assertThat(((Number) interrupted.get("attempt")).intValue()).isEqualTo(1);
      assertThat(interrupted.get("delivery_claim_token")).isNotNull();
      Instant claimUntil = ((Timestamp) interrupted.get("delivery_claim_until")).toInstant();
      assertThat(claimUntil).isAfter(Instant.now());
      assertBusinessAndReceipt(runId, customer);
      // 被终止的 Console 持有已关闭的套接字；下一次请求必须独立恢复。
      // 被终止的消费者此前已持久化提交原始 Kafka 投递。
      try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
        var offsets = admin
            .listConsumerGroupOffsets(GROUP)
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS);
        assertThat(offsets.entrySet().stream()
                .filter(e -> e.getKey().topic().equals(BatchTopics.WORKFLOW_TERMINAL_V1))
                .mapToLong(e -> e.getValue().offset())
                .sum())
            .isEqualTo(1);
      }
      Child replacement = startConsole(children);
      assertThat(replacement.process().pid()).isNotEqualTo(first.process().pid());
      // 因此恢复不能依赖重新生成或重放终态事件。
      // 生产投递租约和 Redis 中继锁都会自然过期（两分钟）。
      await()
          .atMost(Duration.ofSeconds(180))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> {
            assertThat(replacement.process().isAlive())
                .as("replacement Console: %s", replacement.log())
                .isTrue();
            String selectedDeliveryStatus =
                jdbc.queryForObject(selectDeliveryStatusSql2, String.class, TENANT, eventKey);
            assertThat(selectedDeliveryStatus).isEqualTo("SUCCESS");
          });
      assertThat(ATTEMPTS).hasSize(2);
      List<Attempt> attempts = List.copyOf(ATTEMPTS);
      assertThat(attempts.get(1).at()).isAfterOrEqualTo(claimUntil);
      assertThat(attempts.get(1).key()).isEqualTo(attempts.getFirst().key());
      assertThat(attempts.get(1).body()).isEqualTo(attempts.getFirst().body());
      assertThat(ACCEPTED).hasSize(1);
      Map<String, Object> completed = jdbc.queryForMap(selectAttemptSql, TENANT, eventKey);
      assertThat(((Number) completed.get("attempt")).intValue()).isEqualTo(2);
      assertThat(completed.get("delivery_claim_token")).isNull();
      assertThat(completed.get("delivery_claim_until")).isNull();
      assertBusinessAndReceipt(runId, customer);
    } finally {
      RELEASE_RESPONSE.countDown();
      for (Child child : children) child.close();
    }
  }

  private void assertBusinessAndReceipt(Long runId, String customer) {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_terminal_notification_receipt where tenant_id=?\s\
        and event_key like '%:workflow:%'
    """;
    final String selectCountSql2 = """
        select count(*) from batch.outbox_event where tenant_id=? and aggregate_type='WORKFLOW_RUN'
        and aggregate_id=? and event_type='WORKFLOW_TERMINAL' and publish_status='PUBLISHED'
        """;
    final String selectCountSql3 = """
        select count(*) from biz.customer_account where tenant_id=? and customer_no=?
        """;

    String selectedRunStatus = jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, runId);
    assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    Integer selectedWorkflowTerminalNotificationReceiptCount =
        jdbc.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(selectedWorkflowTerminalNotificationReceiptCount).isEqualTo(1);
    Integer selectedOutboxEventCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, TENANT, runId);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
    Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
        .queryForObject(selectCountSql3, Integer.class, TENANT, customer);
    assertThat(selectedCustomerAccountCount).isEqualTo(1);
  }

  private Child startConsole(List<Child> children) throws Exception {
    Map<String, Object> properties = new LinkedHashMap<>();
    AbstractIntegrationTest.registerDynamicProperties(
        (name, supplier) -> properties.put(name, supplier.get()));
    properties.put("spring.profiles.active", "test,e2e");
    properties.put("spring.flyway.enabled", false);
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("server.port", 0);
    properties.put(
        "spring.autoconfigure.exclude",
        String.join(
            ",",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration"));
    properties.put("batch.startup-self-check.enabled", false);
    properties.put("spring.kafka.listener.auto-startup", true);
    properties.put("batch.webhook.relay.poll-interval-millis", 200);
    properties.put("batch.orchestrator.base-url", "http://127.0.0.1:" + port);
    properties.put("batch.console.orchestrator.base-url", "http://127.0.0.1:" + port);
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dbatch.security.ssrf-guard.allow-private=true",
        "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        BatchConsoleApiApplication.class.getName()));
    properties.forEach((name, value) -> command.add("--" + name + "=" + value));
    Path directory = Path.of("../logs/verification/workflow").toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path log = Files.createTempFile(directory, "r32-console-process-", ".log");
    Child child = new Child(
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start(),
        log);
    children.add(child);
    await()
        .atMost(Duration.ofSeconds(90))
        .until(() -> Files.readString(log).contains("Started BatchConsoleApiApplication")
            || !child.process().isAlive());
    assertThat(child.process().isAlive()).as("Console startup log: %s", log).isTrue();
    return child;
  }

  private record Attempt(String key, JsonNode body, Instant at) {}

  private record Child(Process process, Path log) implements AutoCloseable {
    void kill() throws Exception {
      process.destroyForcibly();
      assertThat(process.waitFor(15, TimeUnit.SECONDS))
          .as("killed Console: %s", log)
          .isTrue();
    }

    @Override
    public void close() throws Exception {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) kill();
      }
      assertThat(process.isAlive()).isFalse();
    }
  }
}
