package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import nl.martijndwars.webpush.Utils;
import org.apache.kafka.clients.admin.Admin;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
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
@DisplayName("Job Push 独立 Console 强杀后自然到期保留 UNKNOWN，不自动重投")
class JobPushProcessRecoveryE2eIT extends AbstractIntegrationTest {
  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);
  private static final String USER = "r32-push-process-user";
  private static final CountDownLatch RECEIVED = new CountDownLatch(1);
  private static final CountDownLatch RELEASE_RESPONSE = new CountDownLatch(1);
  private static final AtomicInteger REQUESTS = new AtomicInteger();
  private static final AtomicReference<Process> FIRST_CONSOLE = new AtomicReference<>();
  private static HttpServer endpoint;
  private static KeyPair vapid;

  private final JdbcTemplate jdbc;

  private final WorkflowRunLaunchService launch;

  private final DataSource businessDataSource;

  @LocalServerPort
  private int port;

  @Autowired
  JobPushProcessRecoveryE2eIT(
      JdbcTemplate jdbc,
      WorkflowRunLaunchService launch,
      @Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.jdbc = jdbc;
    this.launch = launch;
    this.businessDataSource = businessDataSource;
  }

  @BeforeAll
  static void startReceiver() throws Exception {
    if (EmptyChecks.isNull(Security.getProvider("BC")))
      Security.addProvider(new BouncyCastleProvider());
    vapid = keyPair();
    endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint.createContext("/device", exchange -> {
      try {
        byte[] encrypted = exchange.getRequestBody().readAllBytes();
        if (encrypted.length < 100
            || !"aes128gcm".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        REQUESTS.incrementAndGet();
        // 接收端副作用已提交后，首次通道调用才按预期超时。
        FIRST_CONSOLE.get().destroyForcibly();
        RECEIVED.countDown();
        // 在发送方三秒截止时间到期前，终止接收线程。
        if (!RELEASE_RESPONSE.await(90, TimeUnit.SECONDS))
          throw new IOException("response gate not released");
        exchange.sendResponseHeaders(201, -1);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (IOException closedSender) {
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

  @Test
  @DisplayName("真实 IMPORT 到 Job Kafka 和加密 Push，在途强杀后原义务自然转人工核对")
  void shouldRequireManualReconciliation_whenConsoleDiesAfterProviderReceivesRequest()
      throws Exception {
    final String insertTenantSql = """
            insert into batch.tenant (tenant_id,tenant_name,status,created_by)\s\
            values (?,?,'ACTIVE','push-process-test') on conflict (tenant_id) do nothing
        """;
    final String updateJobDefinitionSql = """
            update batch.job_definition set default_params=?::jsonb where tenant_id=? and job_code=?
        """;
    final String insertConsolePushSubscriptionSql = """
        insert into batch.console_push_subscription (tenant_id,username,endpoint,p256dh_key,auth_secret)
        values (?,?,?,?,?)
        """;
    final String selectJobInstanceIdSql = """
            select n.job_instance_id from batch.workflow_node_run n\s\
            join batch.workflow_run r on r.id=n.workflow_run_id where r.tenant_id=? and r.id=?\s\
            and n.node_code='JOB'
        """;
    final String deleteConsolePushJobDeliverySql = """
        delete from batch.console_push_job_delivery where tenant_id=? and operator_id=?
    """;
    final String deleteConsolePushSubscriptionSql = """
        delete from batch.console_push_subscription where tenant_id=? and username=?
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id=? and created_by='push-process-test'
    """;

    // 模拟外部服务接收加密请求，但不返回 ACK。
    // 共享旧版 E2E Job 夹具使用 t1，但不会创建对应的租户登记行。
    int registered = jdbc.update(insertTenantSql, TENANT, TENANT);
    var seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    String customer = "PUSH" + Long.toUnsignedString(System.nanoTime());
    String content = JsonUtils.toJson(List.of(Map.of(
        "customerNo",
        customer,
        "customerName",
        "Push recovery",
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
    var deviceKeys = keyPair();
    jdbc.update(
        insertConsolePushSubscriptionSql,
        TENANT,
        USER,
        "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/device",
        encode(Utils.encode((ECPublicKey) deviceKeys.getPublic())),
        encode(new byte[16]));
    List<Child> children = new ArrayList<>();
    try {
      Child first = startConsole(children);
      FIRST_CONSOLE.set(first.process());
      long run = Long.parseLong(launch
          .launch(new LaunchRequest(
              TENANT,
              seed.workflowDefinitionId(),
              BIZ_DATE,
              TriggerType.API,
              seed.requestId(),
              "r32-push-process",
              Map.of("operatorId", USER)))
          .instanceNo());
      assertThat(RECEIVED.await(90, TimeUnit.SECONDS))
          .as("real Job Kafka consumed and encrypted Push received")
          .isTrue();
      first.kill();
      RELEASE_RESPONSE.countDown();
      long job = jdbc.queryForObject(selectJobInstanceIdSql, Long.class, TENANT, run);
      String eventKey = TENANT + ":job:" + job + ":terminal:SUCCESS";
      var interrupted = deviceRow(eventKey);
      assertThat(interrupted.get("delivery_status")).isEqualTo("IN_FLIGHT");
      assertThat(interrupted.get("attempt")).isEqualTo(1);
      assertThat(interrupted.get("delivery_claim_token")).isNotNull();
      Instant claimUntil = ((Timestamp) interrupted.get("delivery_claim_until")).toInstant();
      assertThat(claimUntil).isAfter(Instant.now());
      assertThat(REQUESTS.get()).isEqualTo(1);
      assertBusinessAndIntake(run, job, customer, eventKey);
      try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
        var offsets = admin
            .listConsumerGroupOffsets("batch-console-job-terminal-notifications")
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS);
        assertThat(offsets.entrySet().stream()
                .filter(e -> e.getKey().topic().equals(BatchTopics.JOB_TERMINAL_V1))
                .mapToLong(e -> e.getValue().offset())
                .sum())
            .isEqualTo(1);
      }
      Child replacement = startConsole(children);
      // 生产队列枚举有意以权威租户登记表为准。
      await()
          .atMost(Duration.ofSeconds(180))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(() -> {
            assertThat(replacement.process().isAlive())
                .as("replacement Console: %s", replacement.log())
                .isTrue();
            assertThat(deviceRow(eventKey).get("delivery_status")).isEqualTo("EXHAUSTED");
          });
      assertThat(Instant.now()).isAfterOrEqualTo(claimUntil);
      var recovered = deviceRow(eventKey);
      assertThat(recovered.get("attempt")).isEqualTo(1);
      assertThat(recovered.get("error_message").toString()).startsWith("UNKNOWN:");
      assertThat(recovered.get("delivery_claim_token")).isNull();
      assertThat(recovered.get("delivery_claim_until")).isNull();
      assertThat(recovered.get("next_retry_at")).isNull();
      // 不修改租约或业务状态来加速此生产恢复路径。
      await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
        assertThat(replacement.process().isAlive()).isTrue();
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(deviceRow(eventKey).get("attempt")).isEqualTo(1);
      });
      assertBusinessAndIntake(run, job, customer, eventKey);
    } finally {
      RELEASE_RESPONSE.countDown();
      for (Child child : children) child.close();
      jdbc.update(deleteConsolePushJobDeliverySql, TENANT, USER);
      jdbc.update(deleteConsolePushSubscriptionSql, TENANT, USER);
      if (registered == 1) jdbc.update(deleteTenantSql, TENANT);
    }
  }

  private Map<String, Object> deviceRow(String key) {
    final String selectStatementSql = """
        select * from batch.console_push_job_delivery where tenant_id=? and terminal_event_key=?\s\
        and record_type='DEVICE'
    """;

    return jdbc.queryForMap(selectStatementSql, TENANT, key);
  }

  private void assertBusinessAndIntake(long run, long job, String customer, String key) {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectOperatorIdSql = """
        select operator_id from batch.job_instance where tenant_id=? and id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_terminal_notification_receipt where tenant_id=? and event_key=?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.console_push_job_delivery where tenant_id=? and terminal_event_key=?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.outbox_event where tenant_id=? and aggregate_type='JOB_INSTANCE'\s\
        and aggregate_id=? and event_type='JOB_TERMINAL' and publish_status='PUBLISHED'
    """;
    final String selectCountSql4 = """
        select count(*) from biz.customer_account where tenant_id=? and customer_no=?
    """;

    String selectedRunStatus = jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, run);
    assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    String selectedOperatorId = jdbc.queryForObject(selectOperatorIdSql, String.class, TENANT, job);
    assertThat(selectedOperatorId).isEqualTo(USER);
    Integer selectedWorkflowTerminalNotificationReceiptCount =
        jdbc.queryForObject(selectCountSql, Integer.class, TENANT, key);
    assertThat(selectedWorkflowTerminalNotificationReceiptCount).isEqualTo(1);
    Integer selectedConsolePushJobDeliveryCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, TENANT, key);
    assertThat(selectedConsolePushJobDeliveryCount).isEqualTo(2);
    Integer selectedOutboxEventCount =
        jdbc.queryForObject(selectCountSql3, Integer.class, TENANT, job);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
    Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
        .queryForObject(selectCountSql4, Integer.class, TENANT, customer);
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
    properties.put("batch.console.push.enabled", true);
    properties.put("batch.console.push.job-notify.enabled", true);
    properties.put("batch.console.push.job-notify.poll-interval-millis", 200);
    properties.put("batch.console.push.approval-notify.enabled", false);
    properties.put(
        "batch.console.push.public-key", encode(Utils.encode((ECPublicKey) vapid.getPublic())));
    properties.put(
        "batch.console.push.private-key", encode(Utils.encode((ECPrivateKey) vapid.getPrivate())));
    properties.put("batch.console.orchestrator.base-url", "http://127.0.0.1:" + port);
    properties.put("batch.orchestrator.base-url", "http://127.0.0.1:" + port);
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dbatch.security.ssrf-guard.allow-private=true",
        "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        BatchConsoleApiApplication.class.getName()));
    properties.forEach((name, value) -> command.add("--" + name + "=" + value));
    Path directory = Path.of("../logs/verification/workflow").toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path log = Files.createTempFile(directory, "r32-push-console-process-", ".log");
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

  private static KeyPair keyPair() throws Exception {
    var generator = KeyPairGenerator.getInstance("ECDH", "BC");
    generator.initialize(new ECGenParameterSpec("prime256v1"));
    return generator.generateKeyPair();
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

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
