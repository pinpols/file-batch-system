package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.apps.E2eTriggerApplication;
import io.github.pinpols.batch.e2e.support.BatchDaySettlementChildProcess;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.forensic.ForensicExportProperties;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayOpenScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.ResultVersionRetentionScheduler;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import io.github.pinpols.batch.trigger.infrastructure.QuartzScheduledFireEnumerator;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.infrastructure.scheduler.TriggerReconciler;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.TriggerListener;
import org.quartz.impl.matchers.EverythingMatcher;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.KeyMatcher;
import org.quartz.listeners.JobListenerSupport;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 真实 Console HTTP → Trigger HTTP → 集中审批 → Trigger Outbox/Kafka → Workflow 节点链路。 */
@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.default-tenant-id=t1",
      "batch.console.security.allowed-tenants=t1",
      "batch.console.orchestrator.base-url=http://127.0.0.1:${local.server.port}",
      "batch.worker.registry.fail-fast-on-startup=true",
      "batch.outbox.poll-interval-millis=200",
      "batch.outbox.min-poll-interval-millis=200",
      "batch.resource-scheduler.waiting-dispatch-interval-millis=200",
      "batch.workflow.cross-day-dep.poll-interval-millis=200",
      "batch.trigger.outbox.poll-interval-millis=200",
      "batch.trigger.kafka.send-timeout-seconds=5"
    })
@Tag("critical")
@DisplayName("计划 occurrence 补跑经真实 Console/Trigger HTTP 与集中审批后创建 WorkflowRun 和 JOB 节点实例")
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
class WorkflowOccurrenceCatchUpApprovalE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String OPERATOR = "e2e-catch-up-operator";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 4, 30);
  private static final Instant FIRE_TIME = Instant.parse("2026-04-30T09:30:00Z");
  private static final int TRIGGER_PORT = reservePort();
  private static final int SECOND_TRIGGER_PORT = reservePort();
  private static final String E2E_INTERNAL_SECRET = "e2e-only-" + "x".repeat(48);
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext triggerContext;

  @DynamicPropertySource
  static void registerTriggerUrl(DynamicPropertyRegistry registry) throws Exception {
    createKafkaTopic(BatchTopics.TRIGGER_LAUNCH_V1);
    createKafkaTopic(BatchTopics.WORKFLOW_TERMINAL_V1);
    registry.add("batch.console.trigger.base-url", () -> "http://127.0.0.1:" + TRIGGER_PORT);
    registry.add("batch.security.internal-secret", () -> E2E_INTERNAL_SECRET);
  }

  private final JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper;

  private final ConsoleJwtService consoleJwtService;

  private final ForensicExportProperties forensicExportProperties;

  private final BatchDaySettleScheduler batchDaySettleScheduler;

  private final BatchDayOpenScheduler batchDayOpenScheduler;

  private final ResultVersionRetentionScheduler resultVersionRetentionScheduler;

  private final PartitionReclaimUnit partitionReclaimUnit;

  private final JobPartitionMapper jobPartitionMapper;

  private final ResultVersionPromoteService resultVersionPromoteService;

  private final DataSource businessDataSource;

  @Value("${local.server.port}")
  private int consolePort;

  @Autowired
  WorkflowOccurrenceCatchUpApprovalE2eIT(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ConsoleJwtService consoleJwtService,
      ForensicExportProperties forensicExportProperties,
      BatchDaySettleScheduler batchDaySettleScheduler,
      BatchDayOpenScheduler batchDayOpenScheduler,
      ResultVersionRetentionScheduler resultVersionRetentionScheduler,
      PartitionReclaimUnit partitionReclaimUnit,
      JobPartitionMapper jobPartitionMapper,
      ResultVersionPromoteService resultVersionPromoteService,
      @Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.consoleJwtService = consoleJwtService;
    this.forensicExportProperties = forensicExportProperties;
    this.batchDaySettleScheduler = batchDaySettleScheduler;
    this.batchDayOpenScheduler = batchDayOpenScheduler;
    this.resultVersionRetentionScheduler = resultVersionRetentionScheduler;
    this.partitionReclaimUnit = partitionReclaimUnit;
    this.jobPartitionMapper = jobPartitionMapper;
    this.resultVersionPromoteService = resultVersionPromoteService;
    this.businessDataSource = businessDataSource;
  }

  @AfterAll
  static void stopTriggerContext() {
    if (triggerContext != null) {
      triggerContext.close();
    }
  }

  @Test
  @DisplayName("人工批次日补跑第一项响应丢失后核对并接续第二项")
  void shouldReconcileAndContinueManualBatchAfterRealApprovalResponseLoss() throws Exception {
    LaunchSeed first = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LaunchSeed second = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String updateCalendarPolicySql =
        "update batch.business_calendar set catch_up_policy = 'MANUAL_APPROVAL', catch_up_max_days = 3 where tenant_id = ? and calendar_code = 'e2e_calendar'";
    jdbcTemplate.update(updateCalendarPolicySql, TENANT);
    String requestKey = "manual-recovery-" + UUID.randomUUID();
    String firstKey = requestKey + ":" + first.jobCode();
    String secondKey = requestKey + ":" + second.jobCode();
    AtomicInteger approvalPosts = new AtomicInteger();
    var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    proxy.createContext("/", exchange -> {
      try {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        URI target = URI.create("http://127.0.0.1:" + consolePort + exchange.getRequestURI());
        var upstream = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(20))
            .method(
                exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBytes));
        for (String name : List.of(
            "Content-Type", "Idempotency-Key", "X-Internal-Secret", "X-Request-Id", "X-Trace-Id")) {
          String value = exchange.getRequestHeaders().getFirst(name);
          if (value != null) upstream.header(name, value);
        }
        var response = HTTP_CLIENT.send(upstream.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (exchange.getRequestMethod().equals("POST")
            && exchange.getRequestURI().getPath().equals("/internal/approvals")
            && approvalPosts.incrementAndGet() == 1
            && response.statusCode() == 200) {
          exchange.close();
          return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException(failure);
      } finally {
        exchange.close();
      }
    });
    proxy.start();
    Map<String, Object> properties = new HashMap<>();
    AbstractIntegrationTest.registerDynamicProperties(
        (name, supplier) -> properties.put(name, supplier.get()), false);
    properties.put("server.port", 0);
    properties.put("server.address", "127.0.0.1");
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("spring.flyway.enabled", false);
    properties.put(
        "spring.autoconfigure.exclude",
        String.join(
            ",",
            OpenAiChatAutoConfiguration.class.getName(),
            AnthropicChatAutoConfiguration.class.getName()));
    properties.put("batch.startup-self-check.enabled", false);
    properties.put("batch.security.bypass-mode", false);
    properties.put("batch.console.security.single-session-enabled", false);
    properties.put("batch.console.security.default-tenant-id", TENANT);
    properties.put("batch.console.security.allowed-tenants", "t1,t2");
    properties.put("batch.security.internal-secret", E2E_INTERNAL_SECRET);
    properties.put(
        "batch.console.orchestrator.base-url",
        "http://127.0.0.1:" + proxy.getAddress().getPort());
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    try (var console = new SpringApplicationBuilder(BatchConsoleApiApplication.class)
        .profiles("test", "e2e")
        .run(arguments)) {
      assertThat(console.getBean(BatchSecurityProperties.class).isBypassMode()).isFalse();
      int port = console.getEnvironment().getProperty("local.server.port", Integer.class);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + port)
          .responseTimeout(Duration.ofSeconds(60))
          .build();
      AuthenticatedConsole admin = authenticateConsole(
          client, console.getBean(ConsoleJwtService.class), OPERATOR, "ROLE_ADMIN");
      String body = """
          {"tenantId":"%s","calendarCode":"e2e_calendar","jobCodes":["%s","%s"],"reason":"manual recovery"}
          """.formatted(TENANT, first.jobCode(), second.jobCode());
      client
          .post()
          .uri("/api/console/jobs/batch-days/2035-01-01/catchup")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body)
          .exchange()
          .expectStatus()
          .is5xxServerError();
      String selectApproval =
          "select approval_no from batch.approval_command where tenant_id = ? and source_idempotency_key = ?";
      var approvals = jdbcTemplate.queryForList(selectApproval, String.class, TENANT, firstKey);
      assertThat(approvals).hasSize(1);
      List<String> initialSecondApprovals =
          jdbcTemplate.queryForList(selectApproval, String.class, TENANT, secondKey);
      assertThat(initialSecondApprovals).isEmpty();
      client
          .post()
          .uri("/api/console/jobs/batch-days/catchup/requests/continue")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "continue-blocked-" + UUID.randomUUID())
          .header("X-Original-Idempotency-Key", requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("tenantId", TENANT))
          .exchange()
          .expectStatus()
          .isEqualTo(409);
      assertThat(approvalPosts.get()).isEqualTo(1);
      client
          .post()
          .uri("/api/console/jobs/batch-days/catchup/requests/reconcile")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "reconcile-" + UUID.randomUUID())
          .header("X-Original-Idempotency-Key", requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("tenantId", TENANT, "jobCode", first.jobCode()))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("RUNNING")
          .jsonPath("$.data.items[0].referenceNo")
          .isEqualTo(approvals.getFirst())
          .jsonPath("$.data.items[1].status")
          .isEqualTo("PENDING");
      client
          .post()
          .uri("/api/console/jobs/batch-days/catchup/requests/continue")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "continue-" + UUID.randomUUID())
          .header("X-Original-Idempotency-Key", requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("tenantId", TENANT))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("COMPLETE")
          .jsonPath("$.data.items[0].referenceNo")
          .isEqualTo(approvals.getFirst())
          .jsonPath("$.data.items[1].status")
          .isEqualTo("ACCEPTED");
      assertThat(approvalPosts.get()).isEqualTo(2);
      List<String> finalFirstApprovals =
          jdbcTemplate.queryForList(selectApproval, String.class, TENANT, firstKey);
      assertThat(finalFirstApprovals).containsExactly(approvals.getFirst());
      List<String> finalSecondApprovals =
          jdbcTemplate.queryForList(selectApproval, String.class, TENANT, secondKey);
      assertThat(finalSecondApprovals).hasSize(1);
    } finally {
      proxy.stop(0);
    }
  }

  @Test
  @DisplayName("Console 进程在 Trigger 接纳后崩溃，重启后按原键核对")
  void shouldReconcileBatchDayAutoAfterConsoleProcessCrash() throws Exception {
    startTriggerHttpServer();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String updateCalendarPolicySql =
        "update batch.business_calendar set catch_up_policy = 'AUTO', catch_up_max_days = 3 where tenant_id = ? and calendar_code = 'e2e_calendar'";
    jdbcTemplate.update(updateCalendarPolicySql, TENANT);
    String requestKey = "batch-crash-e2e-" + UUID.randomUUID();
    String sourceKey = requestKey + ":" + seed.jobCode();
    CountDownLatch accepted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger launches = new AtomicInteger();
    AtomicReference<String> consoleResponse = new AtomicReference<>("not scheduled");
    var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    proxy.createContext("/", exchange -> {
      try {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        URI target = URI.create("http://127.0.0.1:" + TRIGGER_PORT + exchange.getRequestURI());
        var upstream = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(20))
            .method(
                exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBytes));
        for (String name : List.of(
            "Content-Type", "Idempotency-Key", "X-Internal-Secret", "X-Request-Id", "X-Trace-Id")) {
          String value = exchange.getRequestHeaders().getFirst(name);
          if (value != null) upstream.header(name, value);
        }
        var response = HTTP_CLIENT.send(upstream.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (exchange.getRequestURI().getPath().equals("/api/triggers/launch")) {
          launches.incrementAndGet();
          if (response.statusCode() == 200) accepted.countDown();
          release.await(45, TimeUnit.SECONDS);
          return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException(failure);
      } finally {
        exchange.close();
      }
    });
    proxy.start();
    List<Process> children = new ArrayList<>();
    try {
      int firstPort = reservePort();
      Process first = startCatchUpConsoleProcess(firstPort, proxy.getAddress().getPort(), children);
      WebTestClient firstClient = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + firstPort)
          .responseTimeout(Duration.ofSeconds(60))
          .build();
      AuthenticatedConsole admin =
          authenticateConsole(firstClient, consoleJwtService, OPERATOR, "ROLE_ADMIN");
      String body = """
          {"tenantId":"%s","calendarCode":"e2e_calendar","jobCodes":["%s"],"reason":"process crash"}
          """.formatted(TENANT, seed.jobCode());
      var pending = CompletableFuture.runAsync(() -> {
        try {
          consoleResponse.set("sending request");
          var response = firstClient
              .post()
              .uri("/api/console/jobs/batch-days/2035-01-01/catchup")
              .cookie("batch_console_token", admin.jwt())
              .cookie("XSRF-TOKEN", admin.csrf())
              .header("X-XSRF-TOKEN", admin.csrf())
              .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
              .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, requestKey)
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(body)
              .exchange()
              .expectBody(String.class)
              .returnResult();
          consoleResponse.set("HTTP " + response.getStatus() + ": " + response.getResponseBody());
        } catch (RuntimeException expectedDisconnect) {
          // 故意在请求进行中终止进程，以验证恢复行为。
          consoleResponse.set(expectedDisconnect.toString());
        }
      });
      assertThat(accepted.await(30, TimeUnit.SECONDS))
          .as("Trigger accepted before crash; Console response: %s", consoleResponse.get())
          .isTrue();
      String selectRequestSql =
          "select request_id from batch.trigger_request where tenant_id = ? and dedup_key = ? and parent_workflow_run_id is null";
      var requestIds = jdbcTemplate.queryForList(selectRequestSql, String.class, TENANT, sourceKey);
      assertThat(requestIds).hasSize(1);
      String triggerRequest = requestIds.getFirst();
      first.destroyForcibly();
      assertThat(first.waitFor(15, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      pending.get(15, TimeUnit.SECONDS);
      int secondPort = reservePort();
      startCatchUpConsoleProcess(secondPort, proxy.getAddress().getPort(), children);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + secondPort)
          .responseTimeout(Duration.ofSeconds(60))
          .build();
      AuthenticatedConsole recoveredAdmin =
          authenticateConsole(client, consoleJwtService, OPERATOR, "ROLE_ADMIN");
      String countChildRequestsSql =
          "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ? and parent_workflow_run_id is not null";
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        Integer childCount =
            jdbcTemplate.queryForObject(countChildRequestsSql, Integer.class, TENANT, sourceKey);
        assertThat(childCount).isGreaterThan(0);
      });
      client
          .get()
          .uri(uri -> uri.path("/api/console/jobs/batch-days/catchup/requests/status")
              .queryParam("tenantId", TENANT)
              .build())
          .cookie("batch_console_token", recoveredAdmin.jwt())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, requestKey)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("RUNNING")
          .jsonPath("$.data.items[0].status")
          .isEqualTo("IN_FLIGHT");
      client
          .post()
          .uri("/api/console/jobs/batch-days/catchup/requests/reconcile")
          .cookie("batch_console_token", recoveredAdmin.jwt())
          .cookie("XSRF-TOKEN", recoveredAdmin.csrf())
          .header("X-XSRF-TOKEN", recoveredAdmin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "reconcile-" + UUID.randomUUID())
          .header("X-Original-Idempotency-Key", requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("tenantId", TENANT, "jobCode", seed.jobCode()))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("COMPLETE")
          .jsonPath("$.data.items[0].referenceNo")
          .isEqualTo(triggerRequest);
      assertThat(launches.get()).isEqualTo(1);
      String countRootRequestsSql =
          "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ? and parent_workflow_run_id is null";
      String countOutboxEventsSql =
          "select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?";
      Integer rootRequestCount =
          jdbcTemplate.queryForObject(countRootRequestsSql, Integer.class, TENANT, sourceKey);
      assertThat(rootRequestCount).isEqualTo(1);
      Integer outboxEventCount =
          jdbcTemplate.queryForObject(countOutboxEventsSql, Integer.class, TENANT, triggerRequest);
      assertThat(outboxEventCount).isEqualTo(1);
    } finally {
      release.countDown();
      proxy.stop(0);
      for (Process child : children) {
        if (child.isAlive()) {
          child.destroy();
          if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(15, TimeUnit.SECONDS);
          }
        }
      }
    }
  }

  private Process startCatchUpConsoleProcess(int port, int proxyPort, List<Process> children)
      throws Exception {
    Map<String, Object> properties = new HashMap<>();
    AbstractIntegrationTest.registerDynamicProperties(
        (name, supplier) -> properties.put(name, supplier.get()), false);
    properties.put("spring.profiles.active", "test,e2e");
    properties.put("spring.flyway.enabled", false);
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("server.port", port);
    properties.put("server.address", "127.0.0.1");
    properties.put(
        "spring.autoconfigure.exclude",
        String.join(
            ",",
            OpenAiChatAutoConfiguration.class.getName(),
            AnthropicChatAutoConfiguration.class.getName()));
    properties.put("batch.startup-self-check.enabled", false);
    properties.put("batch.security.bypass-mode", false);
    properties.put("batch.console.security.single-session-enabled", false);
    properties.put("batch.console.security.default-tenant-id", TENANT);
    properties.put("batch.console.security.allowed-tenants", "t1,t2");
    properties.put("batch.security.internal-secret", E2E_INTERNAL_SECRET);
    properties.put("batch.console.orchestrator.base-url", "http://127.0.0.1:" + consolePort);
    properties.put("batch.console.trigger.base-url", "http://127.0.0.1:" + proxyPort);
    properties.put("batch.console.trigger.read-timeout-millis", 60000);
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dbatch.security.ssrf-guard.allow-private=true",
        "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        BatchConsoleApiApplication.class.getName()));
    properties.forEach((name, value) -> command.add("--" + name + "=" + value));
    Path directory = Path.of("../logs/verification/backend25-2026-10-30/batchday-process")
        .toAbsolutePath()
        .normalize();
    Files.createDirectories(directory);
    Path log = Files.createTempFile(directory, "console-", ".log");
    Process child = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
    children.add(child);
    await()
        .atMost(Duration.ofSeconds(90))
        .until(() -> Files.readString(log).contains("Started BatchConsoleApiApplication")
            || !child.isAlive());
    assertThat(child.isAlive()).as("Console startup log: %s", log).isTrue();
    // Started 日志早于 ApplicationReadyEvent；维护共享状态确认后才允许业务写入。
    String readinessToken =
        consoleJwtService.issueToken(OPERATOR, TENANT, Set.of("ROLE_ADMIN")).accessToken();
    HttpRequest readiness = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
        .header("Cookie", "batch_console_token=" + readinessToken)
        .timeout(Duration.ofSeconds(3))
        .GET()
        .build();
    await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
      assertThat(child.isAlive()).as("Console startup log: %s", log).isTrue();
      assertThat(HTTP_CLIENT
              .send(readiness, HttpResponse.BodyHandlers.discarding())
              .statusCode())
          .as("Console readiness; startup log: %s", log)
          .isEqualTo(200);
    });
    return child;
  }

  @Test
  @DisplayName("批次日 AUTO 响应丢失后通过真实 Console 与 Trigger 按原键核对且不重复接纳")
  void shouldReconcileBatchDayAutoAfterRealTriggerResponseIsDropped() throws Exception {
    startTriggerHttpServer();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String updateCalendarSql = """
        update batch.business_calendar set catch_up_policy = 'AUTO', catch_up_max_days = 3
         where tenant_id = ? and calendar_code = 'e2e_calendar'
        """;
    jdbcTemplate.update(updateCalendarSql, TENANT);
    LocalDate bizDate = LocalDate.of(2035, 1, 1);
    String requestKey = "batch-auto-e2e-" + UUID.randomUUID();
    String sourceKey = requestKey + ":" + seed.jobCode();
    AtomicInteger dropped = new AtomicInteger();
    AtomicInteger forwarded = new AtomicInteger();
    var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    proxy.createContext("/", exchange -> {
      try {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        URI target = URI.create("http://127.0.0.1:" + TRIGGER_PORT + exchange.getRequestURI());
        var upstream = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(20))
            .method(
                exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBytes));
        for (String name : List.of(
            "Content-Type", "Idempotency-Key", "X-Internal-Secret", "X-Request-Id", "X-Trace-Id")) {
          String value = exchange.getRequestHeaders().getFirst(name);
          if (value != null) upstream.header(name, value);
        }
        var response = HTTP_CLIENT.send(upstream.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (exchange.getRequestURI().getPath().equals("/api/triggers/launch")) {
          if (response.statusCode() == 200) forwarded.incrementAndGet();
          dropped.incrementAndGet();
          exchange.close();
          return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
      } catch (Exception failure) {
        exchange.sendResponseHeaders(502, -1);
      } finally {
        exchange.close();
      }
    });
    proxy.start();
    Map<String, Object> properties = new HashMap<>();
    AbstractIntegrationTest.registerDynamicProperties(
        (name, supplier) -> properties.put(name, supplier.get()), false);
    properties.put("server.port", 0);
    properties.put("server.address", "127.0.0.1");
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("spring.flyway.enabled", false);
    properties.put(
        "spring.autoconfigure.exclude",
        String.join(
            ",",
            OpenAiChatAutoConfiguration.class.getName(),
            AnthropicChatAutoConfiguration.class.getName()));
    properties.put("batch.startup-self-check.enabled", false);
    properties.put("batch.security.bypass-mode", false);
    properties.put("batch.console.security.single-session-enabled", false);
    properties.put("batch.console.security.default-tenant-id", TENANT);
    properties.put("batch.console.security.allowed-tenants", "t1,t2");
    properties.put("batch.security.internal-secret", E2E_INTERNAL_SECRET);
    properties.put("batch.console.orchestrator.base-url", "http://127.0.0.1:" + consolePort);
    properties.put(
        "batch.console.trigger.base-url",
        "http://127.0.0.1:" + proxy.getAddress().getPort());
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    try (var console = new SpringApplicationBuilder(BatchConsoleApiApplication.class)
        .profiles("test", "e2e")
        .run(arguments)) {
      assertThat(console.getBean(BatchSecurityProperties.class).isBypassMode()).isFalse();
      int port = console.getEnvironment().getProperty("local.server.port", Integer.class);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + port)
          .responseTimeout(Duration.ofSeconds(60))
          .build();
      AuthenticatedConsole admin = authenticateConsole(
          client, console.getBean(ConsoleJwtService.class), OPERATOR, "ROLE_ADMIN");
      String body = """
          {"tenantId":"%s","calendarCode":"e2e_calendar","jobCodes":["%s"],"reason":"lost response"}
          """.formatted(TENANT, seed.jobCode());
      client
          .post()
          .uri("/api/console/jobs/batch-days/" + bizDate + "/catchup")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body)
          .exchange()
          .expectStatus()
          .is5xxServerError();
      assertThat(forwarded.get()).isGreaterThanOrEqualTo(1);
      assertThat(dropped.get()).isGreaterThanOrEqualTo(1);
      String selectRequestSql =
          "select request_id from batch.trigger_request where tenant_id = ? and dedup_key = ?";
      var requestIds = jdbcTemplate.queryForList(selectRequestSql, String.class, TENANT, sourceKey);
      assertThat(requestIds).hasSize(1);
      String triggerRequest = requestIds.getFirst();
      client
          .get()
          .uri(uri -> uri.path("/api/console/jobs/batch-days/catchup/requests/status")
              .queryParam("tenantId", TENANT)
              .build())
          .cookie("batch_console_token", admin.jwt())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, requestKey)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("UNKNOWN");
      client
          .post()
          .uri("/api/console/jobs/batch-days/catchup/requests/reconcile")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "reconcile-" + UUID.randomUUID())
          .header("X-Original-Idempotency-Key", requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("tenantId", TENANT, "jobCode", seed.jobCode()))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("COMPLETE")
          .jsonPath("$.data.items[0].referenceNo")
          .isEqualTo(triggerRequest);
      int beforeReplay = dropped.get();
      client
          .post()
          .uri("/api/console/jobs/batch-days/" + bizDate + "/catchup")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, requestKey)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.items[0].referenceNo")
          .isEqualTo(triggerRequest);
      assertThat(dropped.get()).isEqualTo(beforeReplay);
      String countRequestSql =
          "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?";
      String countOutboxSql =
          "select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?";
      Integer requestCount =
          jdbcTemplate.queryForObject(countRequestSql, Integer.class, TENANT, sourceKey);
      assertThat(requestCount).isEqualTo(1);
      Integer outboxCount =
          jdbcTemplate.queryForObject(countOutboxSql, Integer.class, TENANT, triggerRequest);
      assertThat(outboxCount).isEqualTo(1);
    } finally {
      proxy.stop(0);
    }
  }

  @Test
  @DisplayName("真实 Trigger planner 发布 occurrence 与 coverage 时结算器不会误结空日")
  void shouldConvergeCoverage_whenPublicationAndSettlementRace() throws Exception {
    final String selectCoverageThroughAtSql = """
        select coverage_through_at from batch.scheduled_occurrence_coverage where tenant_id = ?\s\
        and workflow_definition_id = ? for update\
        """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
        values (?, ?, 'E2E coverage race calendar', 'UTC', true)\s\
        on conflict (tenant_id, calendar_code) do update set enabled = true\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled)\s\
        values (?, ?, 'E2E coverage race workflow', 'DAG', 1, true, 'CRON', '0 0 9 * * ?', 'UTC', 'SCHEDULED',\s\
        ?, true) returning id\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, ?)\s\
        on conflict (tenant_id, calendar_code, biz_date) do update\s\
        set day_status = 'CUTOFF', cutoff_at = excluded.cutoff_at\
        """;
    final String insertScheduledOccurrenceCoverageSql = """
        insert into batch.scheduled_occurrence_coverage (tenant_id, workflow_definition_id,\s\
        configuration_revision, schedule_version, calendar_code, coverage_from_at, coverage_through_at,\s\
        last_success_at) values (?, ?, 0, 1, ?, ?, ?, current_timestamp)\
        """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ? and occurrence_status = 'PLANNED'\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'READINESS_TIMEOUT' where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    startTriggerHttpServer();
    String workflowCode = "E2E_COVERAGE_RACE_" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "e2e_coverage_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, calendarCode);
    Instant scanAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    CronTrigger cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(workflowCode, "coverage-race")
        .startAt(Date.from(scanAt))
        .withSchedule(
            CronScheduleBuilder.cronSchedule("0 0 9 * * ?").inTimeZone(TimeZone.getTimeZone("UTC")))
        .build();
    Instant firstFire = cronTrigger.getFireTimeAfter(Date.from(scanAt)).toInstant();
    LocalDate bizDate = firstFire.atZone(ZoneOffset.UTC).toLocalDate();
    jdbcTemplate.update(
        insertBatchDayInstanceSql, TENANT, calendarCode, bizDate, Timestamp.from(firstFire));
    jdbcTemplate.update(
        insertScheduledOccurrenceCoverageSql,
        TENANT,
        workflowDefinitionId,
        calendarCode,
        Timestamp.from(scanAt.minusSeconds(1)),
        Timestamp.from(firstFire.minusMillis(1)));

    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(TENANT);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 9 * * ?");
    descriptor.setTimezone("UTC");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCalendarCode(calendarCode);
    descriptor.setEnabled(true);
    descriptor.setWorkflowEnabled(true);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (var coverageLock = jdbcTemplate.getDataSource().getConnection()) {
      coverageLock.setAutoCommit(false);
      try (var lock = coverageLock.prepareStatement(selectCoverageThroughAtSql)) {
        lock.setString(1, TENANT);
        lock.setLong(2, workflowDefinitionId);
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      int blockerPid;
      try (var pid = coverageLock.prepareStatement(selectPgBackendPidSql)) {
        try (var result = pid.executeQuery()) {
          assertThat(result.next()).isTrue();
          blockerPid = result.getInt(1);
        }
      }
      TriggerScheduledOccurrencePlanner planner =
          triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
      var plannedFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(cronTrigger);
      Future<Integer> publication =
          executor.submit(() -> planner.materialize(descriptor, plannedFireTimes, scanAt));
      awaitBlockedBySql(blockerPid, "insert into batch.scheduled_occurrence_coverage");

      batchDaySettleScheduler.settle();
      assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");
      coverageLock.commit();
      assertThat(publication.get(20, TimeUnit.SECONDS)).isPositive();
    } finally {
      executor.shutdownNow();
    }

    Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, workflowDefinitionId, Timestamp.from(firstFire));
    assertThat(selectedScheduledOccurrenceCount).isOne();
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isNotEqualTo("SETTLED");
    jdbcTemplate.update(
        updateScheduledOccurrenceSql, TENANT, workflowDefinitionId, Timestamp.from(firstFire));
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SettlementExecutionMode.class)
  @DisplayName("真实 Trigger API 接纳与同 JVM/独立 Orchestrator 结算按业务日围栏串行化")
  void triggerApiAdmissionRacingBatchDaySettlementShouldConverge(
      SettlementExecutionMode settlementMode) throws Exception {
    final String selectIdSql = """
            select id from batch.workflow_definition where tenant_id = ? and id = ? for update
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;
    final String selectDayStatusSql = """
            select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ? for update\
            """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
            enabled) values (?, ?, 'E2E admission fence calendar', 'UTC', '23:59:00', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?\
            """;
    final String updateJobDefinitionSql = """
            update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?\
            """;
    final String deleteTriggerRequestSql = """
            delete from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectCountSql = """
            select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectCountSql2 = """
            select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?
        """;
    final String selectPublishStatusSql = """
        select publish_status from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\
        """;

    startTriggerHttpServer();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "unavailable-" + suffix, TriggerType.API);
    String calendarCode = "e2e_admit_" + suffix.substring(0, 12);
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, seed.jobCode());
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, seed.requestId());

    LocalDate admissionFirstDate = r11BizDate(1);
    insertCutoffBatchDay(calendarCode, admissionFirstDate);
    List<SettlementExecution> settlementExecutions = new ArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (var workflowLock = jdbcTemplate.getDataSource().getConnection()) {
      workflowLock.setAutoCommit(false);
      try (var lock = workflowLock.prepareStatement(selectIdSql)) {
        lock.setString(1, TENANT);
        lock.setLong(2, seed.workflowDefinitionId());
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      int workflowLockPid;
      try (var pid = workflowLock.prepareStatement(selectPgBackendPidSql)) {
        try (var result = pid.executeQuery()) {
          assertThat(result.next()).isTrue();
          workflowLockPid = result.getInt(1);
        }
      }

      String admissionRequestId = "trigger-race-admission-" + suffix;
      Future<HttpResponse<String>> admission = executor.submit(() ->
          postTriggerLaunch(seed.workflowDefinitionId(), admissionFirstDate, admissionRequestId));
      awaitBlockedBySql(workflowLockPid, "insert into batch.trigger_request");
      SettlementExecution settlement = startSettlement(
          settlementMode,
          executor,
          "admission-first-" + suffix,
          calendarCode,
          settlementExecutions);
      releaseSettlement(settlement);
      awaitSettlementBlocked(settlement, "batch.acquire_batch_day_settlement_fence", null);

      workflowLock.commit();
      HttpResponse<String> admissionResponse = admission.get(20, TimeUnit.SECONDS);
      assertThat(admissionResponse.statusCode())
          .as("Trigger API admission failed: %s", admissionResponse.body())
          .isBetween(200, 299);
      awaitSettlementCompletion(settlement);
      Integer selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, admissionRequestId);
      assertThat(selectedTriggerRequestCount).isOne();
      Integer selectedTriggerOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, admissionRequestId);
      assertThat(selectedTriggerOutboxEventCount).isOne();
      await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
        String selectedPublishStatus = jdbcTemplate.queryForObject(
            selectPublishStatusSql, String.class, TENANT, admissionRequestId);
        assertThat(selectedPublishStatus).isEqualTo("PUBLISHED");
      });
      assertThat(batchDayStatus(calendarCode, admissionFirstDate)).isNotEqualTo("SETTLED");
    } finally {
      // 刷新句柄状态期间进程退出。
      executor.shutdownNow();
      cleanupSettlementExecutions(settlementExecutions);
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    LocalDate settlementFirstDate = r11BizDate(2);
    insertCutoffBatchDay(calendarCode, settlementFirstDate);
    String rejectedRequestId = "trigger-race-closed-" + suffix;
    ExecutorService settlementFirstExecutor = Executors.newFixedThreadPool(2);
    try (var batchDayLock = jdbcTemplate.getDataSource().getConnection()) {
      batchDayLock.setAutoCommit(false);
      try (var lock = batchDayLock.prepareStatement(selectDayStatusSql)) {
        lock.setString(1, TENANT);
        lock.setString(2, calendarCode);
        lock.setObject(3, settlementFirstDate);
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString(1)).isEqualTo("CUTOFF");
        }
      }
      int batchDayLockPid;
      try (var pid = batchDayLock.prepareStatement(selectPgBackendPidSql)) {
        try (var result = pid.executeQuery()) {
          assertThat(result.next()).isTrue();
          batchDayLockPid = result.getInt(1);
        }
      }

      SettlementExecution settlement = startSettlement(
          settlementMode,
          settlementFirstExecutor,
          "settlement-first-" + suffix,
          calendarCode,
          settlementExecutions);
      releaseSettlement(settlement);
      awaitSettlementBlocked(settlement, "update batch.batch_day_instance", batchDayLockPid);
      int settlementPid = settlementBackendPid(settlement, batchDayLockPid);

      Future<HttpResponse<String>> rejectedAdmission = settlementFirstExecutor.submit(() ->
          postTriggerLaunch(seed.workflowDefinitionId(), settlementFirstDate, rejectedRequestId));
      awaitBlockedBySql(settlementPid, "batch.acquire_batch_day_settlement_fence");
      batchDayLock.commit();

      awaitSettlementCompletion(settlement);
      HttpResponse<String> rejectedResponse = rejectedAdmission.get(20, TimeUnit.SECONDS);
      assertThat(batchDayStatus(calendarCode, settlementFirstDate)).isEqualTo("SETTLED");
      assertThat(rejectedResponse.statusCode())
          .as("concurrent closed-day Trigger API response: %s", rejectedResponse.body())
          .isEqualTo(409);
    } finally {
      // 子结算 JVM 总会被回收，包括任一竞态中的断言失败情形。
      settlementFirstExecutor.shutdownNow();
      cleanupSettlementExecutions(settlementExecutions);
      assertThat(settlementFirstExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    Integer selectedTriggerRequestCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, rejectedRequestId);
    assertThat(selectedTriggerRequestCountRead2).isZero();
    Integer selectedTriggerOutboxEventCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, rejectedRequestId);
    assertThat(selectedTriggerOutboxEventCountRead2).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SettlementExecutionMode.class)
  @DisplayName("显式 Workflow RERUN 与同 JVM/独立 Orchestrator 结算按业务日围栏串行化")
  void workflowRerunAdmissionRacingBatchDaySettlementShouldConverge(
      SettlementExecutionMode settlementMode) throws Exception {
    final String selectIdSql = """
            select id from batch.workflow_definition where tenant_id = ? and id = ? for update
        """;
    final String selectDayStatusSql = """
            select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ? for update\
            """;

    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        enabled) values (?, ?, 'E2E RERUN admission fence calendar', 'UTC', '23:59:00', true)\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?\
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?\
        """;

    startTriggerHttpServer();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "unavailable-" + suffix, TriggerType.API);
    String calendarCode = "e2e_rerun_admit_" + suffix.substring(0, 12);
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, seed.jobCode());

    LocalDate admissionFirstDate = r11BizDate(9);
    insertCutoffBatchDay(calendarCode, admissionFirstDate);
    List<SettlementExecution> admissionFirstSettlements = new ArrayList<>();
    ExecutorService admissionFirstExecutor = Executors.newFixedThreadPool(2);
    String admissionFirstRequestId = "workflow-rerun-admit-" + suffix;
    try (var workflowLock = jdbcTemplate.getDataSource().getConnection()) {
      workflowLock.setAutoCommit(false);
      try (var lock = workflowLock.prepareStatement(selectIdSql)) {
        lock.setString(1, TENANT);
        lock.setLong(2, seed.workflowDefinitionId());
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      int workflowLockPid = connectionPid(workflowLock);
      Future<HttpResponse<String>> admission =
          admissionFirstExecutor.submit(() -> postTriggerLaunch(
              seed.workflowDefinitionId(),
              admissionFirstDate,
              admissionFirstRequestId,
              TriggerType.RERUN,
              Map.of("rerunReason", "R11 settlement race admission-first")));
      awaitBlockedBySql(workflowLockPid, "insert into batch.trigger_request");

      SettlementExecution settlement = startSettlement(
          settlementMode,
          admissionFirstExecutor,
          "rerun-admit-" + suffix,
          calendarCode,
          admissionFirstSettlements);
      releaseSettlement(settlement);
      awaitSettlementBlocked(settlement, "batch.acquire_batch_day_settlement_fence", null);

      workflowLock.commit();
      HttpResponse<String> response = admission.get(20, TimeUnit.SECONDS);
      assertThat(response.statusCode())
          .as("Workflow RERUN admission failed: %s", response.body())
          .isBetween(200, 299);
      awaitSettlementCompletion(settlement);
      awaitRerunRequestLaunched(admissionFirstRequestId, admissionFirstDate);
      assertThat(batchDayStatus(calendarCode, admissionFirstDate)).isEqualTo("IN_FLIGHT");
    } finally {
      admissionFirstExecutor.shutdownNow();
      cleanupSettlementExecutions(admissionFirstSettlements);
      assertThat(admissionFirstExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    LocalDate settlementFirstDate = r11BizDate(10);
    insertCutoffBatchDay(calendarCode, settlementFirstDate);
    List<SettlementExecution> settlementFirstSettlements = new ArrayList<>();
    ExecutorService settlementFirstExecutor = Executors.newFixedThreadPool(2);
    String settlementFirstRequestId = "workflow-rerun-settle-first-" + suffix;
    try (var batchDayLock = jdbcTemplate.getDataSource().getConnection()) {
      batchDayLock.setAutoCommit(false);
      try (var lock = batchDayLock.prepareStatement(selectDayStatusSql)) {
        lock.setString(1, TENANT);
        lock.setString(2, calendarCode);
        lock.setObject(3, settlementFirstDate);
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString(1)).isEqualTo("CUTOFF");
        }
      }
      int batchDayLockPid = connectionPid(batchDayLock);
      SettlementExecution settlement = startSettlement(
          settlementMode,
          settlementFirstExecutor,
          "rerun-settle-" + suffix,
          calendarCode,
          settlementFirstSettlements);
      releaseSettlement(settlement);
      awaitSettlementBlocked(settlement, "update batch.batch_day_instance", batchDayLockPid);
      int settlementPid = settlementBackendPid(settlement, batchDayLockPid);

      Future<HttpResponse<String>> admission =
          settlementFirstExecutor.submit(() -> postTriggerLaunch(
              seed.workflowDefinitionId(),
              settlementFirstDate,
              settlementFirstRequestId,
              TriggerType.RERUN,
              Map.of("rerunReason", "R11 settlement race settlement-first")));
      awaitBlockedBySql(settlementPid, "batch.acquire_batch_day_settlement_fence");
      batchDayLock.commit();

      awaitSettlementCompletion(settlement);
      HttpResponse<String> response = admission.get(20, TimeUnit.SECONDS);
      assertThat(response.statusCode())
          .as("Workflow RERUN after settlement failed: %s", response.body())
          .isBetween(200, 299);
      awaitRerunRequestLaunched(settlementFirstRequestId, settlementFirstDate);
      assertThat(batchDayStatus(calendarCode, settlementFirstDate)).isEqualTo("IN_FLIGHT");
    } finally {
      settlementFirstExecutor.shutdownNow();
      cleanupSettlementExecutions(settlementFirstSettlements);
      assertThat(settlementFirstExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void awaitRerunRequestLaunched(String requestId, LocalDate bizDate) {
    final String selectTriggerTypeSql = """
        select trigger_type, request_status, biz_date from batch.trigger_request where tenant_id = ?\s\
        and request_id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\s\
        and publish_status = 'PUBLISHED'\
        """;

    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
      Map<String, Object> request =
          jdbcTemplate.queryForMap(selectTriggerTypeSql, TENANT, requestId);
      assertThat(request.get("trigger_type")).isEqualTo("RERUN");
      assertThat(request.get("request_status")).isEqualTo("LAUNCHED");
      assertThat(request.get("biz_date").toString()).isEqualTo(bizDate.toString());
      Integer selectedTriggerOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, requestId);
      assertThat(selectedTriggerOutboxEventCount).isOne();
    });
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SettlementExecutionMode.class)
  @DisplayName("Console MANUAL Workflow 接纳与同 JVM/独立 Orchestrator 结算按业务日围栏串行化")
  void consoleManualAdmissionRacingBatchDaySettlementShouldConverge(
      SettlementExecutionMode settlementMode) throws Exception {
    final String selectIdSql = """
            select id from batch.workflow_definition where tenant_id = ? and id = ? for update
        """;
    final String selectDayStatusSql = """
            select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ? for update\
            """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
            enabled) values (?, ?, 'E2E Console admission fence calendar', 'UTC', '23:59:00', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?\
            """;
    final String updateJobDefinitionSql = """
            update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?\
            """;
    final String selectCountSql = """
            select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_type = 'MANUAL' and biz_date = ?\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.trigger_outbox_event o\s\
            join batch.trigger_request r on r.tenant_id = o.tenant_id and r.request_id = o.request_id\s\
            where r.tenant_id = ? and r.workflow_definition_id = ? and r.trigger_type = 'MANUAL'\s\
            and r.biz_date = ?\
            """;
    final String selectCountSql3 = """
            select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_type = 'MANUAL' and biz_date = ?\
            """;
    final String selectCountSql4 = """
            select count(*) from batch.trigger_outbox_event o\s\
            join batch.trigger_request r on r.tenant_id = o.tenant_id and r.request_id = o.request_id\s\
            where r.tenant_id = ? and r.workflow_definition_id = ? and r.trigger_type = 'MANUAL'\s\
            and r.biz_date = ?\
            """;

    startTriggerHttpServer();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "unavailable-" + suffix, TriggerType.API);
    String calendarCode = "e2e_console_admit_" + suffix.substring(0, 12);
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, seed.jobCode());

    LocalDate admissionFirstDate = r11BizDate(5);
    insertCutoffBatchDay(calendarCode, admissionFirstDate);
    String admissionIdempotencyKey = "console-race-admit-" + suffix;
    List<SettlementExecution> settlementExecutions = new ArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (var workflowLock = jdbcTemplate.getDataSource().getConnection()) {
      workflowLock.setAutoCommit(false);
      try (var lock = workflowLock.prepareStatement(selectIdSql)) {
        lock.setString(1, TENANT);
        lock.setLong(2, seed.workflowDefinitionId());
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      int workflowLockPid = connectionPid(workflowLock);
      Future<HttpResponse<String>> admission = executor.submit(() -> postConsoleResponse(
          "/api/console/workflow-definitions/" + seed.workflowDefinitionId() + "/trigger",
          manualTriggerBody(admissionFirstDate),
          admissionIdempotencyKey));
      awaitBlockedBySql(workflowLockPid, "insert into batch.trigger_request");

      SettlementExecution settlement = startSettlement(
          settlementMode,
          executor,
          "console-admission-first-" + suffix,
          calendarCode,
          settlementExecutions);
      releaseSettlement(settlement);
      awaitSettlementBlocked(settlement, "batch.acquire_batch_day_settlement_fence", null);

      workflowLock.commit();
      HttpResponse<String> admissionResponse = admission.get(20, TimeUnit.SECONDS);
      assertThat(admissionResponse.statusCode())
          .as("Console MANUAL admission failed: %s", admissionResponse.body())
          .isBetween(200, 299);
      awaitSettlementCompletion(settlement);
      Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId(), admissionFirstDate);
      assertThat(selectedTriggerRequestCount).isOne();
      Integer selectedTriggerOutboxEventCount = jdbcTemplate.queryForObject(
          selectCountSql2, Integer.class, TENANT, seed.workflowDefinitionId(), admissionFirstDate);
      assertThat(selectedTriggerOutboxEventCount).isOne();
      assertThat(batchDayStatus(calendarCode, admissionFirstDate)).isNotEqualTo("SETTLED");
    } finally {
      executor.shutdownNow();
      cleanupSettlementExecutions(settlementExecutions);
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    LocalDate settlementFirstDate = r11BizDate(6);
    insertCutoffBatchDay(calendarCode, settlementFirstDate);
    List<SettlementExecution> settlementFirstExecutions = new ArrayList<>();
    ExecutorService settlementFirstExecutor = Executors.newFixedThreadPool(2);
    try (var batchDayLock = jdbcTemplate.getDataSource().getConnection()) {
      batchDayLock.setAutoCommit(false);
      try (var lock = batchDayLock.prepareStatement(selectDayStatusSql)) {
        lock.setString(1, TENANT);
        lock.setString(2, calendarCode);
        lock.setObject(3, settlementFirstDate);
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString(1)).isEqualTo("CUTOFF");
        }
      }
      int batchDayLockPid = connectionPid(batchDayLock);
      SettlementExecution settlement = startSettlement(
          settlementMode,
          settlementFirstExecutor,
          "console-settlement-first-" + suffix,
          calendarCode,
          settlementFirstExecutions);
      releaseSettlement(settlement);
      awaitSettlementBlocked(settlement, "update batch.batch_day_instance", batchDayLockPid);
      int settlementPid = settlementBackendPid(settlement, batchDayLockPid);

      Future<HttpResponse<String>> rejectedAdmission =
          settlementFirstExecutor.submit(() -> postConsoleResponse(
              "/api/console/workflow-definitions/" + seed.workflowDefinitionId() + "/trigger",
              manualTriggerBody(settlementFirstDate),
              "console-race-closed-" + suffix));
      awaitBlockedBySql(settlementPid, "batch.acquire_batch_day_settlement_fence");
      batchDayLock.commit();

      awaitSettlementCompletion(settlement);
      HttpResponse<String> rejectedResponse = rejectedAdmission.get(20, TimeUnit.SECONDS);
      assertThat(batchDayStatus(calendarCode, settlementFirstDate)).isEqualTo("SETTLED");
      assertThat(rejectedResponse.statusCode())
          .as("closed-day Console MANUAL admission response: %s", rejectedResponse.body())
          .isEqualTo(409);
    } finally {
      settlementFirstExecutor.shutdownNow();
      cleanupSettlementExecutions(settlementFirstExecutions);
      assertThat(settlementFirstExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    Integer selectedTriggerRequestCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql3, Integer.class, TENANT, seed.workflowDefinitionId(), settlementFirstDate);
    assertThat(selectedTriggerRequestCountRead2).isZero();
    Integer selectedTriggerOutboxEventCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql4, Integer.class, TENANT, seed.workflowDefinitionId(), settlementFirstDate);
    assertThat(selectedTriggerOutboxEventCountRead2).isZero();
  }

  private SettlementExecution startSettlement(
      SettlementExecutionMode mode,
      ExecutorService executor,
      String label,
      String calendarCode,
      List<SettlementExecution> executions)
      throws Exception {
    final String updateBusinessCalendarSql = """
            update batch.business_calendar set enabled = false where tenant_id = ? and calendar_code = ?\
            """;
    final String updateBusinessCalendarSql2 = """
            update batch.business_calendar set enabled = true where tenant_id = ? and calendar_code = ?
        """;

    if (mode == SettlementExecutionMode.SAME_JVM) {
      SettlementExecution execution = new SettlementExecution(
          mode,
          executor.submit(batchDaySettleScheduler::settle),
          null,
          null,
          null,
          null,
          null,
          null,
          null);
      executions.add(execution);
      return execution;
    }

    // 子结算 JVM 总会被回收，包括任一竞态中的断言失败情形。
    // PostgreSQL 会将 application_name 截断为 63 字节。生成的标识必须短于
    String applicationName = "r11-" + label;
    assertThat(applicationName.getBytes(StandardCharsets.UTF_8))
        .as("PostgreSQL application_name must not be truncated")
        .hasSizeLessThanOrEqualTo(63);
    Path configPath = Files.createTempFile("r11-settle-", ".properties");
    Path readyPath = Files.createTempFile("r11-settle-", ".ready");
    Path releasePath = Files.createTempFile("r11-settle-", ".release");
    Files.deleteIfExists(readyPath);
    Files.deleteIfExists(releasePath);
    Path logPath = Path.of(
            System.getProperty("user.dir"),
            "..",
            "logs",
            "verification",
            "workflow",
            applicationName + ".log")
        .normalize();
    Files.createDirectories(logPath.getParent());
    int affectedBusinessCalendarRows =
        jdbcTemplate.update(updateBusinessCalendarSql, TENANT, calendarCode);
    assertThat(affectedBusinessCalendarRows)
        .as("disable only the test calendar while the child application starts")
        .isOne();
    try {
      writeSettlementProcessConfig(configPath, applicationName, readyPath, releasePath);
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      assertThat(classpath).isNotBlank();
      Process child = new ProcessBuilder(
              Path.of(System.getProperty("java.home"), "bin", "java").toString(),
              "-cp",
              classpath,
              BatchDaySettlementChildProcess.class.getName(),
              configPath.toString())
          .redirectErrorStream(true)
          .redirectOutput(logPath.toFile())
          .start();
      SettlementExecution execution = new SettlementExecution(
          mode,
          null,
          child,
          configPath,
          logPath,
          applicationName,
          calendarCode,
          readyPath,
          releasePath);
      executions.add(execution);
      awaitSettlementReady(execution);
      return execution;
    } catch (Exception exception) {
      jdbcTemplate.update(updateBusinessCalendarSql2, TENANT, calendarCode);
      Files.deleteIfExists(configPath);
      Files.deleteIfExists(readyPath);
      Files.deleteIfExists(releasePath);
      throw exception;
    }
  }

  private void writeSettlementProcessConfig(
      Path path, String applicationName, Path readyPath, Path releasePath) throws Exception {
    Properties properties = new Properties();
    properties.setProperty(
        "spring.datasource.url", platformJdbcUrl() + "&ApplicationName=" + applicationName);
    properties.setProperty("spring.datasource.username", platformJdbcUsername());
    properties.setProperty("spring.datasource.password", platformJdbcPassword());
    properties.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
    properties.setProperty("spring.datasource.hikari.maximum-pool-size", "20");
    properties.setProperty("batch.datasource.business.url", businessJdbcUrl());
    properties.setProperty("batch.datasource.business.username", businessJdbcUsername());
    properties.setProperty("batch.datasource.business.password", businessJdbcPassword());
    properties.setProperty("batch.datasource.business.schema", "biz");
    properties.setProperty("spring.kafka.bootstrap-servers", kafkaBootstrapServers());
    properties.setProperty("batch.storage.backend", "s3");
    properties.setProperty("batch.storage.s3.endpoint", s3Endpoint());
    properties.setProperty("batch.storage.s3.access-key", s3AccessKey());
    properties.setProperty("batch.storage.s3.secret-key", s3SecretKey());
    properties.setProperty("batch.storage.s3.bucket", s3Bucket());
    properties.setProperty("batch.storage.s3.region", s3Region());
    properties.setProperty(
        "batch.storage.s3.path-style-enabled", Boolean.toString(s3PathStyleEnabled()));
    properties.setProperty("spring.data.redis.host", redisHost());
    properties.setProperty("spring.data.redis.port", Integer.toString(redisPort()));
    properties.setProperty("spring.quartz.scheduler-name", applicationName);
    properties.setProperty("spring.quartz.auto-startup", "false");
    properties.setProperty("batch.security.bypass-mode", "true");
    properties.setProperty("batch.security.kms.default-key-ref", "DEFAULT_TEST");
    properties.setProperty("batch.security.kms.keys.DEFAULT_TEST", "AAAAAAAAAAAAAAAAAAAAAA==");
    properties.setProperty("batch.sensor.enabled", "false");
    properties.setProperty("batch.batch-day.settle-scan-interval-millis", "3600000");
    properties.setProperty("batch.batch-day.open-scan-interval-millis", "3600000");
    properties.setProperty("batch.worker.drain.heartbeat-timeout-scheduler-enabled", "false");
    properties.setProperty("batch.partition-lease.reclaim-scheduler-enabled", "false");
    properties.setProperty("spring.flyway.enabled", "true");
    properties.setProperty("spring.flyway.default-schema", "batch");
    properties.setProperty("spring.flyway.schemas", "batch,quartz");
    properties.setProperty("batch.r11.ready-file", readyPath.toString());
    properties.setProperty("batch.r11.release-file", releasePath.toString());
    try (OutputStream output = Files.newOutputStream(path)) {
      properties.store(output, "independent R11 settlement Orchestrator JVM");
    }
  }

  private void awaitSettlementReady(SettlementExecution execution) throws Exception {
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          assertThat(execution.childProcess().isAlive())
              .as(
                  "independent Orchestrator remains alive during startup; log: %s",
                  Files.readString(execution.logPath()))
              .isTrue();
          assertThat(Files.exists(execution.readyPath())).isTrue();
        });
  }

  private void releaseSettlement(SettlementExecution execution) throws Exception {
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set enabled = true where tenant_id = ? and calendar_code = ?\
        """;

    if (execution.mode() != SettlementExecutionMode.INDEPENDENT_ORCHESTRATOR_JVM) {
      return;
    }
    int affectedBusinessCalendarRows =
        jdbcTemplate.update(updateBusinessCalendarSql, TENANT, execution.calendarCode());
    assertThat(affectedBusinessCalendarRows).isOne();
    Files.createFile(execution.releasePath());
  }

  private int settlementBackendPid(SettlementExecution execution, int blockerPid) {
    String applicationName = execution.applicationName();
    final String selectPidSql =
        """
        select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid)) and wait_event_type = 'Lock'\s\
        and lower(query) like '%%update batch.batch_day_instance%%'%s\
        """.formatted((applicationName == null ? " limit 1" : " and application_name = ? limit 1"));
    Integer pid = jdbcTemplate.queryForObject(
        selectPidSql,
        Integer.class,
        applicationName == null
            ? new Object[] {blockerPid}
            : new Object[] {blockerPid, applicationName});
    assertThat(pid).isNotNull();
    return pid;
  }

  private void awaitSettlementBlocked(
      SettlementExecution execution, String sqlFragment, Integer blockerPid) throws Exception {
    final String selectPidSql = """
        select pid, application_name, state, wait_event_type, wait_event, query from pg_stat_activity\s\
        where application_name like 'r11-settle-%%' order by application_name, pid\
        """;

    if (execution.mode() == SettlementExecutionMode.SAME_JVM) {
      if (blockerPid == null) {
        awaitPlatformBlockedSql(sqlFragment);
      } else {
        awaitBlockedBySql(blockerPid, sqlFragment);
      }
      return;
    }
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          final String selectCountSql =
              """
              select count(*) from pg_stat_activity where application_name = ? and wait_event_type = 'Lock'\s\
              and lower(query) like '%%' || lower(?) || '%%'%s\
              """.formatted((blockerPid == null ? "" : " and ? = any(pg_blocking_pids(pid))"));
          Integer blocked = jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              blockerPid == null
                  ? new Object[] {execution.applicationName(), sqlFragment}
                  : new Object[] {execution.applicationName(), sqlFragment, blockerPid});
          List<Map<String, Object>> activity = jdbcTemplate.queryForList(selectPidSql);
          assertThat(blocked)
              .as(
                  "settlement process should wait on %s; PostgreSQL activity: %s",
                  sqlFragment, activity)
              .isPositive();
        });
  }

  private void awaitSettlementCompletion(SettlementExecution execution) throws Exception {
    if (execution.mode() == SettlementExecutionMode.SAME_JVM) {
      execution.localFuture().get(30, TimeUnit.SECONDS);
      return;
    }
    Process child = execution.childProcess();
    assertThat(child.waitFor(90, TimeUnit.SECONDS))
        .as("independent Orchestrator completes; log: %s", Files.readString(execution.logPath()))
        .isTrue();
    assertThat(child.exitValue())
        .as(
            "independent Orchestrator exits successfully; log: %s",
            Files.readString(execution.logPath()))
        .isZero();
  }

  private void cleanupSettlementExecutions(List<SettlementExecution> executions) throws Exception {
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set enabled = true where tenant_id = ? and calendar_code = ?\
        """;

    for (SettlementExecution execution : executions) {
      if (execution.childProcess() != null && execution.childProcess().isAlive()) {
        execution.childProcess().destroyForcibly();
        assertThat(execution.childProcess().waitFor(10, TimeUnit.SECONDS))
            .as("independent settlement process is reaped")
            .isTrue();
      }
      if (execution.configPath() != null) {
        Files.deleteIfExists(execution.configPath());
      }
      if (execution.calendarCode() != null) {
        jdbcTemplate.update(updateBusinessCalendarSql, TENANT, execution.calendarCode());
      }
      if (execution.readyPath() != null) {
        Files.deleteIfExists(execution.readyPath());
      }
      if (execution.releasePath() != null) {
        Files.deleteIfExists(execution.releasePath());
      }
    }
    executions.clear();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SettlementExecutionMode.class)
  @DisplayName("真实外部事件 API 接纳与同 JVM/独立 Orchestrator 结算按业务日围栏串行化")
  void externalEventApiAdmissionRacingBatchDaySettlementShouldConverge(
      SettlementExecutionMode settlementMode) throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectDayStatusSql = """
            select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ? for update\
            """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
            enabled) values (?, ?, 'E2E event admission fence calendar', 'UTC', '23:59:00', true)\
            """;
    final String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type,\s\
            version, enabled, calendar_code, trigger_mode) values (?, ?, ?, 'DAG', 1, true, ?, 'EVENT')\s\
            returning id\
            """;
    final String insertExternalEventExpectationDefinitionSql = """
            insert into batch.external_event_expectation_definition (tenant_id, calendar_code,\s\
            workflow_definition_id, event_code, expected_local_time, enabled) values (?, ?, ?, ?, '09:30', true)\
            """;
    final String insertApiKeySql = """
            insert into batch.api_key (tenant_id, key_name, key_prefix, key_hash, salt, key_hash_algo, scopes,\s\
            enabled) values (?, ?, ?, ?, ?, ?, ?, true) returning id\
            """;
    final String insertExpectedWorkOccurrenceSql = """
            insert into batch.expected_work_occurrence (tenant_id, calendar_code, biz_date,\s\
            workflow_definition_id, source_type, source_key, occurrence_status)\s\
            values (?, ?, ?, ?, 'EXTERNAL_EVENT', ?, 'EXPECTED')\
            """;
    final String selectCountSql = """
            select count(*) from batch.external_event_delivery where tenant_id = ? and event_source = ?\s\
            and event_id = ? and biz_date = ?\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.expected_work_occurrence where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ? and workflow_definition_id = ? and source_type = 'EXTERNAL_EVENT'\s\
            and source_key = ? and occurrence_status in ('EXPECTED', 'ARRIVED')\
            """;
    final String insertExpectedWorkOccurrenceSql2 = """
            insert into batch.expected_work_occurrence (tenant_id, calendar_code, biz_date,\s\
            workflow_definition_id, source_type, source_key, occurrence_status)\s\
            values (?, ?, ?, ?, 'EXTERNAL_EVENT', ?, 'CANCELLED')\
            """;
    final String selectCountSql3 = """
            select count(*) from batch.external_event_delivery where tenant_id = ? and event_source = ?\s\
            and event_id = ?\
            """;
    final String deleteApiKeySql = """
        delete from batch.api_key where id = ?
    """;

    startTriggerHttpServer();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "e2e_event_fence_" + suffix.substring(0, 12);
    String eventCode = "E2E_EVENT_FENCE_" + suffix;
    String source = "billing-fence-" + suffix;
    String rawApiKey = "bk_" + suffix;
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        TENANT,
        "event-fence-" + suffix,
        "Event fence " + suffix,
        calendarCode);
    jdbcTemplate.update(
        insertExternalEventExpectationDefinitionSql,
        TENANT,
        calendarCode,
        workflowDefinitionId,
        eventCode);
    ApiKeyHasher.SaltedHash hash = ApiKeyHasher.hashWithSaltKdf(rawApiKey);
    Long apiKeyId = jdbcTemplate.queryForObject(
        insertApiKeySql,
        Long.class,
        TENANT,
        source,
        rawApiKey.substring(0, 8),
        hash.hash(),
        hash.salt(),
        ApiKeyHasher.ALGO_PBKDF2,
        "trigger.event");

    List<SettlementExecution> settlementExecutions = new ArrayList<>();
    try {
      LocalDate admissionFirstDate = r11BizDate(3);
      insertCutoffBatchDay(calendarCode, admissionFirstDate);
      jdbcTemplate.update(
          insertExpectedWorkOccurrenceSql,
          TENANT,
          calendarCode,
          admissionFirstDate,
          workflowDefinitionId,
          eventCode);
      ExecutorService admissionFirstExecutor = Executors.newFixedThreadPool(2);
      try (var fence = jdbcTemplate.getDataSource().getConnection()) {
        fence.setAutoCommit(false);
        try (var acquire = fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
          acquire.setString(1, TENANT);
          acquire.setString(2, calendarCode);
          acquire.setObject(3, admissionFirstDate);
          try (var result = acquire.executeQuery()) {
            assertThat(result.next()).isTrue();
          }
        }
        int fencePid = connectionPid(fence);
        String admissionEventId = "event-admission-first-" + suffix;
        String admissionKey = "event-admission-first-key-" + suffix;
        String admissionBody = externalEventBody(eventCode, admissionEventId, admissionFirstDate);
        Future<HttpResponse<String>> admission = admissionFirstExecutor.submit(
            () -> postTriggerEventResponse(admissionBody, rawApiKey, admissionKey));
        awaitBlockedBySql(fencePid, "batch.acquire_batch_day_settlement_fence");
        SettlementExecution settlement = startSettlement(
            settlementMode,
            admissionFirstExecutor,
            "event-admission-first-" + suffix,
            calendarCode,
            settlementExecutions);
        releaseSettlement(settlement);
        awaitSettlementBlocked(settlement, "batch.acquire_batch_day_settlement_fence", null);
        awaitBlockedBySqlCount(fencePid, "batch.acquire_batch_day_settlement_fence", 2);
        fence.commit();

        HttpResponse<String> response = admission.get(20, TimeUnit.SECONDS);
        assertThat(response.statusCode())
            .as("external event admission failed: %s", response.body())
            .isBetween(200, 299);
        awaitSettlementCompletion(settlement);
        Integer selectedExternalEventDeliveryCount = jdbcTemplate.queryForObject(
            selectCountSql, Integer.class, TENANT, source, admissionEventId, admissionFirstDate);
        assertThat(selectedExternalEventDeliveryCount).isOne();
        Integer selectedExpectedWorkOccurrenceCount = jdbcTemplate.queryForObject(
            selectCountSql2,
            Integer.class,
            TENANT,
            calendarCode,
            admissionFirstDate,
            workflowDefinitionId,
            eventCode);
        assertThat(selectedExpectedWorkOccurrenceCount).isOne();
        assertThat(batchDayStatus(calendarCode, admissionFirstDate)).isNotEqualTo("SETTLED");
      } finally {
        admissionFirstExecutor.shutdownNow();
        assertThat(admissionFirstExecutor.awaitTermination(10, TimeUnit.SECONDS))
            .isTrue();
      }

      LocalDate settlementFirstDate = r11BizDate(4);
      insertCutoffBatchDay(calendarCode, settlementFirstDate);
      jdbcTemplate.update(
          insertExpectedWorkOccurrenceSql2,
          TENANT,
          calendarCode,
          settlementFirstDate,
          workflowDefinitionId,
          eventCode);
      ExecutorService settlementFirstExecutor = Executors.newFixedThreadPool(2);
      try (var batchDayLock = jdbcTemplate.getDataSource().getConnection()) {
        batchDayLock.setAutoCommit(false);
        try (var lock = batchDayLock.prepareStatement(selectDayStatusSql)) {
          lock.setString(1, TENANT);
          lock.setString(2, calendarCode);
          lock.setObject(3, settlementFirstDate);
          try (var result = lock.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("CUTOFF");
          }
        }
        int batchDayLockPid = connectionPid(batchDayLock);
        SettlementExecution settlement = startSettlement(
            settlementMode,
            settlementFirstExecutor,
            "event-settlement-first-" + suffix,
            calendarCode,
            settlementExecutions);
        releaseSettlement(settlement);
        awaitSettlementBlocked(settlement, "update batch.batch_day_instance", batchDayLockPid);
        int settlementPid = settlementBackendPid(settlement, batchDayLockPid);

        String lateEventId = "event-settlement-first-" + suffix;
        Future<HttpResponse<String>> lateAdmission =
            settlementFirstExecutor.submit(() -> postTriggerEventResponse(
                externalEventBody(eventCode, lateEventId, settlementFirstDate),
                rawApiKey,
                "event-settlement-first-key-" + suffix));
        awaitBlockedBySql(settlementPid, "batch.acquire_batch_day_settlement_fence");
        batchDayLock.commit();

        awaitSettlementCompletion(settlement);
        HttpResponse<String> response = lateAdmission.get(20, TimeUnit.SECONDS);
        assertThat(batchDayStatus(calendarCode, settlementFirstDate)).isEqualTo("SETTLED");
        assertThat(response.statusCode())
            .as("late external event admission response: %s", response.body())
            .isEqualTo(409);
        Integer selectedExternalEventDeliveryCountRead2 = jdbcTemplate.queryForObject(
            selectCountSql3, Integer.class, TENANT, source, lateEventId);
        assertThat(selectedExternalEventDeliveryCountRead2).isZero();
      } finally {
        settlementFirstExecutor.shutdownNow();
        assertThat(settlementFirstExecutor.awaitTermination(10, TimeUnit.SECONDS))
            .isTrue();
      }
    } finally {
      cleanupSettlementExecutions(settlementExecutions);
      jdbcTemplate.update(deleteApiKeySql, apiKeyId);
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ScheduledAdmissionEntry.class)
  @DisplayName("计划触发先接纳时结算等待同一业务日围栏并保留 occurrence")
  void scheduledAdmissionBeforeSettlement_keepsOccurrenceBlocking(ScheduledAdmissionEntry entry)
      throws Exception {
    final String selectIdSql = """
            select id from batch.workflow_definition where tenant_id = ? and id = ? for update
        """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
            enabled) values (?, ?, 'E2E scheduled admission calendar', 'UTC', '00:00:00', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition\s\
            set calendar_code = ?, schedule_type = 'CRON', schedule_expr = '0 0 12 * * ?', timezone = 'UTC',\s\
            trigger_mode = 'SCHEDULED', schedule_enabled = true where tenant_id = ? and id = ?\
            """;
    final String updateJobDefinitionSql = """
            update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?
        """;
    final String deleteTriggerRequestSql = """
            delete from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectRequestIdSql = """
            select request_id from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time = ?\
            """;
    final String selectPublishStatusSql = """
            select publish_status from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\
            """;
    final String selectCountSql = """
            select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectCountSql2 = """
            select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?
        """;

    startTriggerHttpServer();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "scheduled-admission-" + suffix, TriggerType.API);
    String calendarCode = "e2e_sched_admit_" + suffix.substring(0, 12);
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, seed.jobCode());
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, seed.requestId());

    LocalDate bizDate = BIZ_DATE.plusDays(10);
    Instant fireTime = bizDate.atTime(12, 0).toInstant(ZoneOffset.UTC);
    insertCutoffBatchDay(calendarCode, bizDate);
    insertPlannedOccurrence(seed, calendarCode, bizDate, fireTime);
    String serviceRequestId = "scheduled-admitted-" + suffix;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (var workflowLock = jdbcTemplate.getDataSource().getConnection()) {
      workflowLock.setAutoCommit(false);
      try (var lock = workflowLock.prepareStatement(selectIdSql)) {
        lock.setString(1, TENANT);
        lock.setLong(2, seed.workflowDefinitionId());
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      int workflowLockPid = connectionPid(workflowLock);
      Future<?> admission;
      if (entry == ScheduledAdmissionEntry.TRIGGER_SERVICE) {
        TriggerService triggerService = triggerContext.getBean(TriggerService.class);
        TriggerDescriptor descriptor = triggerContext
            .getBean(TriggerDefinitionLoader.class)
            .loadByWorkflowDefinitionId(TENANT, seed.workflowDefinitionId());
        admission =
            executor.submit(() -> triggerService.launchScheduled(new ScheduledTriggerCommand(
                descriptor,
                fireTime,
                TriggerType.SCHEDULED,
                serviceRequestId,
                "trace-" + serviceRequestId)));
      } else {
        admission = executor.submit(() -> {
          fireScheduledOccurrenceThroughQuartz(
              seed, fireTime, "scheduled-admitted-" + suffix, calendarCode, "UTC", "0 0 12 * * ?");
          return null;
        });
      }
      awaitBlockedBySql(workflowLockPid, "from batch.workflow_definition wf");
      Future<?> settlement = executor.submit(batchDaySettleScheduler::settle);
      awaitPlatformBlockedSql("batch.acquire_batch_day_settlement_fence");
      workflowLock.commit();

      admission.get(20, TimeUnit.SECONDS);
      settlement.get(20, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    String requestId = serviceRequestId;
    if (entry == ScheduledAdmissionEntry.QUARTZ_CALLBACK) {
      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(
              () -> assertThat(scheduledOccurrenceStatus(seed.workflowDefinitionId(), fireTime))
                  .isEqualTo("REQUESTED"));
      requestId = jdbcTemplate.queryForObject(
          selectRequestIdSql,
          String.class,
          TENANT,
          seed.workflowDefinitionId(),
          Timestamp.from(fireTime));
      assertThat(requestId).isNotBlank();
    }
    String acceptedRequestId = requestId;
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
      String selectedPublishStatus = jdbcTemplate.queryForObject(
          selectPublishStatusSql, String.class, TENANT, acceptedRequestId);
      assertThat(selectedPublishStatus).isEqualTo("PUBLISHED");
    });
    assertThat(scheduledOccurrenceStatus(seed.workflowDefinitionId(), fireTime))
        .isEqualTo("REQUESTED");
    assertThat(batchDayStatus(calendarCode, bizDate)).isNotEqualTo("SETTLED");
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, acceptedRequestId);
    assertThat(selectedTriggerRequestCount).isOne();
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, acceptedRequestId);
    assertThat(selectedTriggerOutboxEventCount).isOne();
  }

  @Test
  @DisplayName("结算持有围栏时真实 Quartz callback 不复活已跳过的 occurrence")
  void settlementFirstScheduledQuartzCallback_doesNotReviveSkippedOccurrence() throws Exception {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ? for update\
        """;

    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        enabled) values (?, ?, 'E2E settlement-first schedule calendar', 'UTC', '00:00:00', true)\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition\s\
        set calendar_code = ?, schedule_type = 'CRON', schedule_expr = '0 0 12 * * ?', timezone = 'UTC',\s\
        trigger_mode = 'SCHEDULED', schedule_enabled = true where tenant_id = ? and id = ?\
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'MISFIRE_SKIPPED' where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String insertScheduledOccurrenceCoverageSql = """
        insert into batch.scheduled_occurrence_coverage (tenant_id, workflow_definition_id,\s\
        configuration_revision, schedule_version, calendar_code, coverage_from_at, coverage_through_at,\s\
        last_success_at) values (?, ?, batch.current_tenant_configuration_revision(?), 1, ?, current_timestamp -\s\
        interval '1 year', current_timestamp, current_timestamp)\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ?\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.trigger_outbox_event event join batch.trigger_request request\s\
        using (tenant_id, request_id) where request.tenant_id = ? and request.workflow_definition_id = ?\s\
        and request.biz_date = ?\
        """;

    startTriggerHttpServer();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "settlement-first-scheduled-" + suffix, TriggerType.API);
    String calendarCode = "e2e_sched_settle_first_" + suffix.substring(0, 12);
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, seed.jobCode());
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, seed.requestId());

    LocalDate bizDate = BIZ_DATE.plusDays(11);
    Instant fireTime = bizDate.atTime(12, 0).toInstant(ZoneOffset.UTC);
    insertCutoffBatchDay(calendarCode, bizDate);
    insertPlannedOccurrence(seed, calendarCode, bizDate, fireTime);
    jdbcTemplate.update(
        updateScheduledOccurrenceSql,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(fireTime));
    jdbcTemplate.update(
        insertScheduledOccurrenceCoverageSql,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        calendarCode);
    String callbackIdentity = "settlement-first-scheduled-" + suffix;
    CompletableFuture<Void> callbackCompleted = new CompletableFuture<>();
    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    JobListenerSupport listener = new JobListenerSupport() {
      @Override
      public String getName() {
        return callbackIdentity;
      }

      @Override
      public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        if (jobException == null) {
          callbackCompleted.complete(null);
        } else {
          callbackCompleted.completeExceptionally(jobException);
        }
      }
    };
    scheduler
        .getListenerManager()
        .addJobListener(
            listener, KeyMatcher.keyEquals(JobKey.jobKey(callbackIdentity, "workflow-daily-e2e")));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (var batchDayLock = jdbcTemplate.getDataSource().getConnection()) {
      batchDayLock.setAutoCommit(false);
      try (var lock = batchDayLock.prepareStatement(selectDayStatusSql)) {
        lock.setString(1, TENANT);
        lock.setString(2, calendarCode);
        lock.setObject(3, bizDate);
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString(1)).isEqualTo("CUTOFF");
        }
      }
      int batchDayLockPid = connectionPid(batchDayLock);
      Future<?> settlement = executor.submit(batchDaySettleScheduler::settle);
      awaitBlockedBySql(batchDayLockPid, "update batch.batch_day_instance");
      fireScheduledOccurrenceThroughQuartz(
          seed, fireTime, callbackIdentity, calendarCode, "UTC", "0 0 12 * * ?");
      callbackCompleted.get(20, TimeUnit.SECONDS);
      batchDayLock.commit();

      settlement.get(20, TimeUnit.SECONDS);
    } finally {
      scheduler.getListenerManager().removeJobListener(listener.getName());
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> assertThat(scheduledOccurrenceStatus(seed.workflowDefinitionId(), fireTime))
                .isEqualTo("MISFIRE_SKIPPED"));
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("SETTLED");
    String selectedRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql,
        String.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(fireTime));
    assertThat(selectedRequestId).isNull();
    Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId(), bizDate);
    assertThat(selectedTriggerRequestCount).isZero();
    Integer selectedTriggerOutboxEventCount = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, TENANT, seed.workflowDefinitionId(), bizDate);
    assertThat(selectedTriggerOutboxEventCount).isZero();
  }

  private enum ScheduledAdmissionEntry {
    TRIGGER_SERVICE,
    QUARTZ_CALLBACK
  }

  @Test
  @DisplayName("计划覆盖发布遇到配置版本变化时回滚,结算等待新版本覆盖")
  void shouldConvergeCoverage_whenConfigurationAndSettlementRace() throws Exception {
    final String selectScheduledFireTimeSql = """
            select scheduled_fire_time from batch.scheduled_occurrence where tenant_id = ?\s\
            and workflow_definition_id = ? and scheduled_fire_time = ? for update\
            """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, 'E2E coverage revision calendar', 'UTC', true)\s\
            on conflict (tenant_id, calendar_code) do update set enabled = true\
            """;
    final String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type,\s\
            version, enabled, schedule_type, schedule_expr, timezone, trigger_mode, calendar_code,\s\
            schedule_enabled) values (?, ?, 'E2E coverage revision race workflow', 'DAG', 1, true, 'CRON',\s\
            '0 0 9 * * ?', 'UTC', 'SCHEDULED', ?, true) returning id\
            """;
    final String selectCurrentTenantConfigurationRevisionSql = """
            select batch.current_tenant_configuration_revision(?)
        """;
    final String insertBatchDayInstanceSql = """
            insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
            cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, ?)\s\
            on conflict (tenant_id, calendar_code, biz_date) do update\s\
            set day_status = 'CUTOFF', cutoff_at = excluded.cutoff_at\
            """;
    final String insertScheduledOccurrenceCoverageSql = """
            insert into batch.scheduled_occurrence_coverage (tenant_id, workflow_definition_id,\s\
            configuration_revision, schedule_version, calendar_code, coverage_from_at, coverage_through_at,\s\
            last_success_at) values (?, ?, ?, 1, ?, ?, ?, current_timestamp)\
            """;
    final String insertScheduledOccurrenceSql = """
            insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
            schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
            values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition\s\
            set version = 2, schedule_expr = '0 30 9 * * ?', updated_at = current_timestamp where tenant_id = ?\s\
            and id = ?\
            """;
    final String selectCountSql = """
            select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and biz_date = ?\
            """;
    final String selectOccurrenceStatusSql = """
            select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
            and workflow_definition_id = ? and scheduled_fire_time = ?\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and schedule_version = 2 and biz_date = ? and occurrence_status = 'PLANNED'\
            """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'READINESS_TIMEOUT' where tenant_id = ?\s\
        and workflow_definition_id = ? and schedule_version = 2 and biz_date = ?\s\
        and occurrence_status = 'PLANNED'\
        """;

    startTriggerHttpServer();
    String workflowCode =
        "E2E_COVERAGE_REVISION_RACE_" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "e2e_cov_rev_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, calendarCode);
    long initialRevision = jdbcTemplate.queryForObject(
        selectCurrentTenantConfigurationRevisionSql, Long.class, TENANT);
    Instant scanAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    CronTrigger initialTrigger = TriggerBuilder.newTrigger()
        .withIdentity(workflowCode, "coverage-revision-race-v1")
        .startAt(Date.from(scanAt))
        .withSchedule(
            CronScheduleBuilder.cronSchedule("0 0 9 * * ?").inTimeZone(TimeZone.getTimeZone("UTC")))
        .build();
    Instant firstFire = initialTrigger.getFireTimeAfter(Date.from(scanAt)).toInstant();
    Instant nextFire = initialTrigger.getFireTimeAfter(Date.from(firstFire)).toInstant();
    // 使用首日之后的业务日：首批 coverage 上限（~10 万条）可能刚好覆盖完整首日，导致
    // settle 直接 SETTLED 而非 CUTOFF（CI 上偶发）；第二天 coverage 必然不完整，断言确定。
    LocalDate bizDate = firstFire.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        TENANT,
        calendarCode,
        bizDate,
        Timestamp.from(firstFire.plusSeconds(3600)));
    jdbcTemplate.update(
        insertScheduledOccurrenceCoverageSql,
        TENANT,
        workflowDefinitionId,
        initialRevision,
        calendarCode,
        Timestamp.from(scanAt.minusMillis(1)),
        Timestamp.from(firstFire.minusMillis(1)));
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(nextFire),
        nextFire.atZone(ZoneOffset.UTC).toLocalDate());

    TriggerDescriptor staleDescriptor = new TriggerDescriptor();
    staleDescriptor.setTenantId(TENANT);
    staleDescriptor.setWorkflowDefinitionId(workflowDefinitionId);
    staleDescriptor.setWorkflowCode(workflowCode);
    staleDescriptor.setWorkflowVersion(1);
    staleDescriptor.setScheduleType("CRON");
    staleDescriptor.setScheduleExpression("0 0 9 * * ?");
    staleDescriptor.setTimezone("UTC");
    staleDescriptor.setTriggerMode("SCHEDULED");
    staleDescriptor.setCalendarCode(calendarCode);
    staleDescriptor.setEnabled(true);
    staleDescriptor.setWorkflowEnabled(true);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (var occurrenceLock = jdbcTemplate.getDataSource().getConnection()) {
      occurrenceLock.setAutoCommit(false);
      try (var lock = occurrenceLock.prepareStatement(selectScheduledFireTimeSql)) {
        lock.setString(1, TENANT);
        lock.setLong(2, workflowDefinitionId);
        lock.setTimestamp(3, Timestamp.from(nextFire));
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      int blockerPid;
      try (var pid = occurrenceLock.prepareStatement(selectPgBackendPidSql)) {
        try (var result = pid.executeQuery()) {
          assertThat(result.next()).isTrue();
          blockerPid = result.getInt(1);
        }
      }

      TriggerScheduledOccurrencePlanner planner =
          triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
      var initialFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(initialTrigger);
      Future<Integer> stalePublication =
          executor.submit(() -> planner.materialize(staleDescriptor, initialFireTimes, scanAt));
      awaitBlockedBySql(blockerPid, "insert into batch.scheduled_occurrence");

      jdbcTemplate.update(updateWorkflowDefinitionSql, TENANT, workflowDefinitionId);
      Long selectedCurrentTenantConfigurationRevision = jdbcTemplate.queryForObject(
          selectCurrentTenantConfigurationRevisionSql, Long.class, TENANT);
      assertThat(selectedCurrentTenantConfigurationRevision).isGreaterThan(initialRevision);
      batchDaySettleScheduler.settle();
      assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");

      occurrenceLock.commit();
      assertThatThrownBy(() -> stalePublication.get(20, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasMessageContaining(
              "Tenant configuration changed during scheduled occurrence materialization");
    } finally {
      executor.shutdownNow();
    }

    Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, workflowDefinitionId, bizDate);
    assertThat(selectedScheduledOccurrenceCount).isZero();
    String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(nextFire));
    assertThat(selectedOccurrenceStatus).isEqualTo("PLANNED");
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");

    Instant currentScanAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    CronTrigger currentTrigger = TriggerBuilder.newTrigger()
        .withIdentity(workflowCode, "coverage-revision-race-v2")
        .startAt(Date.from(currentScanAt))
        .withSchedule(CronScheduleBuilder.cronSchedule("0 30 9 * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC")))
        .build();
    TriggerDescriptor currentDescriptor = new TriggerDescriptor();
    currentDescriptor.setTenantId(TENANT);
    currentDescriptor.setWorkflowDefinitionId(workflowDefinitionId);
    currentDescriptor.setWorkflowCode(workflowCode);
    currentDescriptor.setWorkflowVersion(2);
    currentDescriptor.setScheduleType("CRON");
    currentDescriptor.setScheduleExpression("0 30 9 * * ?");
    currentDescriptor.setTimezone("UTC");
    currentDescriptor.setTriggerMode("SCHEDULED");
    currentDescriptor.setCalendarCode(calendarCode);
    currentDescriptor.setEnabled(true);
    currentDescriptor.setWorkflowEnabled(true);
    var currentFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(currentTrigger);
    assertThat(triggerContext
            .getBean(TriggerScheduledOccurrencePlanner.class)
            .materialize(currentDescriptor, currentFireTimes, currentScanAt))
        .isPositive();
    Integer selectedScheduledOccurrenceCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, TENANT, workflowDefinitionId, bizDate);
    assertThat(selectedScheduledOccurrenceCountRead2).isEqualTo(1);
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateScheduledOccurrenceSql, TENANT, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("DST cutoff fire 按 planner 持久化业务日阻塞并释放生产结算")
  void shouldBlockPersistedBusinessDay_whenDstCutoffOccurrencePending() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        enabled) values (?, ?, 'E2E DST calendar', 'America/New_York', '02:30:00', true)\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled)\s\
        values (?, ?, 'E2E DST settlement workflow', 'DAG', 1, true, 'CRON', '0 0 9 * * ?', 'America/New_York',\s\
        'SCHEDULED', ?, false) returning id\
        """;
    final String updateTenantConfigurationRevisionSql = """
        update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
    """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, ?)\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        order by scheduled_fire_time\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'MISFIRE_SKIPPED' where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String updateScheduledOccurrenceSql2 = """
        update batch.scheduled_occurrence set occurrence_status = 'MISFIRE_SKIPPED' where tenant_id = ?\s\
        and workflow_definition_id = ? and biz_date = ?\
        """;

    startTriggerHttpServer();
    String calendarCode = "dst_settlement_" + UUID.randomUUID().toString().replace("-", "");
    String workflowCode = "E2E_DST_SETTLEMENT_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, calendarCode);

    Instant scanAt = Instant.parse("2026-03-08T06:59:58Z");
    Instant firstFire = Instant.parse("2026-03-08T06:59:59Z");
    LocalDate beforeCutoffBizDate = LocalDate.of(2026, 3, 7);
    LocalDate afterGapBizDate = LocalDate.of(2026, 3, 8);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql,
        Timestamp.from(scanAt.minus(Duration.ofDays(1))),
        TENANT);
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        TENANT,
        calendarCode,
        beforeCutoffBizDate,
        Timestamp.from(scanAt));
    jdbcTemplate.update(
        insertBatchDayInstanceSql, TENANT, calendarCode, afterGapBizDate, Timestamp.from(scanAt));
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(workflowCode, "dst-settlement")
        .startAt(Date.from(firstFire))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).withRepeatCount(2))
        .build();
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(TENANT);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 9 * * ?");
    descriptor.setTimezone("America/New_York");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCalendarCode(calendarCode);
    descriptor.setEnabled(true);
    descriptor.setWorkflowEnabled(true);

    TriggerScheduledOccurrencePlanner planner =
        triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
    var plannedFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(trigger);
    assertThat(planner.materialize(descriptor, plannedFireTimes, scanAt)).isEqualTo(3);
    List<LocalDate> expectedBizDates =
        List.of(beforeCutoffBizDate, afterGapBizDate, afterGapBizDate);
    List<LocalDate> selectedBizDateValues =
        jdbcTemplate.queryForList(selectBizDateSql, LocalDate.class, TENANT, workflowDefinitionId);
    assertThat(selectedBizDateValues).containsExactlyElementsOf(expectedBizDates);
    assertThat(scheduledOccurrenceStatus(workflowDefinitionId, firstFire)).isEqualTo("PLANNED");

    long occurrenceCountBeforeRestart = scheduledOccurrenceCount(workflowDefinitionId);
    Instant coverageThroughBeforeRestart = scheduledOccurrenceCoverageThrough(workflowDefinitionId);
    restartTriggerHttpServer();
    assertThat(scheduledOccurrenceCount(workflowDefinitionId))
        .isEqualTo(occurrenceCountBeforeRestart);
    assertThat(scheduledOccurrenceCoverageThrough(workflowDefinitionId))
        .isEqualTo(coverageThroughBeforeRestart);
    List<LocalDate> selectedBizDateValuesRead2 =
        jdbcTemplate.queryForList(selectBizDateSql, LocalDate.class, TENANT, workflowDefinitionId);
    assertThat(selectedBizDateValuesRead2).containsExactlyElementsOf(expectedBizDates);
    assertThat(triggerContext
            .getBean(TriggerScheduledOccurrencePlanner.class)
            .materialize(descriptor, plannedFireTimes, scanAt))
        .isZero();
    assertThat(scheduledOccurrenceCount(workflowDefinitionId))
        .isEqualTo(occurrenceCountBeforeRestart);

    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, beforeCutoffBizDate)).isEqualTo("CUTOFF");
    assertThat(batchDayStatus(calendarCode, afterGapBizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(
        updateScheduledOccurrenceSql, TENANT, workflowDefinitionId, Timestamp.from(firstFire));
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, beforeCutoffBizDate)).isEqualTo("SETTLED");
    assertThat(batchDayStatus(calendarCode, afterGapBizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(
        updateScheduledOccurrenceSql2, TENANT, workflowDefinitionId, afterGapBizDate);
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, afterGapBizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("开日后变更日历并暂停恢复日程时保留已开日快照与既有 occurrence")
  void shouldPreserveSnapshotsAndObligations_whenDefinitionsChangeAfterOpen() throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do update set status = 'ACTIVE'\
        """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        enabled) values (?, ?, 'R06 calendar edit', 'UTC', '00:00:00', true)\
        """;
    final String selectTimezoneSnapshotSql = """
        select timezone_snapshot, cutoff_at, sla_deadline_at, dst_policy_snapshot from batch.batch_day_instance\s\
        where tenant_id = ? and calendar_code = ? and biz_date = ? and day_status = 'OPEN'\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled)\s\
        values (?, ?, 'R06 scheduled workflow', 'DAG', 1, true, 'CRON', '0 0 * * * ?', 'UTC', 'SCHEDULED', ?,\s\
        true) returning id\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;
    final String insertExistingRequestSql = """
        insert into batch.trigger_request
            (tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key, request_status)
        values (?, ?, 'SCHEDULED', ?, ?, ?, 'ACCEPTED')
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status, request_id)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set timezone = 'Pacific/Honolulu', cutoff_time = '23:59:59'\s\
        where tenant_id = ? and calendar_code = ?\
        """;
    final String updateTenantConfigurationRevisionSql = """
        update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set version = 2, schedule_expr = '0 15 4 * * ?' where tenant_id = ?\s\
        and id = ?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectTimezoneSnapshotSql2 = """
        select timezone_snapshot, cutoff_at, sla_deadline_at, dst_policy_snapshot from batch.batch_day_instance\s\
        where tenant_id = ? and calendar_code = ? and biz_date = ?\
        """;
    final String updateWorkflowDefinitionSql2 = """
        update batch.workflow_definition set schedule_enabled = false where tenant_id = ? and id = ?\
        """;
    final String updateWorkflowDefinitionSql3 = """
        update batch.workflow_definition set schedule_enabled = true where tenant_id = ? and id = ?\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ? and occurrence_status = 'REQUESTED'\
        """;

    startTriggerHttpServer();
    jdbcTemplate.update(insertTenantSql, TENANT, "R06 calendar edit E2E");
    String calendarCode = "r06_calendar_" + UUID.randomUUID().toString().replace("-", "");
    String workflowCode = "R06_SCHEDULE_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    Instant openedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    LocalDate openedBizDate = openedAt.atZone(ZoneOffset.UTC).toLocalDate();
    batchDayOpenScheduler.openDueBatchDays(openedAt);
    Map<String, Object> openedDaySnapshot =
        jdbcTemplate.queryForMap(selectTimezoneSnapshotSql, TENANT, calendarCode, openedBizDate);
    assertThat(openedDaySnapshot.get("timezone_snapshot")).isEqualTo("UTC");

    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, calendarCode);
    TriggerReconciler triggerReconciler =
        AopTestUtils.getTargetObject(triggerContext.getBean(TriggerReconciler.class));
    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    JobKey jobKey = JobKey.jobKey(TENANT + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP);
    triggerReconciler.reconcile();
    CronTrigger initialTrigger = (CronTrigger) scheduler.getTriggersOfJob(jobKey).stream()
        .filter(CronTrigger.class::isInstance)
        .findFirst()
        .orElseThrow();
    Instant firstObservedFire =
        initialTrigger.getFireTimeAfter(Date.from(openedAt)).toInstant();
    LocalDate initialBizDate = jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(firstObservedFire));
    String existingRequestId = "r06-existing-request-" + UUID.randomUUID();
    jdbcTemplate.update(
        insertExistingRequestSql,
        TENANT,
        existingRequestId,
        workflowDefinitionId,
        openedBizDate,
        existingRequestId);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(openedAt.minusSeconds(60)),
        openedBizDate,
        existingRequestId);

    Instant calendarEffectiveAt = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    jdbcTemplate.update(updateBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql, Timestamp.from(calendarEffectiveAt), TENANT);
    jdbcTemplate.update(updateWorkflowDefinitionSql, TENANT, workflowDefinitionId);
    triggerReconciler.reconcile();

    CronTrigger updatedTrigger = (CronTrigger) scheduler.getTriggersOfJob(jobKey).stream()
        .filter(CronTrigger.class::isInstance)
        .findFirst()
        .orElseThrow();
    assertThat(updatedTrigger.getCronExpression()).isEqualTo("0 15 4 * * ?");
    String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(firstObservedFire));
    assertThat(selectedOccurrenceStatus).isEqualTo("SUPERSEDED");
    Instant updatedFire =
        updatedTrigger.getFireTimeAfter(Date.from(calendarEffectiveAt)).toInstant();
    LocalDate updatedBizDate = jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(updatedFire));
    assertThat(updatedBizDate).isNotEqualTo(initialBizDate);
    assertThat(updatedBizDate)
        .isEqualTo(
            updatedFire.atZone(ZoneId.of("Pacific/Honolulu")).toLocalDate().minusDays(1));
    Map<String, Object> selectedTimezoneSnapshotRow =
        jdbcTemplate.queryForMap(selectTimezoneSnapshotSql2, TENANT, calendarCode, openedBizDate);
    assertThat(selectedTimezoneSnapshotRow).isEqualTo(openedDaySnapshot);

    jdbcTemplate.update(updateWorkflowDefinitionSql2, TENANT, workflowDefinitionId);
    triggerReconciler.reconcile();
    assertThat(scheduler.checkExists(jobKey)).isFalse();
    String selectedOccurrenceStatusRead2 = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(updatedFire));
    assertThat(selectedOccurrenceStatusRead2).isEqualTo("SUPERSEDED");

    jdbcTemplate.update(updateWorkflowDefinitionSql3, TENANT, workflowDefinitionId);
    triggerReconciler.reconcile();
    assertThat(scheduler.checkExists(jobKey)).isTrue();
    String selectedOccurrenceStatusRead3 = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(updatedFire));
    assertThat(selectedOccurrenceStatusRead3).isEqualTo("PLANNED");
    String selectedRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql,
        String.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(openedAt.minusSeconds(60)));
    assertThat(selectedRequestId).isEqualTo(existingRequestId);
    Map<String, Object> selectedTimezoneSnapshotRowRead2 =
        jdbcTemplate.queryForMap(selectTimezoneSnapshotSql2, TENANT, calendarCode, openedBizDate);
    assertThat(selectedTimezoneSnapshotRowRead2).isEqualTo(openedDaySnapshot);
  }

  @Test
  @DisplayName("Trigger 上下文重启后恢复 occurrence coverage 并由结算器按业务义务收敛")
  void triggerRestart_recoversOccurrenceCoverageBeforeBatchDaySettlement() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
        values (?, ?, 'E2E coverage restart calendar', 'UTC', true)\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled)\s\
        values (?, ?, 'E2E coverage restart workflow', 'DAG', 1, true, 'CRON', '0 0 9 * * ?', 'UTC',\s\
        'SCHEDULED', ?, true) returning id\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, ?)\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'READINESS_TIMEOUT' where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    startTriggerHttpServer();
    String calendarCode = "coverage_restart_" + UUID.randomUUID().toString().replace("-", "");
    String workflowCode = "E2E_COVERAGE_RESTART_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, calendarCode);

    Instant scanAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    CronTrigger cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(workflowCode, "coverage-restart")
        .startAt(Date.from(scanAt))
        .withSchedule(
            CronScheduleBuilder.cronSchedule("0 0 9 * * ?").inTimeZone(TimeZone.getTimeZone("UTC")))
        .build();
    Instant firstFire = cronTrigger.getFireTimeAfter(Date.from(scanAt)).toInstant();
    LocalDate bizDate = firstFire.atZone(ZoneOffset.UTC).toLocalDate();
    jdbcTemplate.update(
        insertBatchDayInstanceSql, TENANT, calendarCode, bizDate, Timestamp.from(firstFire));

    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(TENANT);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 9 * * ?");
    descriptor.setTimezone("UTC");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCalendarCode(calendarCode);
    descriptor.setEnabled(true);
    descriptor.setWorkflowEnabled(true);

    TriggerScheduledOccurrencePlanner planner =
        triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
    var plannedFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(cronTrigger);
    assertThat(planner.materialize(descriptor, plannedFireTimes, scanAt)).isPositive();
    long plannedCount = scheduledOccurrenceCount(workflowDefinitionId);
    Instant coverageBeforeRestart = scheduledOccurrenceCoverageThrough(workflowDefinitionId);
    assertThat(scheduledOccurrenceStatus(workflowDefinitionId, firstFire)).isEqualTo("PLANNED");

    restartTriggerHttpServer();
    assertThat(scheduledOccurrenceCount(workflowDefinitionId)).isEqualTo(plannedCount);
    assertThat(scheduledOccurrenceCoverageThrough(workflowDefinitionId))
        .isEqualTo(coverageBeforeRestart);
    TriggerScheduledOccurrencePlanner restartedPlanner =
        triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
    restartedPlanner.materialize(descriptor, plannedFireTimes, scanAt);
    assertThat(scheduledOccurrenceCount(workflowDefinitionId)).isEqualTo(plannedCount);

    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");
    jdbcTemplate.update(
        updateScheduledOccurrenceSql, TENANT, workflowDefinitionId, Timestamp.from(firstFire));
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("两日超容量 coverage 重启追平后结算器仍等待当日全部 occurrence")
  void triggerRestart_resumesBoundedTwoDayCoverageBeforeBatchDaySettlement() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        enabled) values (?, ?, 'E2E bounded coverage calendar', 'UTC', '23:59:59', true)\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled)\s\
        values (?, ?, 'E2E bounded coverage workflow', 'DAG', 1, true, 'CRON', '0/1 * * * * ?', 'UTC',\s\
        'SCHEDULED', ?, false) returning id\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, ?)\
        """;
    final String selectCountSql = """
        select count(*) from (select scheduled_fire_time, lag(scheduled_fire_time) over (order by\s\
        scheduled_fire_time) previous_fire_time from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ?) fires where previous_fire_time is not null\s\
        and scheduled_fire_time <> previous_fire_time + interval '1 second'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ? and occurrence_status = 'PLANNED'\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'READINESS_TIMEOUT' where tenant_id = ?\s\
        and workflow_definition_id = ? and biz_date = ?\
        """;

    startTriggerHttpServer();
    String calendarCode = "bounded_coverage_" + UUID.randomUUID().toString().replace("-", "");
    String workflowCode = "E2E_BOUNDED_COVERAGE_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, calendarCode);

    Instant scanAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant firstFire = scanAt.plusSeconds(1);
    Instant requestedThrough = scanAt.plus(Duration.ofDays(2));
    CronTrigger cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(workflowCode, "bounded-coverage-restart")
        .startAt(Date.from(firstFire))
        .withSchedule(CronScheduleBuilder.cronSchedule("0/1 * * * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC")))
        .build();
    LocalDate bizDate = firstFire.atZone(ZoneOffset.UTC).toLocalDate();
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        TENANT,
        calendarCode,
        bizDate,
        Timestamp.from(bizDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC)));

    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(TENANT);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0/1 * * * * ?");
    descriptor.setTimezone("UTC");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCalendarCode(calendarCode);
    descriptor.setEnabled(true);
    descriptor.setWorkflowEnabled(true);

    TriggerScheduledOccurrencePlanner planner =
        triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
    var plannedFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(cronTrigger);
    int firstPass = planner.materialize(descriptor, plannedFireTimes, scanAt);
    long expectedCount = Duration.between(firstFire, requestedThrough).getSeconds() + 1;
    assertThat(firstPass).isBetween(80_000, 99_999);
    assertThat(scheduledOccurrenceCount(workflowDefinitionId)).isEqualTo(firstPass);
    assertThat(scheduledOccurrenceCoverageThrough(workflowDefinitionId)).isBefore(requestedThrough);

    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");

    restartTriggerHttpServer();
    TriggerScheduledOccurrencePlanner restartedPlanner =
        triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
    int resumedCount = restartedPlanner.materialize(descriptor, plannedFireTimes, scanAt);
    assertThat(resumedCount).isPositive();
    assertThat(scheduledOccurrenceCount(workflowDefinitionId)).isEqualTo(expectedCount);
    assertThat(scheduledOccurrenceCoverageThrough(workflowDefinitionId))
        .isEqualTo(requestedThrough);
    Integer selectedCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, workflowDefinitionId);
    assertThat(selectedCount).isZero();

    long plannedForBizDate = jdbcTemplate.queryForObject(
        selectCountSql2, Long.class, TENANT, workflowDefinitionId, bizDate);
    assertThat(plannedForBizDate).isPositive();
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateScheduledOccurrenceSql, TENANT, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  private long scheduledOccurrenceCount(Long workflowDefinitionId) {
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\
        """;

    return jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, workflowDefinitionId);
  }

  private Instant scheduledOccurrenceCoverageThrough(Long workflowDefinitionId) {
    final String selectCoverageThroughAtSql = """
        select coverage_through_at from batch.scheduled_occurrence_coverage where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectCoverageThroughAtSql,
        (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(),
        TENANT,
        workflowDefinitionId);
  }

  private String scheduledOccurrenceStatus(Long workflowDefinitionId, Instant fireTime) {
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(fireTime));
  }

  private String batchDayStatus(LocalDate bizDate) {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'e2e_calendar'\s\
        and biz_date = ?\
        """;

    return jdbcTemplate.queryForObject(selectDayStatusSql, String.class, TENANT, bizDate);
  }

  private String batchDayStatus(String calendarCode, LocalDate bizDate) {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, bizDate);
  }

  private void isolateScenarioCalendar(String calendarCode, LaunchSeed... seeds) {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, enabled)
        values (?, ?, 'Isolated occurrence scenario', 'UTC', '00:00:00', true)
        """;
    final String updateWorkflowCalendarSql = """
        update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?
        """;
    final String updateJobCalendarSql = """
        update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?
        """;
    jdbcTemplate.update(insertCalendarSql, TENANT, calendarCode);
    for (LaunchSeed seed : seeds) {
      jdbcTemplate.update(
          updateWorkflowCalendarSql, calendarCode, TENANT, seed.workflowDefinitionId());
      jdbcTemplate.update(updateJobCalendarSql, calendarCode, TENANT, seed.jobCode());
    }
  }

  private void insertCutoffBatchDay(String calendarCode, LocalDate bizDate) {
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp - interval '2 days', current_timestamp -\s\
        interval '1 day')\
        """;

    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, bizDate);
  }

  private LocalDate r11BizDate(int offsetDays) {
    return LocalDate.now(ZoneOffset.UTC).plusDays(30L + offsetDays);
  }

  private void insertPlannedOccurrence(
      LaunchSeed seed, String calendarCode, LocalDate bizDate, Instant fireTime) {
    final String insertScheduledOccurrenceSql = """
            insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
            schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
            values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
            """;

    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        seed.workflowDefinitionId(),
        seed.jobCode(),
        calendarCode,
        Timestamp.from(fireTime),
        bizDate);
  }

  private int connectionPid(Connection connection) throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (var statement = connection.createStatement();
        var result = statement.executeQuery(selectPgBackendPidSql)) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private enum SettlementExecutionMode {
    SAME_JVM,
    INDEPENDENT_ORCHESTRATOR_JVM
  }

  private HttpResponse<String> postTriggerLaunch(
      Long workflowDefinitionId, LocalDate bizDate, String requestId) throws Exception {
    return postTriggerLaunch(
        workflowDefinitionId,
        bizDate,
        requestId,
        TriggerType.API,
        Map.of("scenario", "batch-day-settlement-race"));
  }

  private HttpResponse<String> postTriggerLaunch(
      Long workflowDefinitionId,
      LocalDate bizDate,
      String requestId,
      TriggerType triggerType,
      Map<String, Object> params)
      throws Exception {
    Map<String, Object> body = Map.of(
        "tenantId", TENANT,
        "workflowDefinitionId", workflowDefinitionId,
        "bizDate", bizDate.toString(),
        "triggerType", triggerType.code(),
        "params", params);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + TRIGGER_PORT + "/api/triggers/launch"))
        .header(CommonConstants.INTERNAL_SECRET_HEADER, E2E_INTERNAL_SECRET)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-" + requestId)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId)
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, "trace-" + requestId)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String manualTriggerBody(LocalDate bizDate) {
    return "{\"tenantId\":\"t1\",\"bizDate\":\"" + bizDate
        + "\",\"triggerType\":null,\"payload\":null}";
  }

  private void awaitPlatformBlockedSql(String sqlFragment) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where wait_event_type = 'Lock'\s\
        and cardinality(pg_blocking_pids(pid)) > 0 and lower(query) like '%%' || lower(?) || '%%'\
        """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(selectCountSql, Integer.class, sqlFragment);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected platform SQL blocked while executing " + sqlFragment);
  }

  private void awaitBlockedBySql(int blockerPid, String sqlFragment) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))\s\
        and lower(query) like '%%' || lower(?) || '%%'\
        """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid, sqlFragment);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Expected PostgreSQL transaction blocked by " + blockerPid + " executing " + sqlFragment);
  }

  private void awaitBlockedBySqlCount(int blockerPid, String sqlFragment, int expectedCount)
      throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))\s\
        and lower(query) like '%%' || lower(?) || '%%'\
        """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid, sqlFragment);
      if (waiting != null && waiting >= expectedCount) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected " + expectedCount + " PostgreSQL transactions blocked by "
        + blockerPid + " executing " + sqlFragment);
  }

  private void awaitBlockedByRelation(int blockerPid, String relationName) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity a join pg_locks l on l.pid = a.pid and not l.granted\s\
        join pg_class c on c.oid = l.relation where ? = any(pg_blocking_pids(a.pid)) and c.relname = ?\
        """;

    JdbcTemplate businessJdbcTemplate = new JdbcTemplate(businessDataSource);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = businessJdbcTemplate.queryForObject(
          selectCountSql, Integer.class, blockerPid, relationName);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Expected PostgreSQL transaction blocked by " + blockerPid
        + " on relation " + relationName);
  }

  @Test
  @DisplayName("Console MANUAL 显式业务日贯穿真实 Worker 并由生产结算器收口")
  void consoleManualTrigger_preservesExplicitBizDateThroughWorkerAndSettlement() throws Exception {
    final String selectCountSql2 = """
        select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?\
        """;

    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        enabled) values (?, ?, 'Console MANUAL E2E', 'Asia/Tokyo', '06:00:00', true)\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?\
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at, late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version,\s\
        created_at, updated_at, dry_run)\s\
        values (?, ?, ?, 'OPEN', current_timestamp, ?, 0, 0, 'Asia/Tokyo',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'MANUAL' and biz_date = ?\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'MANUAL' and biz_date = ?\
        """;
    final String selectRequestStatusSql = """
              select tr.request_status,
                     tr.trigger_type as root_trigger_type,
                     coalesce((select string_agg(wr.run_status, ',' order by wr.id)
                               from batch.workflow_run wr
                               where wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id),
                              'NONE') as workflow_statuses,
                     coalesce((select string_agg(wr.biz_date::text, ',' order by wr.id)
                               from batch.workflow_run wr
                               where wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id),
                              'NONE') as workflow_biz_dates,
                     coalesce((select string_agg(wnr.node_status, ',' order by wnr.id)
                               from batch.workflow_node_run wnr
                               join batch.workflow_run wr on wr.id = wnr.workflow_run_id
                               where wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id),
                              'NONE') as node_statuses,
                     coalesce((select string_agg(ji.instance_status, ',' order by ji.id)
                               from batch.workflow_node_run wnr
                               join batch.workflow_run wr on wr.id = wnr.workflow_run_id
                               join batch.job_instance ji on ji.tenant_id = wr.tenant_id
                                 and ji.id = wnr.job_instance_id
                               where wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id),
                              'NONE') as job_statuses,
                     coalesce((select string_agg(ji.biz_date::text, ',' order by ji.id)
                               from batch.workflow_node_run wnr
                               join batch.workflow_run wr on wr.id = wnr.workflow_run_id
                               join batch.job_instance ji on ji.tenant_id = wr.tenant_id
                                 and ji.id = wnr.job_instance_id
                               where wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id),
                              'NONE') as job_biz_dates,
                     coalesce((select string_agg(ji.trigger_type, ',' order by ji.id)
                               from batch.workflow_node_run wnr
                               join batch.workflow_run wr on wr.id = wnr.workflow_run_id
                               join batch.job_instance ji on ji.tenant_id = wr.tenant_id
                                 and ji.id = wnr.job_instance_id
                               where wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id),
                              'NONE') as job_trigger_types
              from batch.trigger_request tr
              where tr.tenant_id = ? and tr.request_id = ?
              """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second' where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String customerNo = "MANUAL_" + suffix;
    String calendarCode = "manual_e2e_" + suffix;
    ZoneId calendarZone = ZoneId.of("Asia/Tokyo");
    LocalDate bizDate = LocalDate.of(2026, 12, 21);
    Instant businessDayCutoffAt =
        bizDate.plusDays(1).atTime(LocalTime.of(6, 0)).atZone(calendarZone).toInstant();
    configureImportPayload(seed, customerNo, "Console MANUAL business date customer");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, seed.jobCode());
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        TENANT,
        calendarCode,
        bizDate,
        Timestamp.from(businessDayCutoffAt));

    JsonNode response = postConsoleJson(
        "/api/console/workflow-definitions/" + seed.workflowDefinitionId() + "/trigger",
        """
        {"tenantId":"t1","bizDate":"%s","triggerType":null,"payload":null}
        """.formatted(bizDate),
        "manual-workflow-trigger-" + suffix);
    assertThat(response.path("code").asText()).isIn("0", "SUCCESS", "OK");
    assertThat(response.path("data").asText()).isNotBlank();

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
              selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId(), bizDate);
          assertThat(selectedTriggerRequestCount).isOne();
        });
    String requestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId(), bizDate);
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> launchState =
              jdbcTemplate.queryForMap(selectRequestStatusSql, TENANT, requestId);
          assertThat(launchState.get("request_status"))
              .as("Console MANUAL TriggerRequest progression: %s", launchState)
              .isEqualTo("LAUNCHED");
          assertThat(launchState.get("root_trigger_type"))
              .as("Console entry trigger type is persisted on the root request: %s", launchState)
              .isEqualTo(TriggerType.MANUAL.code());
          assertThat(launchState.get("workflow_statuses"))
              .as("WorkflowRun state for Console MANUAL request: %s", launchState)
              .isEqualTo("SUCCESS");
          assertThat(launchState.get("workflow_biz_dates"))
              .as("WorkflowRun business date for Console MANUAL request: %s", launchState)
              .isEqualTo(bizDate.toString());
          assertThat(launchState.get("job_statuses"))
              .as("JobInstance state for Console MANUAL request: %s", launchState)
              .isEqualTo("SUCCESS");
          assertThat(launchState.get("job_biz_dates"))
              .as("JobInstance business date for Console MANUAL request: %s", launchState)
              .isEqualTo(bizDate.toString());
          assertThat(launchState.get("job_trigger_types"))
              .as("Workflow child Job uses the internal EVENT trigger type: %s", launchState)
              .isEqualTo(TriggerType.EVENT.code());
          Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql2, Integer.class, TENANT, customerNo);
          assertThat(selectedCustomerAccountCount).isOne();
        });

    jdbcTemplate.update(updateBatchDayInstanceSql, TENANT, calendarCode, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("MANUAL_APPROVAL 下从关闭日拒绝 occurrence 发起补跑,审批后恢复并只生成一个 Workflow 节点实例")
  void approvalOfConsoleCatchUp_reconcilesAndLaunchesWorkflowNode() throws Exception {
    final String selectCountSql8 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
        """;

    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params = ?::jsonb where tenant_id = ? and job_code = ?\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set catch_up_policy = 'MANUAL_APPROVAL' where tenant_id = ?\s\
        and calendar_code = 'e2e_calendar'\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        settled_at) values (?, 'e2e_calendar', ?, 'SETTLED', current_timestamp, current_timestamp)\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectOccurrenceStatusSql2 = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectStatusSql = """
        select status from batch.trigger_misfire_pending where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;
    final String selectStatusSql2 = """
        select status from batch.trigger_misfire_pending where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;
    final String selectOccurrenceStatusSql3 = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectCountSql2 = """
                  select count(*) from batch.workflow_node_run nr
                  join batch.workflow_run wr on wr.id = nr.workflow_run_id
                  where wr.tenant_id = ? and wr.workflow_definition_id = ?
                  and wr.trigger_request_id = (select id from batch.trigger_request
                  where tenant_id = ? and request_id = ?)
                  and nr.node_code = 'JOB' and nr.job_instance_id is not null
                  """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectApprovalStatusSql = """
        select approval_status from batch.approval_command where tenant_id = ? and approval_no = ?\s\
        and target_id = ?\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.scheduled_occurrence_catch_up_audit where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ? and requested_by = ?\s\
        and reason = 'recover rejected scheduled fire' and request_id = ?\
        """;
    final String selectCountSql4 = """
        select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\s\
        and publish_status = 'PUBLISHED'\
        """;
    final String selectJobInstanceIdSql = """
        select nr.job_instance_id from batch.workflow_node_run nr
        join batch.workflow_run wr on wr.id = nr.workflow_run_id
        where wr.tenant_id = ? and wr.workflow_definition_id = ?
        and wr.trigger_request_id = (select id from batch.trigger_request
        where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB'
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectCountSql5 = """
        select count(*) from batch.job_task where tenant_id = ? and job_instance_id = ?\s\
        and task_status = 'SUCCESS'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectCountSql6 = """
        select count(*) as linked_job_count,
               count(*) filter (
                 where so.biz_date is distinct from ?
                    or tr.biz_date is distinct from ?
                    or wr.biz_date is distinct from ?
                    or ji.biz_date is distinct from ?
               ) as mismatch_count
          from batch.scheduled_occurrence so
          join batch.trigger_request tr
            on tr.tenant_id = so.tenant_id and tr.request_id = so.request_id
          join batch.workflow_run wr
            on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id
          join batch.workflow_node_run wnr
            on wnr.workflow_run_id = wr.id and wnr.node_code = 'JOB'
           and wnr.job_instance_id is not null
          join batch.job_instance ji
            on ji.tenant_id = tr.tenant_id and ji.id = wnr.job_instance_id
         where so.tenant_id = ? and so.workflow_definition_id = ?
           and so.scheduled_fire_time = ? and so.request_id = ?
        """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'FAILED' where tenant_id = ? and id = ?
    """;
    final String selectRunStatusSql2 = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'SETTLED' where tenant_id = ?\s\
        and calendar_code = 'e2e_calendar' and biz_date = ?\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'e2e_calendar'\s\
        and biz_date = ?\
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,\s\
        request_status, parent_workflow_run_id, parent_node_code)\s\
        values (?, ?, 'CATCH_UP', ?, ?, ?, 'ACCEPTED', ?, 'JOB')\
        """;
    final String selectCountSql7 = """
        select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String insertTriggerRequestSql2 = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,\s\
        request_status, parent_workflow_run_id, parent_node_code)\s\
        values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED', ?, 'JOB')\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "CATCHUP_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Catch-up customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        updateJobDefinitionSql,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());
    jdbcTemplate.update(updateBusinessCalendarSql, TENANT);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, BIZ_DATE);

    fireClosedDayScheduleThroughQuartz(seed);
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(FIRE_TIME));
          assertThat(selectedOccurrenceStatus).isEqualTo("CLOSED_DAY_REJECTED");
        });

    JsonNode catchUp = postConsoleJson(
        "/api/console/workflow-definitions/" + seed.workflowDefinitionId()
            + "/scheduled-occurrences/catch-up",
        """
        {"tenantId":"t1","scheduledFireTime":"2026-04-30T09:30:00Z",
         "bizDate":"2026-04-30","reason":"recover rejected scheduled fire"}
        """,
        "catch-up-submit-" + UUID.randomUUID());
    String requestId = catchUp.path("data").path("requestId").asText();
    String approvalNo = catchUp.path("data").path("approvalId").asText();
    assertThat(requestId).isNotBlank();
    assertThat(approvalNo).isNotBlank();
    assertThat(catchUp.path("data").path("status").asText()).isEqualTo("PENDING_APPROVAL");

    String selectedOccurrenceStatusRead2 = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql2,
        String.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(FIRE_TIME));
    assertThat(selectedOccurrenceStatusRead2).isEqualTo("MISFIRE_PENDING");
    String selectedStatus = jdbcTemplate.queryForObject(
        selectStatusSql,
        String.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(FIRE_TIME));
    assertThat(selectedStatus).isEqualTo("PENDING");

    JsonNode approved = postConsoleJson(
        "/api/console/approvals/" + approvalNo + "/approve",
        """
        {"tenantId":"t1","operatorId":"e2e-approver","reason":"approved recovery"}
        """,
        "catch-up-approve-" + UUID.randomUUID());
    assertThat(approved.path("code").asText()).isIn("0", "SUCCESS", "OK");

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedStatusRead2 = jdbcTemplate.queryForObject(
              selectStatusSql2,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(FIRE_TIME));
          assertThat(selectedStatusRead2).isEqualTo("APPROVED");
          String selectedOccurrenceStatusRead3 = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql3,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(FIRE_TIME));
          assertThat(selectedOccurrenceStatusRead3).isEqualTo("REQUESTED");
          Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              TENANT,
              requestId);
          assertThat(selectedWorkflowRunCount).isEqualTo(1);
          Integer selectedWorkflowNodeRunCount = jdbcTemplate.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              TENANT,
              requestId);
          assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
        });

    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, requestId);
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    String selectedApprovalStatus = jdbcTemplate.queryForObject(
        selectApprovalStatusSql, String.class, TENANT, approvalNo, requestId);
    assertThat(selectedApprovalStatus).isEqualTo("EXECUTED");
    Integer selectedScheduledOccurrenceCatchUpAuditCount = jdbcTemplate.queryForObject(
        selectCountSql3,
        Integer.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(FIRE_TIME),
        OPERATOR,
        requestId);
    assertThat(selectedScheduledOccurrenceCatchUpAuditCount).isEqualTo(1);
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql4, Integer.class, TENANT, requestId);
    assertThat(selectedTriggerOutboxEventCount).isEqualTo(1);

    Long jobInstanceId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql, Long.class, TENANT, seed.workflowDefinitionId(), TENANT, requestId);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          Integer selectedJobTaskCount =
              jdbcTemplate.queryForObject(selectCountSql5, Integer.class, TENANT, jobInstanceId);
          assertThat(selectedJobTaskCount).isGreaterThan(0);
          String selectedRunStatus = jdbcTemplate.queryForObject(
              selectRunStatusSql, String.class, TENANT, TENANT, requestId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          Integer selectedCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql8, Integer.class, TENANT, customerNo);
          assertThat(selectedCount).isEqualTo(1);
        });

    Map.Entry<Integer, Integer> catchUpBizDateCounts = jdbcTemplate.queryForObject(
        selectCountSql6,
        (rs, rowNum) -> Map.entry(rs.getInt("linked_job_count"), rs.getInt("mismatch_count")),
        BIZ_DATE,
        BIZ_DATE,
        BIZ_DATE,
        BIZ_DATE,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(FIRE_TIME),
        requestId);
    assertThat(catchUpBizDateCounts.getKey())
        .as("approved catch-up has exactly one linked Workflow Job node")
        .isEqualTo(1);
    assertThat(catchUpBizDateCounts.getValue())
        .as(
            "the scheduled occurrence business date remains frozen through approval and Job dispatch")
        .isZero();

    Long parentWorkflowRunId = jdbcTemplate.queryForObject(
        selectIdSql, Long.class, TENANT, seed.workflowDefinitionId(), TENANT, requestId);
    int affectedWorkflowRunRows =
        jdbcTemplate.update(updateWorkflowRunSql, TENANT, parentWorkflowRunId);
    assertThat(affectedWorkflowRunRows).isEqualTo(1);
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql2, String.class, TENANT, parentWorkflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("FAILED");
    int affectedBatchDayInstanceRows =
        jdbcTemplate.update(updateBatchDayInstanceSql, TENANT, BIZ_DATE);
    assertThat(affectedBatchDayInstanceRows).isEqualTo(1);
    String selectedDayStatus =
        jdbcTemplate.queryForObject(selectDayStatusSql, String.class, TENANT, BIZ_DATE);
    assertThat(selectedDayStatus).isEqualTo("SETTLED");
    String inactiveParentChildRequestId = "inactive-parent-child-" + UUID.randomUUID();
    assertThatThrownBy(() -> jdbcTemplate.update(
            insertTriggerRequestSql,
            TENANT,
            inactiveParentChildRequestId,
            seed.jobCode(),
            BIZ_DATE,
            "inactive-parent-child-dedup-" + UUID.randomUUID(),
            parentWorkflowRunId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("active parent WorkflowRun is required");
    Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
        selectCountSql7, Integer.class, TENANT, inactiveParentChildRequestId);
    assertThat(selectedTriggerRequestCount).isZero();

    String invalidChildRequestId = "invalid-date-child-" + UUID.randomUUID();
    assertThatThrownBy(() -> jdbcTemplate.update(
            insertTriggerRequestSql2,
            TENANT,
            invalidChildRequestId,
            seed.jobCode(),
            BIZ_DATE.plusDays(1),
            "invalid-date-child-dedup-" + UUID.randomUUID(),
            parentWorkflowRunId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("does not match parent WorkflowRun business date");
    Integer selectedTriggerRequestCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql7, Integer.class, TENANT, invalidChildRequestId);
    assertThat(selectedTriggerRequestCountRead2).isZero();
  }

  @Test
  @DisplayName("CUTOFF 日的计划 fire 经 Quartz/Worker 完成后由生产结算器收口")
  void cutoffScheduledFire_completesWorkerBeforeBatchDaySettlement() throws Exception {
    final String selectCountSql3 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
        """;

    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params = ?::jsonb where tenant_id = ? and job_code = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp)\
        """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ? order by id desc limit 1\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectJobInstanceIdSql = """
        select nr.job_instance_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and wr.workflow_definition_id = ? and wr.trigger_request_id = (select id from batch.trigger_request\s\
        where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB' and nr.job_instance_id is not null\
        """;
    final String selectJobInstanceIdSql2 = """
        select nr.job_instance_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and wr.workflow_definition_id = ? and wr.trigger_request_id = (select id from batch.trigger_request\s\
        where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB'\
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LocalDate cutoffBizDate = LocalDate.now(ZoneOffset.UTC).minusDays(2);
    Instant cutoffFireTime = cutoffBizDate.atTime(9, 30).toInstant(ZoneOffset.UTC);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String calendarCode = "e2e_cutoff_" + UUID.randomUUID().toString().replace("-", "");
    isolateScenarioCalendar(calendarCode, seed);
    String customerNo = "CUTOFF_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Cutoff scheduled customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        updateJobDefinitionSql,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        seed.workflowDefinitionId(),
        seed.jobCode(),
        calendarCode,
        Timestamp.from(cutoffFireTime),
        cutoffBizDate);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, cutoffBizDate);

    fireScheduledOccurrenceThroughQuartz(
        seed,
        cutoffFireTime,
        "cutoff-day-" + UUID.randomUUID(),
        calendarCode,
        "UTC",
        "0 30 9 * * ?");
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(cutoffFireTime));
          assertThat(selectedScheduledOccurrenceCount).isOne();
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(cutoffFireTime));
          assertThat(selectedOccurrenceStatus).isEqualTo("REQUESTED");
        });
    String requestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId(), cutoffBizDate);
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              TENANT,
              requestId);
          assertThat(selectedWorkflowRunCount).isEqualTo(1);
        });

    batchDaySettleScheduler.settle();
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, cutoffBizDate);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          List<Long> selectedJobInstanceIdValues = jdbcTemplate.query(
              selectJobInstanceIdSql,
              (rs, rowNum) -> rs.getLong(1),
              TENANT,
              seed.workflowDefinitionId(),
              TENANT,
              requestId);
          assertThat(selectedJobInstanceIdValues).hasSize(1);
        });
    Long jobInstanceId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql2,
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        requestId);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          String selectedRunStatus = jdbcTemplate.queryForObject(
              selectRunStatusSql, String.class, TENANT, TENANT, requestId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          Integer selectedCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql3, Integer.class, TENANT, customerNo);
          assertThat(selectedCount).isEqualTo(1);
        });

    batchDaySettleScheduler.settle();
    String selectedDayStatusRead2 = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, cutoffBizDate);
    assertThat(selectedDayStatusRead2).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("连续两次 Quartz fire 跨 UTC 日期后按东京业务日独立运行并结算")
  void shouldKeepCalendarBusinessDate_whenFireCrossesUtcMidnight() throws Exception {
    final String selectCountSql3 = """
            select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
            """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
            enabled) values (?, ?, 'UTC date crossing E2E', 'Asia/Tokyo', '06:00:00', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition\s\
            set calendar_code = ?, schedule_type = 'CRON', schedule_expr = ?, timezone = 'UTC', trigger_mode =\s\
            'SCHEDULED', schedule_enabled = true where tenant_id = ? and id = ?\
            """;
    final String updateTenantConfigurationRevisionSql = """
            update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
        """;
    final String selectCountSql = """
            select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time in (?, ?)\
            """;
    final String selectBizDateSql = """
            select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time in (?, ?) order by scheduled_fire_time\
            """;
    final String selectCoverageThroughAtSql = """
            select coverage_through_at from batch.scheduled_occurrence_coverage where tenant_id = ?\s\
            and workflow_definition_id = ? and calendar_code = ?\
            """;
    final String insertBatchDayInstanceSql = """
            insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
            cutoff_at) values (?, ?, ?, 'CUTOFF', ?, ?)\
            """;
    final String selectRequestIdSql = """
            select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
            and biz_date = ? order by id desc limit 1\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.trigger_request tr\s\
            join batch.workflow_run wr on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id\s\
            join batch.workflow_node_run nr on nr.workflow_run_id = wr.id and nr.job_instance_id is not null\s\
            join batch.job_instance ji on ji.tenant_id = tr.tenant_id and ji.id = nr.job_instance_id\s\
            where tr.tenant_id = ? and tr.request_id = ? and (tr.biz_date is distinct from ?\s\
            or wr.biz_date is distinct from ? or ji.biz_date is distinct from ?)\
            """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    ZoneId tokyoZone = ZoneId.of("Asia/Tokyo");
    LocalDate firstBizDate = LocalDate.now(tokyoZone).minusDays(3);
    LocalDate secondBizDate = firstBizDate.plusDays(1);
    Instant firstFireTime = firstBizDate.atTime(8, 30).atZone(tokyoZone).toInstant();
    Instant secondFireTime = secondBizDate.atTime(8, 30).atZone(tokyoZone).toInstant();
    assertThat(firstFireTime.atZone(ZoneOffset.UTC).toLocalDate())
        .isEqualTo(firstBizDate.minusDays(1));
    assertThat(secondFireTime.atZone(ZoneOffset.UTC).toLocalDate())
        .isEqualTo(secondBizDate.minusDays(1));

    String calendarCode = "utc_cross_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "TZ_CROSS_" + UUID.randomUUID().toString().replace("-", "");
    configureImportPayload(seed, customerNo, "Calendar cross-date customer");
    String scheduleExpression = "0 30 23 * * ?";
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        calendarCode,
        scheduleExpression,
        TENANT,
        seed.workflowDefinitionId());

    Instant scanAt = firstFireTime.minusSeconds(1);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql,
        Timestamp.from(scanAt.minus(Duration.ofDays(1))),
        TENANT);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(TENANT);
    descriptor.setWorkflowDefinitionId(seed.workflowDefinitionId());
    descriptor.setWorkflowCode(seed.jobCode());
    descriptor.setWorkflowVersion(1);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression(scheduleExpression);
    descriptor.setTimezone("UTC");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCalendarCode(calendarCode);
    descriptor.setEnabled(true);
    descriptor.setWorkflowEnabled(true);
    CronTrigger plannerTrigger = TriggerBuilder.newTrigger()
        .withIdentity("utc-cross-" + UUID.randomUUID(), "utc-cross-date")
        .startAt(Date.from(scanAt))
        .endAt(Date.from(secondFireTime))
        .withSchedule(CronScheduleBuilder.cronSchedule(scheduleExpression)
            .inTimeZone(TimeZone.getTimeZone("UTC")))
        .build();
    TriggerScheduledOccurrencePlanner planner =
        triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
    var plannedFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(plannerTrigger);
    assertThat(planner.materialize(descriptor, plannedFireTimes, scanAt)).isEqualTo(2);
    Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(firstFireTime),
        Timestamp.from(secondFireTime));
    assertThat(selectedScheduledOccurrenceCount).isEqualTo(2);
    List<LocalDate> selectedBizDateValues = jdbcTemplate.queryForList(
        selectBizDateSql,
        LocalDate.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(firstFireTime),
        Timestamp.from(secondFireTime));
    assertThat(selectedBizDateValues).containsExactly(firstBizDate, secondBizDate);
    Instant firstBusinessDayOpenAt = firstBizDate.atTime(6, 0).atZone(tokyoZone).toInstant();
    Instant firstBusinessDayCutoffAt =
        firstBizDate.plusDays(1).atTime(6, 0).atZone(tokyoZone).toInstant();
    Instant secondBusinessDayOpenAt =
        secondBizDate.atTime(6, 0).atZone(tokyoZone).toInstant();
    Instant secondBusinessDayCutoffAt =
        secondBizDate.plusDays(1).atTime(6, 0).atZone(tokyoZone).toInstant();
    Timestamp selectedCoverageThroughAt = jdbcTemplate.queryForObject(
        selectCoverageThroughAtSql,
        Timestamp.class,
        TENANT,
        seed.workflowDefinitionId(),
        calendarCode);
    assertThat(selectedCoverageThroughAt.toInstant()).isAfterOrEqualTo(secondBusinessDayCutoffAt);

    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        TENANT,
        calendarCode,
        firstBizDate,
        Timestamp.from(firstBusinessDayOpenAt),
        Timestamp.from(firstBusinessDayCutoffAt));
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        TENANT,
        calendarCode,
        secondBizDate,
        Timestamp.from(secondBusinessDayOpenAt),
        Timestamp.from(secondBusinessDayCutoffAt));
    fireScheduledOccurrenceThroughQuartz(
        seed,
        firstFireTime,
        "utc-cross-first-date-" + UUID.randomUUID(),
        calendarCode,
        "UTC",
        scheduleExpression);

    awaitScheduledOccurrenceRequested(seed.workflowDefinitionId(), firstFireTime);
    String requestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId(), firstBizDate);
    Long workflowRunId = awaitWorkflowRunForRequest(requestId);
    Long jobInstanceId = awaitWorkflowJobInstanceForRequest(seed.workflowDefinitionId(), requestId);
    Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
        selectCountSql2,
        Integer.class,
        TENANT,
        requestId,
        firstBizDate,
        firstBizDate,
        firstBizDate);
    assertThat(selectedTriggerRequestCount)
        .as("Quartz fire instant, Tokyo business date, and child Job identity remain distinct")
        .isZero();

    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, firstBizDate)).isEqualTo("IN_FLIGHT");
    assertThat(batchDayStatus(calendarCode, secondBizDate))
        .as("an empty date with unresolved planned work must not be auto-settled")
        .isEqualTo("CUTOFF");
    assertThat(occurrenceStatus(seed.workflowDefinitionId(), secondFireTime)).isEqualTo("PLANNED");
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          Integer selectedCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql3, Integer.class, TENANT, customerNo);
          assertThat(selectedCount).isEqualTo(1);
        });
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedRunStatus =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
        });

    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, firstBizDate)).isEqualTo("SETTLED");
    assertThat(batchDayStatus(calendarCode, secondBizDate)).isEqualTo("CUTOFF");
    assertThat(occurrenceStatus(seed.workflowDefinitionId(), secondFireTime)).isEqualTo("PLANNED");

    fireScheduledOccurrenceThroughQuartz(
        seed,
        secondFireTime,
        "utc-cross-second-date-" + UUID.randomUUID(),
        calendarCode,
        "UTC",
        scheduleExpression);
    awaitScheduledOccurrenceRequested(seed.workflowDefinitionId(), secondFireTime);
    String secondRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId(), secondBizDate);
    Long secondWorkflowRunId = awaitWorkflowRunForRequest(secondRequestId);
    Long secondJobInstanceId =
        awaitWorkflowJobInstanceForRequest(seed.workflowDefinitionId(), secondRequestId);
    Integer selectedTriggerRequestCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql2,
        Integer.class,
        TENANT,
        secondRequestId,
        secondBizDate,
        secondBizDate,
        secondBizDate);
    assertThat(selectedTriggerRequestCountRead2)
        .as("the second Quartz identity retains its own Tokyo business date")
        .isZero();
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, secondBizDate)).isEqualTo("IN_FLIGHT");
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedInstanceStatusRead2 = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, secondJobInstanceId);
          assertThat(selectedInstanceStatusRead2).isEqualTo("SUCCESS");
        });
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedRunStatusRead2 = jdbcTemplate.queryForObject(
              selectRunStatusSql, String.class, TENANT, secondWorkflowRunId);
          assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
        });
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, secondBizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("CUTOFF 日的复合 Workflow 等待两个真实 Worker 节点完成后结算")
  void cutoffCompositeWorkflow_waitsForBothWorkerJobsBeforeBatchDaySettlement() throws Exception {
    final String selectCountSql3 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no in (?, ?)\
        """;

    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp)\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ? order by id desc limit 1\
        """;
    final String selectIdSql = """
        select wr.id from batch.workflow_run wr join batch.trigger_request tr on tr.tenant_id = wr.tenant_id\s\
        and tr.id = wr.trigger_request_id where wr.tenant_id = ? and wr.workflow_definition_id = ?\s\
        and tr.request_id = ?\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id in (?, ?) order by id\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code in ('JOB', 'JOB_B')\s\
        and node_status = 'SUCCESS'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LocalDate cutoffBizDate = LocalDate.now(ZoneOffset.UTC).minusDays(2);
    Instant cutoffFireTime = cutoffBizDate.atTime(9, 30).toInstant(ZoneOffset.UTC);
    LaunchSeed first = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LaunchSeed second = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String calendarCode = "e2e_cutoff_" + UUID.randomUUID().toString().replace("-", "");
    isolateScenarioCalendar(calendarCode, first, second);
    String firstCustomerNo = "DAG_A_" + UUID.randomUUID().toString().replace("-", "");
    String secondCustomerNo = "DAG_B_" + UUID.randomUUID().toString().replace("-", "");

    configureImportPayload(first, firstCustomerNo, "Composite Workflow customer A");
    configureImportPayload(second, secondCustomerNo, "Composite Workflow customer B");
    prepareCompositeImportWorkflow(first, second);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        first.workflowDefinitionId(),
        first.jobCode(),
        calendarCode,
        Timestamp.from(cutoffFireTime),
        cutoffBizDate);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, cutoffBizDate);

    fireScheduledOccurrenceThroughQuartz(
        first,
        cutoffFireTime,
        "cutoff-composite-" + UUID.randomUUID(),
        calendarCode,
        "UTC",
        "0 30 9 * * ?");
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              first.workflowDefinitionId(),
              Timestamp.from(cutoffFireTime));
          assertThat(selectedOccurrenceStatus).isEqualTo("REQUESTED");
        });

    String requestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, first.workflowDefinitionId(), cutoffBizDate);
    Long workflowRunId = await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () -> jdbcTemplate
                .query(
                    selectIdSql,
                    (rs, rowNum) -> rs.getLong(1),
                    TENANT,
                    first.workflowDefinitionId(),
                    requestId)
                .stream()
                .findFirst()
                .orElse(null),
            id -> id != null);

    batchDaySettleScheduler.settle();
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, cutoffBizDate);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");

    Map<String, Long> jobInstances = Map.of(
        "JOB", awaitWorkflowJobInstance(workflowRunId, "JOB"),
        "JOB_B", awaitWorkflowJobInstance(workflowRunId, "JOB_B"));
    assertThat(jobInstances.get("JOB")).isNotEqualTo(jobInstances.get("JOB_B"));
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          List<String> selectedInstanceStatusValues = jdbcTemplate.queryForList(
              selectInstanceStatusSql,
              String.class,
              TENANT,
              jobInstances.get("JOB"),
              jobInstances.get("JOB_B"));
          assertThat(selectedInstanceStatusValues).containsExactly("SUCCESS", "SUCCESS");
          Integer selectedWorkflowNodeRunCount =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunId);
          assertThat(selectedWorkflowNodeRunCount).isEqualTo(2);
          String selectedRunStatus =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
          Integer selectedCount = businessJdbc.queryForObject(
              selectCountSql3, Integer.class, TENANT, firstCustomerNo, secondCustomerNo);
          assertThat(selectedCount).isEqualTo(2);
        });

    batchDaySettleScheduler.settle();
    String selectedDayStatusRead2 = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, cutoffBizDate);
    assertThat(selectedDayStatusRead2).isEqualTo("SETTLED");
    Integer selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, workflowRunId);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
  }

  @Test
  @DisplayName("真实外部事件 API 多目标路由经 Kafka 和 Import Worker 执行并可回查")
  void externalEventApi_fansOutToWorkersAndReportsTargetResults() throws Exception {
    final String selectCustomerNoSql = """
        select customer_no from biz.customer_account where tenant_id = ? and customer_no in (?, ?)\s\
        order by customer_no\
        """;

    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        enabled) values (?, ?, 'External event fan-out E2E', 'Asia/Tokyo', '06:00:00', true)\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id in (?, ?)\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', ?, ?) on conflict (tenant_id, calendar_code, biz_date) do update\s\
        set day_status = 'CUTOFF', cutoff_at = excluded.cutoff_at\
        """;
    final String insertExpectedWorkOccurrenceSql = """
        insert into batch.expected_work_occurrence (tenant_id, calendar_code, biz_date, workflow_definition_id,\s\
        source_type, source_key, occurrence_status)\s\
        values (?, ?, ?, ?, 'EXTERNAL_EVENT', ?, 'EXPECTED'), (?, ?, ?, ?, 'EXTERNAL_EVENT', ?, 'EXPECTED')\
        """;
    final String insertApiKeySql = """
        insert into batch.api_key (tenant_id, key_name, key_prefix, key_hash, salt, key_hash_algo, scopes,\s\
        enabled) values (?, ?, ?, ?, ?, ?, ?, true) returning id\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request tr join batch.workflow_run wr on wr.tenant_id = tr.tenant_id\s\
        and wr.trigger_request_id = tr.id join batch.workflow_node_run nr on nr.workflow_run_id = wr.id\s\
        and nr.job_instance_id is not null join batch.job_instance ji on ji.tenant_id = wr.tenant_id\s\
        and ji.id = nr.job_instance_id where tr.tenant_id = ? and tr.external_event_source = ?\s\
        and tr.external_event_id = ? and (tr.biz_date is distinct from ? or wr.biz_date is distinct from ?\s\
        or ji.biz_date is distinct from ?)\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.trigger_request tr join batch.workflow_run wr on wr.tenant_id = tr.tenant_id\s\
        and wr.trigger_request_id = tr.id join batch.workflow_node_run nr on nr.workflow_run_id = wr.id\s\
        and nr.job_instance_id is not null where tr.tenant_id = ? and tr.external_event_source = ?\s\
        and tr.external_event_id = ?\
        """;
    final String selectEventSourceSql = """
        select event_source from batch.external_event_delivery where tenant_id = ? and event_id = ?\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.trigger_request where tenant_id = ? and external_event_source = ?\s\
        and external_event_id = ?\
        """;
    final String selectCountSql4 = """
        select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id in (select request_id\s\
        from batch.trigger_request where tenant_id = ? and external_event_source = ? and external_event_id = ?)\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.expected_work_occurrence where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ? and source_type = 'EXTERNAL_EVENT' and source_key = ? order by workflow_definition_id\
        """;
    final String deleteApiKeySql = """
        delete from batch.api_key where id = ?
    """;
    final String deleteExternalEventExpectationDefinitionSql = """
        delete from batch.external_event_expectation_definition where tenant_id = ? and event_code = ?\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed first = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LaunchSeed second = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String eventCode = "E2E_EVENT_" + suffix;
    String eventId = "payment-close-" + suffix;
    String source = "billing-e2e-" + suffix;
    String rawApiKey = "bk_" + suffix;
    String idempotencyKey = "event-e2e-key-" + suffix;
    String calendarCode = "e2e_event_" + suffix.substring(0, 12);
    ZoneId calendarZone = ZoneId.of("Asia/Tokyo");
    LocalDate bizDate = LocalDate.now(calendarZone).minusDays(2);
    Instant businessDayOpenAt =
        bizDate.atTime(LocalTime.of(6, 0)).atZone(calendarZone).toInstant();
    Instant businessDayCutoffAt =
        bizDate.plusDays(1).atTime(LocalTime.of(6, 0)).atZone(calendarZone).toInstant();
    String firstCustomer = "EVENT_A_" + suffix;
    String secondCustomer = "EVENT_B_" + suffix;

    configureImportPayload(first, firstCustomer, "External event target A");
    configureImportPayload(second, secondCustomer, "External event target B");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        calendarCode,
        TENANT,
        first.workflowDefinitionId(),
        second.workflowDefinitionId());
    insertExternalEventExpectation(first.workflowDefinitionId(), eventCode, calendarCode);
    insertExternalEventExpectation(second.workflowDefinitionId(), eventCode, calendarCode);
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        TENANT,
        calendarCode,
        bizDate,
        Timestamp.from(businessDayOpenAt),
        Timestamp.from(businessDayCutoffAt));
    jdbcTemplate.update(
        insertExpectedWorkOccurrenceSql,
        TENANT,
        calendarCode,
        bizDate,
        first.workflowDefinitionId(),
        eventCode,
        TENANT,
        calendarCode,
        bizDate,
        second.workflowDefinitionId(),
        eventCode);
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("CUTOFF");
    ApiKeyHasher.SaltedHash hash = ApiKeyHasher.hashWithSaltKdf(rawApiKey);
    Long apiKeyId = jdbcTemplate.queryForObject(
        insertApiKeySql,
        Long.class,
        TENANT,
        source,
        rawApiKey.substring(0, 8),
        hash.hash(),
        hash.salt(),
        ApiKeyHasher.ALGO_PBKDF2,
        "trigger.event");

    try {
      String requestBody = """
          {"tenantId":"%s","externalEventCode":"%s","externalEventId":"%s",
           "bizDate":"%s","params":{"eventTrace":"%s"}}
          """.formatted(TENANT, eventCode, eventId, bizDate, suffix);
      JsonNode accepted =
          postTriggerEventJson("/api/triggers/events", requestBody, rawApiKey, idempotencyKey);
      assertThat(accepted.path("data").path("targets").findValuesAsText("workflowDefinitionId"))
          .containsExactlyInAnyOrder(
              first.workflowDefinitionId().toString(),
              second.workflowDefinitionId().toString());

      Long lateTarget =
          insertWorkflowDefinitionForEventRoute("external-event-late-" + suffix, calendarCode);
      insertExternalEventExpectation(lateTarget, eventCode, calendarCode);
      JsonNode replay = postTriggerEventJson(
          "/api/triggers/events", requestBody, rawApiKey, idempotencyKey + "-replay");
      assertThat(replay.path("data").path("targets").findValuesAsText("workflowDefinitionId"))
          .containsExactlyInAnyOrder(
              first.workflowDefinitionId().toString(),
              second.workflowDefinitionId().toString());

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            JsonNode status = getTriggerEventStatus(rawApiKey, idempotencyKey + "-replay");
            JsonNode targets = status.path("data").path("targets");
            assertThat(targets).hasSize(2);
            assertThat(targets.findValuesAsText("requestStatus"))
                .containsExactlyInAnyOrder("LAUNCHED", "LAUNCHED");
            assertThat(targets.findValuesAsText("workflowRunStatus"))
                .containsExactlyInAnyOrder("SUCCESS", "SUCCESS");
            assertThat(targets.findValuesAsText("workflowRunId"))
                .allMatch(value -> !value.isBlank());
            Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
                selectCountSql, Integer.class, TENANT, source, eventId, bizDate, bizDate, bizDate);
            assertThat(selectedTriggerRequestCount)
                .as(
                    "external event request, each WorkflowRun, and its JobInstance keep the event business date")
                .isZero();
            Integer selectedTriggerRequestCountRead2 = jdbcTemplate.queryForObject(
                selectCountSql2, Integer.class, TENANT, source, eventId);
            assertThat(selectedTriggerRequestCountRead2).isEqualTo(2);
            JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
            List<String> selectedCustomerNoValues = businessJdbc.queryForList(
                selectCustomerNoSql, String.class, TENANT, firstCustomer, secondCustomer);
            assertThat(selectedCustomerNoValues)
                .containsExactlyInAnyOrder(firstCustomer, secondCustomer);
          });

      String selectedEventSource =
          jdbcTemplate.queryForObject(selectEventSourceSql, String.class, TENANT, eventId);
      assertThat(selectedEventSource).isEqualTo(source);
      Integer selectedTriggerRequestCountRead3 =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, source, eventId);
      assertThat(selectedTriggerRequestCountRead3).isEqualTo(2);
      Integer selectedTriggerOutboxEventCount = jdbcTemplate.queryForObject(
          selectCountSql4, Integer.class, TENANT, TENANT, source, eventId);
      assertThat(selectedTriggerOutboxEventCount).isEqualTo(2);
      List<String> selectedOccurrenceStatusValues = jdbcTemplate.queryForList(
          selectOccurrenceStatusSql, String.class, TENANT, calendarCode, bizDate, eventCode);
      assertThat(selectedOccurrenceStatusValues).containsExactly("SATISFIED", "SATISFIED");
      batchDaySettleScheduler.settle();
      assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("SETTLED");
    } finally {
      jdbcTemplate.update(deleteApiKeySql, apiKeyId);
      jdbcTemplate.update(deleteExternalEventExpectationDefinitionSql, TENANT, eventCode);
    }
  }

  private void insertExternalEventExpectation(
      Long workflowDefinitionId, String eventCode, String calendarCode) {
    final String insertExternalEventExpectationDefinitionSql = """
            insert into batch.external_event_expectation_definition (tenant_id, calendar_code,\s\
            workflow_definition_id, event_code, expected_local_time, enabled) values (?, ?, ?, ?, '09:30', true)\
            """;

    jdbcTemplate.update(
        insertExternalEventExpectationDefinitionSql,
        TENANT,
        calendarCode,
        workflowDefinitionId,
        eventCode);
  }

  private Long insertWorkflowDefinitionForEventRoute(String workflowCode, String calendarCode) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values (?, ?, ?, 'DAG', 1, true, ?) returning id\
        """;

    return jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, workflowCode, calendarCode);
  }

  private JsonNode postTriggerEventJson(
      String path, String body, String rawApiKey, String idempotencyKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + TRIGGER_PORT + path))
        .header(CommonConstants.BATCH_API_KEY_HEADER, rawApiKey)
        .header(CommonConstants.BATCH_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("Trigger external event POST failed: %s", response.body())
        .isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private HttpResponse<String> postTriggerEventResponse(
      String body, String rawApiKey, String idempotencyKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + TRIGGER_PORT + "/api/triggers/events"))
        .header(CommonConstants.BATCH_API_KEY_HEADER, rawApiKey)
        .header(CommonConstants.BATCH_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String externalEventBody(String eventCode, String eventId, LocalDate bizDate) {
    return """
        {"tenantId":"%s","externalEventCode":"%s","externalEventId":"%s",
         "bizDate":"%s","params":{"scenario":"settlement-fence"}}
        """.formatted(TENANT, eventCode, eventId, bizDate);
  }

  private JsonNode getTriggerEventStatus(String rawApiKey, String idempotencyKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(
            "http://127.0.0.1:" + TRIGGER_PORT + "/api/triggers/events/status?tenantId=" + TENANT))
        .header(CommonConstants.BATCH_API_KEY_HEADER, rawApiKey)
        .header(CommonConstants.BATCH_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .GET()
        .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("Trigger external event status GET failed: %s", response.body())
        .isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private void configureImportPayload(LaunchSeed seed, String customerNo, String customerName)
      throws Exception {
    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params = ?::jsonb where tenant_id = ? and job_code = ?\
        """;

    String content = """
        [{"customerNo":"%s","customerName":"%s",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerName, customerNo);
    jdbcTemplate.update(
        updateJobDefinitionSql,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", content)),
        TENANT,
        seed.jobCode());
  }

  private void prepareCompositeImportWorkflow(LaunchSeed first, LaunchSeed second) {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
    """;
    final String deleteWorkflowNodeSql = """
        delete from batch.workflow_node where tenant_id = ? and workflow_definition_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id = ?
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set single_job_definition_id = null where tenant_id = ? and id = ?\
        """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order = 3 where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'END'\
        """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (tenant_id, workflow_definition_id, node_code, node_name, node_type,\s\
        related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled)\s\
        values (?, ?, 'JOB_B', 'Job B', 'JOB', ?, 2, 'NONE', 0, 0, true)\
        """;
    final String deleteWorkflowEdgeSql2 = """
        delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?\s\
        and from_node_code = 'JOB' and to_node_code = 'END'\
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (tenant_id, workflow_definition_id, from_node_code, to_node_code,\s\
        edge_type, enabled) values (?, ?, 'START', 'JOB_B', 'ALWAYS', true), (?, ?, 'JOB', 'END', 'SUCCESS',\s\
        true), (?, ?, 'JOB_B', 'END', 'SUCCESS', true)\
        """;

    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, second.requestId());
    jdbcTemplate.update(deleteWorkflowEdgeSql, TENANT, second.workflowDefinitionId());
    jdbcTemplate.update(deleteWorkflowNodeSql, TENANT, second.workflowDefinitionId());
    jdbcTemplate.update(deleteWorkflowDefinitionSql, TENANT, second.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowDefinitionSql, TENANT, first.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowNodeSql, TENANT, first.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, TENANT, first.workflowDefinitionId(), second.jobCode());
    jdbcTemplate.update(deleteWorkflowEdgeSql2, TENANT, first.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        TENANT,
        first.workflowDefinitionId(),
        TENANT,
        first.workflowDefinitionId(),
        TENANT,
        first.workflowDefinitionId());
  }

  private Long awaitWorkflowJobInstance(Long workflowRunId, String nodeCode) {
    final String selectJobInstanceIdSql = """
        select job_instance_id from batch.workflow_node_run where workflow_run_id = ? and node_code = ?\s\
        and job_instance_id is not null order by run_seq desc limit 1\
        """;

    return await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () -> jdbcTemplate
                .query(
                    selectJobInstanceIdSql, (rs, rowNum) -> rs.getLong(1), workflowRunId, nodeCode)
                .stream()
                .findFirst()
                .orElse(null),
            id -> id != null);
  }

  private Long awaitWorkflowJobInstanceForRequest(Long workflowDefinitionId, String requestId) {
    final String selectJobInstanceIdSql = """
        select nr.job_instance_id from batch.trigger_request tr\s\
        join batch.workflow_run wr on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id\s\
        join batch.workflow_node_run nr on nr.workflow_run_id = wr.id where tr.tenant_id = ?\s\
        and tr.workflow_definition_id = ? and tr.request_id = ? and nr.job_instance_id is not null\s\
        order by nr.run_seq desc limit 1\
        """;

    return await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () -> jdbcTemplate
                .query(
                    selectJobInstanceIdSql,
                    (rs, rowNum) -> rs.getLong(1),
                    TENANT,
                    workflowDefinitionId,
                    requestId)
                .stream()
                .findFirst()
                .orElse(null),
            id -> id != null);
  }

  private Long awaitWorkflowRunForRequest(String requestId) {
    final String selectIdSql = """
        select wr.id from batch.workflow_run wr join batch.trigger_request tr on tr.tenant_id = wr.tenant_id\s\
        and tr.id = wr.trigger_request_id where tr.tenant_id = ? and tr.request_id = ?\
        """;

    return await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () ->
                jdbcTemplate
                    .query(selectIdSql, (rs, rowNum) -> rs.getLong(1), TENANT, requestId)
                    .stream()
                    .findFirst()
                    .orElse(null),
            id -> id != null);
  }

  private void awaitScheduledOccurrenceRequested(Long workflowDefinitionId, Instant fireTime) {
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              workflowDefinitionId,
              Timestamp.from(fireTime));
          assertThat(selectedOccurrenceStatus).isEqualTo("REQUESTED");
        });
  }

  private String occurrenceStatus(Long workflowDefinitionId, Instant fireTime) {
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        workflowDefinitionId,
        Timestamp.from(fireTime));
  }

  @Test
  @DisplayName("真实 Quartz MANUAL_APPROVAL misfire 与批日结算竞争后仍经 Worker 完成")
  void manualMisfireApprovalRacingSettlement_completesWorkerBeforeBatchDaySettlement()
      throws Exception {
    final String lockCustomerAccountSql =
        "lock table biz.customer_account in share row exclusive mode";

    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;

    final String selectCountSql3 = """
            select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
            """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
            enabled) values (?, ?, 'Tokyo MANUAL_APPROVAL E2E', 'Asia/Tokyo', '06:00:00', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where tenant_id = ? and id = ?
        """;
    final String updateJobDefinitionSql = """
            update batch.job_definition set calendar_code = ? where tenant_id = ? and job_code = ?
        """;
    final String updateJobDefinitionSql2 = """
            update batch.job_definition set default_params = ?::jsonb where tenant_id = ? and job_code = ?\
            """;
    final String selectOccurrenceStatusSql = """
            select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
            and workflow_definition_id = ? and scheduled_fire_time = ?\
            """;
    final String selectBizDateSql = """
            select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time = ?\
            """;
    final String selectBizDateSql2 = """
            select biz_date from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectStatusSql = """
            select status from batch.trigger_misfire_pending where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time = ?\
            """;
    final String selectOccurrenceStatusSql2 = """
            select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
            and workflow_definition_id = ? and scheduled_fire_time = ?\
            """;
    final String selectStatusSql2 = """
            select status from batch.trigger_misfire_pending where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time = ?\
            """;
    final String selectRequestIdSql = """
            select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
            and request_id = ?\
            """;
    final String selectCountSql = """
            select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
            and request_id = ?)\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.workflow_node_run nr\s\
            join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
            and wr.workflow_definition_id = ? and wr.trigger_request_id = (select id from batch.trigger_request\s\
            where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB' and nr.job_instance_id is not null\
            """;
    final String selectJobInstanceIdSql = """
            select nr.job_instance_id from batch.workflow_node_run nr\s\
            join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
            and wr.workflow_definition_id = ? and wr.trigger_request_id = (select id from batch.trigger_request\s\
            where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB'\
            """;
    final String selectBizDateSql3 = """
            select wr.biz_date from batch.workflow_run wr\s\
            join batch.trigger_request tr on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id\s\
            where tr.tenant_id = ? and tr.request_id = ?\
            """;
    final String selectBizDateSql4 = """
            select biz_date from batch.job_instance where tenant_id = ? and id = ?
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String calendarCode = "e2e_tokyo_misfire_" + UUID.randomUUID().toString().replace("-", "");
    ZoneId businessZone = ZoneId.of("Asia/Tokyo");
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, seed.jobCode());
    String customerNo = "APPROVED_MISFIRE_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Approved misfire customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        updateJobDefinitionSql2,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());
    LocalDate bizDate = LocalDate.now(businessZone).minusDays(2);
    ManualMisfire misfire =
        createManualMisfire(seed, bizDate, calendarCode, businessZone, LocalTime.of(8, 29));
    Instant originalFireTime = misfire.fireTime();
    assertThat(originalFireTime.atZone(ZoneOffset.UTC).toLocalDate())
        .isEqualTo(bizDate.minusDays(1));
    String requestId = misfire.requestId();

    ExecutorService settlementExecutor = Executors.newSingleThreadExecutor();
    try (var workerFence = businessDataSource.getConnection()) {
      workerFence.setAutoCommit(false);
      try (var lock = workerFence.createStatement()) {
        lock.execute(lockCustomerAccountSql);
      }
      int workerBlockerPid;
      try (var pid = workerFence.prepareStatement(selectPgBackendPidSql)) {
        try (var result = pid.executeQuery()) {
          assertThat(result.next()).isTrue();
          workerBlockerPid = result.getInt(1);
        }
      }
      try (var settlementFence = jdbcTemplate.getDataSource().getConnection()) {
        settlementFence.setAutoCommit(false);
        try (var acquire =
            settlementFence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
          acquire.setString(1, TENANT);
          acquire.setString(2, calendarCode);
          acquire.setObject(3, bizDate);
          acquire.execute();
        }
        int settlementBlockerPid;
        try (var pid = settlementFence.prepareStatement(selectPgBackendPidSql)) {
          try (var result = pid.executeQuery()) {
            assertThat(result.next()).isTrue();
            settlementBlockerPid = result.getInt(1);
          }
        }
        Future<?> settlement = settlementExecutor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBySql(settlementBlockerPid, "acquire_batch_day_settlement_fence");

        JsonNode approvalSubmission = postConsoleJson(
            "/api/console/jobs/catch-up/approve",
            """
            {"tenantId":"t1","requestId":"%s","workflowDefinitionId":%d,
             "jobCode":"%s","bizDate":"%s","reason":"approve real Quartz misfire"}
            """.formatted(requestId, seed.workflowDefinitionId(), seed.jobCode(), bizDate),
            "manual-misfire-approval-submit-" + UUID.randomUUID());
        String approvalNo = approvalSubmission.path("data").asText();
        assertThat(approvalNo).isNotBlank();
        JsonNode approved = postConsoleJson(
            "/api/console/approvals/" + approvalNo + "/approve",
            """
            {"tenantId":"t1","operatorId":"e2e-approver","reason":"approved misfire"}
            """,
            "manual-misfire-approval-" + UUID.randomUUID());
        assertThat(approved.path("code").asText()).isIn("0", "SUCCESS", "OK");

        String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
            selectOccurrenceStatusSql,
            String.class,
            TENANT,
            seed.workflowDefinitionId(),
            Timestamp.from(originalFireTime));
        assertThat(selectedOccurrenceStatus).isEqualTo("REQUESTED");
        LocalDate selectedBizDate = jdbcTemplate.queryForObject(
            selectBizDateSql,
            LocalDate.class,
            TENANT,
            seed.workflowDefinitionId(),
            Timestamp.from(originalFireTime));
        assertThat(selectedBizDate).isEqualTo(bizDate);
        LocalDate selectedBizDateRead2 =
            jdbcTemplate.queryForObject(selectBizDateSql2, LocalDate.class, TENANT, requestId);
        assertThat(selectedBizDateRead2).isEqualTo(bizDate);
        String selectedStatus = jdbcTemplate.queryForObject(
            selectStatusSql,
            String.class,
            TENANT,
            seed.workflowDefinitionId(),
            Timestamp.from(originalFireTime));
        assertThat(selectedStatus).isEqualTo("APPROVED");

        settlementFence.commit();
        settlement.get(20, TimeUnit.SECONDS);
        awaitBlockedByRelation(workerBlockerPid, "customer_account");
        batchDaySettleScheduler.settle();
        assertThat(batchDayStatus(calendarCode, bizDate)).isEqualTo("IN_FLIGHT");
        workerFence.commit();
      }
    } finally {
      settlementExecutor.shutdownNow();
      assertThat(settlementExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedOccurrenceStatusRead2 = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql2,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(originalFireTime));
          assertThat(selectedOccurrenceStatusRead2).isEqualTo("REQUESTED");
          String selectedStatusRead2 = jdbcTemplate.queryForObject(
              selectStatusSql2,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(originalFireTime));
          assertThat(selectedStatusRead2).isEqualTo("APPROVED");
        });
    String launchRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId(), requestId);
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              TENANT,
              launchRequestId);
          assertThat(selectedWorkflowRunCount).isOne();
        });

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedWorkflowNodeRunCount = jdbcTemplate.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              TENANT,
              launchRequestId);
          assertThat(selectedWorkflowNodeRunCount).isOne();
        });
    Long jobInstanceId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql,
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        launchRequestId);
    LocalDate selectedBizDateRead3 =
        jdbcTemplate.queryForObject(selectBizDateSql3, LocalDate.class, TENANT, launchRequestId);
    assertThat(selectedBizDateRead3).isEqualTo(bizDate);
    LocalDate selectedBizDateRead4 =
        jdbcTemplate.queryForObject(selectBizDateSql4, LocalDate.class, TENANT, jobInstanceId);
    assertThat(selectedBizDateRead4).isEqualTo(bizDate);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          String selectedRunStatus = jdbcTemplate.queryForObject(
              selectRunStatusSql, String.class, TENANT, TENANT, launchRequestId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          Integer selectedCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql3, Integer.class, TENANT, customerNo);
          assertThat(selectedCount).isEqualTo(1);
        });
    batchDaySettleScheduler.settle();
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, bizDate);
    assertThat(selectedDayStatus).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("真实 Quartz MANUAL_APPROVAL misfire 被拒绝后终结 occurrence 并结算空日")
  void rejectedManualMisfire_closesOccurrenceBeforeEmptyDaySettlement() throws Exception {
    final String selectApprovalStatusSql = """
        select approval_status from batch.approval_command where tenant_id = ? and approval_no = ?\
        """;
    final String selectStatusSql = """
        select status from batch.trigger_misfire_pending where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ?\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ? and biz_date = ?\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LocalDate bizDate = LocalDate.now(ZoneOffset.UTC).minusDays(2);
    String calendarCode = "e2e_reject_" + UUID.randomUUID().toString().replace("-", "");
    isolateScenarioCalendar(calendarCode, seed);
    ManualMisfire misfire =
        createManualMisfire(seed, bizDate, calendarCode, ZoneOffset.UTC, LocalTime.of(9, 30));
    JsonNode submission = postConsoleJson(
        "/api/console/jobs/catch-up/approve",
        """
        {"tenantId":"t1","requestId":"%s","workflowDefinitionId":%d,
         "jobCode":"%s","bizDate":"%s","reason":"review real Quartz misfire"}
        """.formatted(misfire.requestId(), seed.workflowDefinitionId(), seed.jobCode(), bizDate),
        "manual-misfire-reject-submit-" + UUID.randomUUID());
    String approvalNo = submission.path("data").asText();
    assertThat(approvalNo).isNotBlank();

    JsonNode rejected = postConsoleJson(
        "/api/console/approvals/" + approvalNo + "/reject",
        """
        {"tenantId":"t1","operatorId":"e2e-reviewer","reason":"reject misfire"}
        """,
        "manual-misfire-reject-" + UUID.randomUUID());
    assertThat(rejected.path("code").asText()).isIn("0", "SUCCESS", "OK");
    String selectedApprovalStatus =
        jdbcTemplate.queryForObject(selectApprovalStatusSql, String.class, TENANT, approvalNo);
    assertThat(selectedApprovalStatus).isEqualTo("REJECTED");

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedStatus = jdbcTemplate.queryForObject(
              selectStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(misfire.fireTime()));
          assertThat(selectedStatus).isEqualTo("REJECTED");
          String selectedRequestStatus = jdbcTemplate.queryForObject(
              selectRequestStatusSql, String.class, TENANT, misfire.requestId());
          assertThat(selectedRequestStatus).isEqualTo("REJECTED");
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(misfire.fireTime()));
          assertThat(selectedOccurrenceStatus).isEqualTo("MISFIRE_SKIPPED");
        });

    batchDaySettleScheduler.settle();
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, bizDate);
    assertThat(selectedDayStatus).isEqualTo("SETTLED");
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId(), bizDate);
    assertThat(selectedWorkflowRunCount).isZero();
    Integer selectedJobInstanceCount = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, TENANT, seed.jobCode(), bizDate);
    assertThat(selectedJobInstanceCount).isZero();
  }

  @Test
  @DisplayName("真实 Quartz MANUAL_APPROVAL misfire 超期后终结 occurrence 并结算空日")
  void expiredManualMisfire_closesOccurrenceBeforeEmptyDaySettlement() throws Exception {
    final String updateTriggerMisfirePendingSql = """
        update batch.trigger_misfire_pending set expires_at = current_timestamp - interval '1 second'\s\
        where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ? and status = 'PENDING'\
        """;
    final String selectStatusSql = """
        select status from batch.trigger_misfire_pending where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'e2e_calendar'\s\
        and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ?\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LocalDate bizDate = LocalDate.now(ZoneOffset.UTC).minusDays(3);
    ManualMisfire misfire = createManualMisfire(seed, bizDate);
    int affectedTriggerMisfirePendingRows = jdbcTemplate.update(
        updateTriggerMisfirePendingSql,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(misfire.fireTime()));
    assertThat(affectedTriggerMisfirePendingRows).isEqualTo(1);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedStatus = jdbcTemplate.queryForObject(
              selectStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(misfire.fireTime()));
          assertThat(selectedStatus).isEqualTo("EXPIRED");
          String selectedRequestStatus = jdbcTemplate.queryForObject(
              selectRequestStatusSql, String.class, TENANT, misfire.requestId());
          assertThat(selectedRequestStatus).isEqualTo("REJECTED");
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(misfire.fireTime()));
          assertThat(selectedOccurrenceStatus).isEqualTo("MISFIRE_SKIPPED");
        });

    batchDaySettleScheduler.settle();
    String selectedDayStatus =
        jdbcTemplate.queryForObject(selectDayStatusSql, String.class, TENANT, bizDate);
    assertThat(selectedDayStatus).isEqualTo("SETTLED");
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId(), bizDate);
    assertThat(selectedWorkflowRunCount).isZero();
  }

  @Test
  @DisplayName("双 Quartz 节点竞争审批重启后的待审批义务且 JWT/CSRF Console 只产生唯一 Worker 结果")
  void shouldApproveRestartedMisfire_whenConsoleAuthenticated() throws Exception {
    final String selectCountSql10 = """
            select count(*) from biz.customer_account where customer_no = ?
        """;

    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params = ?::jsonb
        where tenant_id = ? and job_code = ?
        """;
    final String selectCountSql = """
              select count(*) from quartz.QRTZ_SCHEDULER_STATE
              where SCHED_NAME = ?
                and INSTANCE_NAME in ('trigger-node-1', 'trigger-node-2')
              """;
    final String selectCountSql2 = """
            select count(*) from batch.approval_command where tenant_id = ? and target_id = ?
        """;
    final String selectCountSql3 = """
            select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?
        """;
    final String selectCountSql4 = """
                select count(*) from batch.workflow_run wr
                join batch.trigger_request r on r.id = wr.trigger_request_id
                where r.tenant_id = ? and r.request_id = ? and wr.run_status = 'SUCCESS'
                """;
    final String selectCountSql5 = """
          select count(*) from batch.trigger_outbox_event
          where tenant_id = ? and request_id = ? and publish_status = 'PUBLISHED'
          """;
    final String selectCountSql6 = """
          select count(*) from batch.approval_command
          where tenant_id = ? and target_id = ? and approval_no = ?
          and approval_status = 'EXECUTED' and requester_id = ? and approver_id = 'recovery-approver'
          """;
    final String selectCountSql7 = """
          select count(*) from batch.trigger_misfire_pending
          where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?
          and status = 'APPROVED'
          """;
    final String selectCountSql8 = """
          select count(*) from batch.workflow_run wr
          join batch.trigger_request r on r.id = wr.trigger_request_id and r.tenant_id = wr.tenant_id
          join batch.workflow_node_run nr on nr.workflow_run_id = wr.id and nr.node_code = 'JOB'
          join batch.job_instance ji on ji.id = nr.job_instance_id and ji.tenant_id = wr.tenant_id
          where r.tenant_id = ? and r.request_id = ?
          and wr.biz_date = ? and ji.biz_date = ?
          and wr.run_status = 'SUCCESS' and nr.node_status = 'SUCCESS' and ji.instance_status = 'SUCCESS'
          """;
    final String selectCountSql9 = """
          select count(*) from batch.scheduled_occurrence
          where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?
          and biz_date = ? and request_id = ? and occurrence_status = 'REQUESTED'
          """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "AUTH_MISFIRE_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        updateJobDefinitionSql,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType",
            "JSON",
            "templateCode",
            "IMP-CUSTOMER-JSON-ARRAY",
            "bizType",
            "CUSTOMER",
            "content",
            """
            [{"customerNo":"%s","customerName":"Authenticated recovery",
              "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
            """.formatted(customerNo, customerNo))),
        TENANT,
        seed.jobCode());
    LocalDate bizDate = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    ManualMisfire misfire = createManualMisfire(seed, bizDate);
    Map<String, Object> pendingBeforeRestart = pendingMisfireSnapshot(seed, misfire);
    String schedulerName = triggerContext.getBean(Scheduler.class).getSchedulerName();
    if (triggerContext != null) {
      triggerContext.close();
      triggerContext = null;
    }
    assertThat(pendingMisfireSnapshot(seed, misfire)).isEqualTo(pendingBeforeRestart);

    // 此限制，锁断言才能按子进程 JDBC 连接实际使用的完整名称查询。
    // 单独创建生产 Console 上下文，确保从构建安全链开始就禁用绕过；
    Map<String, Object> properties = new HashMap<>();
    AbstractIntegrationTest.registerDynamicProperties(
        (name, supplier) -> properties.put(name, supplier.get()), false);
    properties.put("server.port", 0);
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("spring.flyway.enabled", false);
    properties.put(
        "spring.autoconfigure.exclude",
        String.join(
            ",",
            OpenAiChatAutoConfiguration.class.getName(),
            AnthropicChatAutoConfiguration.class.getName()));
    properties.put("batch.startup-self-check.enabled", false);
    properties.put("batch.console.security.single-session-enabled", false);
    properties.put("batch.console.security.default-tenant-id", TENANT);
    properties.put("batch.console.security.allowed-tenants", TENANT);
    properties.put("batch.security.internal-secret", E2E_INTERNAL_SECRET);
    properties.put("batch.console.orchestrator.base-url", "http://127.0.0.1:" + consolePort);
    properties.put("batch.console.trigger.base-url", "http://127.0.0.1:" + TRIGGER_PORT);
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    try (var separateTrigger = startSeparateTriggerProcess(TRIGGER_PORT, "trigger-node-1");
        var secondTrigger = startSeparateTriggerProcess(SECOND_TRIGGER_PORT, "trigger-node-2");
        var securedConsole = new SpringApplicationBuilder(BatchConsoleApiApplication.class)
            .profiles("test", "e2e")
            .run(arguments)) {
      await()
          .atMost(Duration.ofSeconds(40))
          .pollInterval(Duration.ofMillis(250))
          .untilAsserted(() -> {
            Integer selectedQrtzSchedulerStateCount =
                jdbcTemplate.queryForObject(selectCountSql, Integer.class, schedulerName);
            assertThat(selectedQrtzSchedulerStateCount).isEqualTo(2);
          });
      assertThat(securedConsole.getBean(BatchSecurityProperties.class).isBypassMode())
          .isFalse();
      int port = securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + port)
          .responseTimeout(Duration.ofSeconds(30))
          .build();
      ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
      client.get().uri("/api/console/auth/check").exchange().expectStatus().isUnauthorized();
      AuthenticatedConsole maker = authenticateConsole(client, jwtService, OPERATOR, "ROLE_ADMIN");
      AuthenticatedConsole auditor =
          authenticateConsole(client, jwtService, "recovery-auditor", "ROLE_AUDITOR");
      String submissionBody =
          """
          {"tenantId":"t1","requestId":"%s","workflowDefinitionId":%d,
           "jobCode":"%s","bizDate":"%s","reason":"authenticated Quartz recovery"}
          """.formatted(misfire.requestId(), seed.workflowDefinitionId(), seed.jobCode(), bizDate);
      String submissionPath = "/api/console/jobs/catch-up/approve";
      client
          .post()
          .uri(submissionPath)
          .cookie("XSRF-TOKEN", maker.csrf())
          .header("X-XSRF-TOKEN", maker.csrf())
          .header("X-Console-User", OPERATOR)
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "no-jwt-" + UUID.randomUUID())
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(submissionBody)
          .exchange()
          .expectStatus()
          .isUnauthorized();
      client
          .post()
          .uri(submissionPath)
          .cookie("batch_console_token", maker.jwt())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "no-csrf-" + UUID.randomUUID())
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(submissionBody)
          .exchange()
          .expectStatus()
          .isForbidden();
      authenticatedConsolePost(client, auditor, submissionPath, submissionBody)
          .expectStatus()
          .isForbidden();
      assertThat(pendingMisfireSnapshot(seed, misfire)).isEqualTo(pendingBeforeRestart);
      Integer selectedApprovalCommandCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, misfire.requestId());
      assertThat(selectedApprovalCommandCount).isZero();
      Integer selectedTriggerOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, misfire.requestId());
      assertThat(selectedTriggerOutboxEventCount).isZero();

      byte[] responseBody = authenticatedConsolePost(client, maker, submissionPath, submissionBody)
          .expectStatus()
          .is2xxSuccessful()
          .expectBody()
          .returnResult()
          .getResponseBody();
      assertThat(responseBody).isNotNull();
      JsonNode submission = objectMapper.readTree(responseBody);
      String approvalNo = submission.path("data").asText();
      assertThat(approvalNo).isNotBlank();
      AuthenticatedConsole approver =
          authenticateConsole(client, jwtService, "recovery-approver", "ROLE_ADMIN");
      installR26CatchUpCasPauseTrigger();
      ExecutorService approvalExecutor = Executors.newSingleThreadExecutor();
      Future<byte[]> consoleApproval;
      try {
        consoleApproval = approvalExecutor.submit(() -> authenticatedConsolePost(
                client, approver, "/api/console/approvals/" + approvalNo + "/approve", """
                {"tenantId":"t1","operatorId":"recovery-approver","reason":"approved recovery"}
                """)
            .expectStatus()
            .is2xxSuccessful()
            .expectBody()
            .returnResult()
            .getResponseBody());
        awaitR26CatchUpCasIsPaused();
        HttpResponse<String> concurrentDuplicate = postInternalCatchUpApproval(
            SECOND_TRIGGER_PORT, approvalNo, misfire.requestId(), "concurrent recovery retry");
        assertThat(concurrentDuplicate.statusCode())
            .as("the second clustered Trigger node accepts the same in-flight approval")
            .isEqualTo(200);
        JsonNode duplicateResponse = objectMapper.readTree(concurrentDuplicate.body());
        assertThat(duplicateResponse.path("data").path("instanceNo").asText())
            .isEqualTo(misfire.requestId());
        assertThat(consoleApproval.get(30, TimeUnit.SECONDS)).isNotNull();
      } finally {
        approvalExecutor.shutdownNow();
        approvalExecutor.awaitTermination(10, TimeUnit.SECONDS);
        removeR26CatchUpCasPauseTrigger();
      }
      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
                selectCountSql4, Integer.class, TENANT, misfire.requestId());
            assertThat(selectedWorkflowRunCount).isOne();
            Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
                .queryForObject(selectCountSql10, Integer.class, customerNo);
            assertThat(selectedCustomerAccountCount).isOne();
          });
      Integer selectedTriggerOutboxEventCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql5, Integer.class, TENANT, misfire.requestId());
      assertThat(selectedTriggerOutboxEventCountRead2).isOne();
      Integer selectedApprovalCommandCountRead2 = jdbcTemplate.queryForObject(
          selectCountSql6, Integer.class, TENANT, misfire.requestId(), approvalNo, OPERATOR);
      assertThat(selectedApprovalCommandCountRead2).isOne();
      Integer selectedTriggerMisfirePendingCount = jdbcTemplate.queryForObject(
          selectCountSql7,
          Integer.class,
          TENANT,
          seed.workflowDefinitionId(),
          Timestamp.from(misfire.fireTime()));
      assertThat(selectedTriggerMisfirePendingCount).isOne();
      Integer selectedWorkflowRunCountRead2 = jdbcTemplate.queryForObject(
          selectCountSql8, Integer.class, TENANT, misfire.requestId(), bizDate, bizDate);
      assertThat(selectedWorkflowRunCountRead2).isOne();
      Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
          selectCountSql9,
          Integer.class,
          TENANT,
          seed.workflowDefinitionId(),
          Timestamp.from(misfire.fireTime()),
          bizDate,
          misfire.requestId());
      assertThat(selectedScheduledOccurrenceCount).isOne();
      assertThat(separateTrigger.process().isAlive())
          .as("the first Trigger JVM remains alive through authenticated recovery")
          .isTrue();
      assertThat(secondTrigger.process().isAlive())
          .as("the second Trigger JVM remains alive through authenticated recovery")
          .isTrue();
    }
  }

  private SeparateTriggerProcess startSeparateTriggerProcess(int port, String instanceId)
      throws Exception {
    Path log = Files.createTempFile("r26-trigger-process-", ".log");
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    assertThat(classpath).isNotBlank();
    Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            E2eTriggerApplication.class.getName(),
            "--spring.profiles.active=test,e2e",
            "--spring.main.web-application-type=servlet",
            "--server.port=" + port,
            "--spring.datasource.url=" + platformJdbcUrl(),
            "--spring.datasource.username=" + platformJdbcUsername(),
            "--spring.datasource.password=" + platformJdbcPassword(),
            "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
            "--spring.flyway.enabled=false",
            "--spring.quartz.job-store-type=jdbc",
            "--spring.quartz.jdbc.initialize-schema=never",
            "--spring.quartz.auto-startup=true",
            "--spring.quartz.properties.org.quartz.scheduler.instanceId=" + instanceId,
            "--spring.quartz.properties.org.quartz.jobStore.class=org.quartz.impl.jdbcjobstore.JobStoreTX",
            "--spring.quartz.properties.org.quartz.jobStore.dataSource=quartzJobstoreDs",
            "--spring.quartz.properties.org.quartz.jobStore.tablePrefix=quartz.QRTZ_",
            "--spring.quartz.properties.org.quartz.jobStore.driverDelegateClass=org.quartz.impl.jdbcjobstore.PostgreSQLDelegate",
            "--spring.quartz.properties.org.quartz.jobStore.isClustered=true",
            "--spring.quartz.properties.org.quartz.jobStore.misfireThreshold=1000",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.driver=org.postgresql.Driver",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.provider=hikaricp",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.URL="
                + platformJdbcUrl(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.user="
                + platformJdbcUsername(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.password="
                + platformJdbcPassword(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.maxConnections=5",
            "--batch.startup-self-check.enabled=false",
            "--batch.security.bypass-mode=false",
            "--batch.security.internal-secret=" + E2E_INTERNAL_SECRET,
            "--batch.orchestrator.base-url=http://127.0.0.1:" + consolePort,
            "--batch.trigger.outbox.poll-interval-millis=200",
            "--batch.trigger.misfire-pending-reconcile-interval-millis=200",
            "--batch.trigger.kafka.send-timeout-seconds=5")
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
    SeparateTriggerProcess handle = new SeparateTriggerProcess(process, log);
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .until(() ->
            Files.readString(log).contains("Started E2eTriggerApplication") || !process.isAlive());
    assertThat(process.isAlive())
        .as("independent Trigger JVM %s starts; log: %s", instanceId, Files.readString(log))
        .isTrue();
    return handle;
  }

  private record SeparateTriggerProcess(Process process, Path log) implements AutoCloseable {
    @Override
    public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      assertThat(process.isAlive())
          .as("independent Trigger JVM exits cleanly; log: %s", Files.readString(log))
          .isFalse();
    }
  }

  private void installR26CatchUpCasPauseTrigger() {
    final String dropTriggerSql = """
        drop trigger if exists r26_pause_catch_up_cas on batch.trigger_request
    """;
    final String dropFunctionSql = """
        drop function if exists batch.r26_pause_catch_up_cas()
    """;
    final String createFunctionSql = """
        create function batch.r26_pause_catch_up_cas() returns trigger
        language plpgsql as $$
        begin
          if old.request_status = 'ACCEPTED' and new.request_status = 'PROCESSING' then
            perform pg_sleep(3);
          end if;
          return new;
        end;
        $$
        """;
    final String createTriggerSql = """
        create trigger r26_pause_catch_up_cas
        before update of request_status on batch.trigger_request
        for each row execute function batch.r26_pause_catch_up_cas()
        """;

    jdbcTemplate.execute(dropTriggerSql);
    jdbcTemplate.execute(dropFunctionSql);
    jdbcTemplate.execute(createFunctionSql);
    jdbcTemplate.execute(createTriggerSql);
  }

  private void awaitR26CatchUpCasIsPaused() {
    final String selectCountSql = """
            select count(*) from pg_stat_activity
            where datname = current_database()
              and state = 'active'
              and wait_event = 'PgSleep'
              and query ilike '%update batch.trigger_request%'
            """;

    await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
      Integer selectedPgStatActivityCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class);
      assertThat(selectedPgStatActivityCount).isGreaterThanOrEqualTo(1);
    });
  }

  private void removeR26CatchUpCasPauseTrigger() {
    final String dropTriggerSql = """
        drop trigger if exists r26_pause_catch_up_cas on batch.trigger_request
    """;
    final String dropFunctionSql = """
        drop function if exists batch.r26_pause_catch_up_cas()
    """;

    jdbcTemplate.execute(dropTriggerSql);
    jdbcTemplate.execute(dropFunctionSql);
  }

  private HttpResponse<String> postInternalCatchUpApproval(
      int port, String approvalNo, String requestId, String reason) throws Exception {
    String body = objectMapper.writeValueAsString(
        Map.of("tenantId", TENANT, "requestId", requestId, "reason", reason));
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + "/api/triggers/catch-up/approve"))
        .header(CommonConstants.INTERNAL_SECRET_HEADER, E2E_INTERNAL_SECRET)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, approvalNo)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> pendingMisfireSnapshot(LaunchSeed seed, ManualMisfire misfire) {
    final String selectIdSql = """
        select p.id, p.catch_up_request_id, p.scheduled_fire_time, r.biz_date, p.expires_at,
               p.status, r.request_status, md5(r.launch_payload::text) as payload_hash
        from batch.trigger_misfire_pending p
        join batch.trigger_request r on r.id = p.catch_up_request_id and r.tenant_id = p.tenant_id
        where p.tenant_id = ? and p.workflow_definition_id = ? and p.scheduled_fire_time = ?
        """;

    return jdbcTemplate.queryForMap(
        selectIdSql, TENANT, seed.workflowDefinitionId(), Timestamp.from(misfire.fireTime()));
  }

  @Test
  @DisplayName("真实 JWT/CSRF 空日结算拒绝越权且取证 HTTP 返回可信操作人与前后状态")
  void shouldExposeAuthoritativeAudit_whenEmptyDaySettled(@TempDir Path exportDirectory)
      throws Exception {
    final String insertCalendarSql = """
        insert into batch.business_calendar
            (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, ?, 'Authenticated empty day', 'UTC', false)
        """;
    final String selectAuditCountSql = """
        select count(*) from batch.batch_day_operation_audit
        where tenant_id = ? and calendar_code = ? and biz_date = ?
        """;
    String calendarCode = "auth_empty_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate bizDate = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    jdbcTemplate.update(insertCalendarSql, TENANT, calendarCode);
    insertCutoffBatchDay(calendarCode, bizDate);
    Map<String, Object> properties = new HashMap<>();
    AbstractIntegrationTest.registerDynamicProperties(
        (name, supplier) -> properties.put(name, supplier.get()), false);
    properties.put("server.port", 0);
    properties.put("server.address", "127.0.0.1");
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("spring.flyway.enabled", false);
    String excludedAutoConfigurations = String.join(
        ",",
        OpenAiChatAutoConfiguration.class.getName(),
        AnthropicChatAutoConfiguration.class.getName());
    properties.put("spring.autoconfigure.exclude", excludedAutoConfigurations);
    properties.put("batch.startup-self-check.enabled", false);
    properties.put("batch.security.bypass-mode", false);
    properties.put("batch.console.security.single-session-enabled", false);
    properties.put("batch.console.security.default-tenant-id", TENANT);
    properties.put("batch.console.security.allowed-tenants", "t1,t2");
    properties.put("batch.security.internal-secret", E2E_INTERNAL_SECRET);
    properties.put("batch.console.orchestrator.base-url", "http://127.0.0.1:" + consolePort);
    properties.put("batch.console.trigger.base-url", "http://127.0.0.1:" + TRIGGER_PORT);
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    String previousStorageDir = forensicExportProperties.getStorageDir();
    String ownedExportId = null;
    forensicExportProperties.setStorageDir(exportDirectory.toString());
    try (var securedConsole = new SpringApplicationBuilder(BatchConsoleApiApplication.class)
        .profiles("test", "e2e")
        .run(arguments)) {
      BatchSecurityProperties security = securedConsole.getBean(BatchSecurityProperties.class);
      assertThat(security.isBypassMode()).isFalse();
      int port = securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + port)
          .responseTimeout(Duration.ofSeconds(30))
          .build();
      ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
      AuthenticatedConsole admin =
          authenticateConsole(client, jwtService, "settlement-admin", "ROLE_ADMIN");
      AuthenticatedConsole auditor =
          authenticateConsole(client, jwtService, "settlement-auditor", "ROLE_AUDITOR");
      AuthenticatedConsole tenantAdmin =
          authenticateConsole(client, jwtService, "settlement-tenant-admin", "ROLE_TENANT_ADMIN");
      String operatePath = "/api/console/batch-days/operate";
      String reason = "Operator confirmed no expected work after source reconciliation";
      String requestBody = objectMapper.writeValueAsString(Map.of(
          "tenantId",
          TENANT,
          "calendarCode",
          calendarCode,
          "bizDate",
          bizDate.toString(),
          "action",
          "SETTLE_EMPTY",
          "operatorId",
          "forged-operator",
          "reason",
          reason));
      client
          .post()
          .uri(operatePath)
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(requestBody)
          .exchange()
          .expectStatus()
          .isUnauthorized();
      client
          .post()
          .uri(operatePath)
          .cookie("batch_console_token", admin.jwt())
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(requestBody)
          .exchange()
          .expectStatus()
          .isForbidden();
      authenticatedConsolePost(client, auditor, operatePath, requestBody)
          .expectStatus()
          .isForbidden();
      String otherTenantBody = requestBody.replace("\"tenantId\":\"t1\"", "\"tenantId\":\"t2\"");
      authenticatedConsolePost(client, tenantAdmin, operatePath, otherTenantBody)
          .expectStatus()
          .isForbidden();
      // 此查询允许租户管理员，确保另行经过租户边界而非只由写接口角色拒绝。
      client
          .get()
          .uri("/api/console/batch-days/catch-up-preview?tenantId=t2&calendarCode=" + calendarCode)
          .cookie("batch_console_token", tenantAdmin.jwt())
          .exchange()
          .expectStatus()
          .isForbidden();
      Integer deniedAuditCount = jdbcTemplate.queryForObject(
          selectAuditCountSql, Integer.class, TENANT, calendarCode, bizDate);
      String deniedDayStatus = batchDayStatus(calendarCode, bizDate);
      assertThat(deniedAuditCount).isZero();
      assertThat(deniedDayStatus).isEqualTo("CUTOFF");

      authenticatedConsolePost(client, admin, operatePath, requestBody)
          .expectStatus()
          .isOk();
      String settledDayStatus = batchDayStatus(calendarCode, bizDate);
      Integer settledAuditCount = jdbcTemplate.queryForObject(
          selectAuditCountSql, Integer.class, TENANT, calendarCode, bizDate);
      assertThat(settledDayStatus).isEqualTo("SETTLED");
      assertThat(settledAuditCount).isEqualTo(1);
      String exportBody = objectMapper.writeValueAsString(Map.of(
          "tenantId",
          TENANT,
          "bizDateFrom",
          bizDate.toString(),
          "bizDateTo",
          bizDate.toString(),
          "exportFormat",
          "BUNDLE",
          "requestedBy",
          "forged-exporter"));
      String exportResponse = authenticatedConsolePost(
              client, admin, "/api/console/forensic/export", exportBody)
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode exportResult = objectMapper.readTree(exportResponse).path("data");
      String exportId = exportResult.path("exportId").asText();
      ownedExportId = exportId;
      assertThat(exportId).isNotBlank();
      String downloadPath =
          "/api/console/forensic/export/" + exportId + "/download?tenantId=" + TENANT;
      byte[] bundle = client
          .get()
          .uri(downloadPath)
          .cookie("batch_console_token", admin.jwt())
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(byte[].class)
          .returnResult()
          .getResponseBody();
      assertThat(bundle).isNotEmpty();
      Map<String, JsonNode> entries = new HashMap<>();
      try (var zip = new ZipInputStream(new ByteArrayInputStream(bundle), StandardCharsets.UTF_8)) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          byte[] content = zip.readAllBytes();
          JsonNode value = objectMapper.readTree(content);
          entries.put(entry.getName(), value);
        }
      }
      JsonNode manifest = entries.get("manifest.json");
      assertThat(manifest).isNotNull();
      assertThat(manifest.path("tenantId").asText()).isEqualTo(TENANT);
      assertThat(manifest.path("requestedBy").asText()).isEqualTo("settlement-admin");
      JsonNode audits = entries.get("batch-day-operation-audits.json");
      assertThat(audits).isNotNull();
      List<JsonNode> matchingAudits = new ArrayList<>();
      for (JsonNode audit : audits) {
        if (calendarCode.equals(audit.path("calendarCode").asText())) {
          matchingAudits.add(audit);
        }
      }
      assertThat(matchingAudits).hasSize(1);
      JsonNode audit = matchingAudits.getFirst();
      assertThat(audit.path("tenantId").asText()).isEqualTo(TENANT);
      assertThat(audit.path("bizDate").asText()).isEqualTo(bizDate.toString());
      assertThat(audit.path("operationType").asText()).isEqualTo("BATCH_DAY_SETTLE_EMPTY");
      assertThat(audit.path("operatorId").asText()).isEqualTo("settlement-admin");
      assertThat(audit.path("fromStatus").asText()).isEqualTo("CUTOFF");
      assertThat(audit.path("toStatus").asText()).isEqualTo("SETTLED");
      assertThat(audit.path("reasonCode").asText()).isEqualTo("EMPTY_DAY_CONFIRMED");
      assertThat(audit.path("comment").asText()).isEqualTo(reason);
    } finally {
      forensicExportProperties.setStorageDir(previousStorageDir);
      cleanupAuthenticatedEmptyDay(calendarCode, bizDate, ownedExportId);
    }
  }

  private void cleanupAuthenticatedEmptyDay(
      String calendarCode, LocalDate bizDate, String exportId) {
    final String deleteExportSql = """
        delete from batch.forensic_export_log where tenant_id = ? and export_id = ?
        """;
    final String deleteDayAuditSql = """
        delete from batch.batch_day_operation_audit
        where tenant_id = ? and calendar_code = ? and biz_date = ?
        """;
    final String deleteExecutionAuditSql = """
        delete from batch.job_execution_log
        where tenant_id = ? and detail_ref = 'batch_day_instance'
          and extra_json ->> 'calendarCode' = ? and extra_json ->> 'bizDate' = ?
        """;
    final String deleteDaySql = """
        delete from batch.batch_day_instance
        where tenant_id = ? and calendar_code = ? and biz_date = ?
        """;
    final String deleteCalendarSql = """
        delete from batch.business_calendar where tenant_id = ? and calendar_code = ?
        """;
    jdbcTemplate.update(deleteExportSql, TENANT, exportId);
    jdbcTemplate.update(deleteDayAuditSql, TENANT, calendarCode, bizDate);
    jdbcTemplate.update(deleteExecutionAuditSql, TENANT, calendarCode, bizDate.toString());
    jdbcTemplate.update(deleteDaySql, TENANT, calendarCode, bizDate);
    jdbcTemplate.update(deleteCalendarSql, TENANT, calendarCode);
  }

  private AuthenticatedConsole authenticateConsole(
      WebTestClient client, ConsoleJwtService jwtService, String username, String role) {
    String jwt = jwtService.issueToken(username, TENANT, Set.of(role)).accessToken();
    var cookie = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(cookie).isNotNull();
    return new AuthenticatedConsole(jwt, cookie.getValue());
  }

  private WebTestClient.ResponseSpec authenticatedConsolePost(
      WebTestClient client, AuthenticatedConsole credentials, String path, String body) {
    return client
        .post()
        .uri(path)
        .cookie("batch_console_token", credentials.jwt())
        .cookie("XSRF-TOKEN", credentials.csrf())
        .header("X-XSRF-TOKEN", credentials.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "auth-recovery-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private record AuthenticatedConsole(String jwt, String csrf) {}

  private ManualMisfire createManualMisfire(LaunchSeed seed, LocalDate bizDate) throws Exception {
    return createManualMisfire(seed, bizDate, "e2e_calendar", ZoneOffset.UTC, LocalTime.of(9, 29));
  }

  private ManualMisfire createManualMisfire(
      LaunchSeed seed,
      LocalDate bizDate,
      String calendarCode,
      ZoneId businessZone,
      LocalTime scheduledLocalTime)
      throws Exception {
    final String updateBusinessCalendarSql = """
            update batch.business_calendar set catch_up_policy = 'MANUAL_APPROVAL', catch_up_max_days = 3\s\
            where tenant_id = ? and calendar_code = ?\
            """;
    final String insertBatchDayInstanceSql = """
            insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
            cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp)\s\
            on conflict (tenant_id, calendar_code, biz_date) do update\s\
            set day_status = 'CUTOFF', cutoff_at = current_timestamp\
            """;
    final String updateTenantConfigurationRevisionSql = """
            update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
        """;
    final String selectOccurrenceStatusSql = """
            select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
            and workflow_definition_id = ? and scheduled_fire_time = ?\
            """;
    final String selectBizDateSql = """
            select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
            and scheduled_fire_time = ?\
            """;
    final String selectOccurrenceStatusSql2 = """
            select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
            and workflow_definition_id = ? and scheduled_fire_time = ?\
            """;
    final String selectCountSql = """
            select count(*) from batch.trigger_misfire_pending p\s\
            join batch.trigger_request r on r.id = p.catch_up_request_id where p.tenant_id = ?\s\
            and p.workflow_definition_id = ? and p.scheduled_fire_time = ? and p.status = 'PENDING'\s\
            and r.request_status = 'ACCEPTED'\
            """;
    final String selectRequestIdSql = """
            select r.request_id from batch.trigger_misfire_pending p\s\
            join batch.trigger_request r on r.id = p.catch_up_request_id where p.tenant_id = ?\s\
            and p.workflow_definition_id = ? and p.scheduled_fire_time = ?\
            """;

    jdbcTemplate.update(updateBusinessCalendarSql, TENANT, calendarCode);
    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
    scheduler.start();
    scheduler.standby();
    Instant startAt = bizDate.atTime(scheduledLocalTime).atZone(businessZone).toInstant();
    String quartzIdentity = TENANT + ":WF:" + seed.jobCode();
    JobKey jobKey = JobKey.jobKey(quartzIdentity, TriggerSchedulerFacade.JOB_GROUP);
    TriggerKey triggerKey = TriggerKey.triggerKey(quartzIdentity, TriggerSchedulerFacade.JOB_GROUP);
    JobDataMap jobData = new JobDataMap();
    jobData.put(QuartzLaunchJob.TENANT_ID, TENANT);
    jobData.put(QuartzLaunchJob.JOB_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    jobData.put(QuartzLaunchJob.WORKFLOW_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobData.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 1);
    jobData.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobData.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 * * * * ?");
    jobData.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobData.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobData.put(QuartzLaunchJob.CALENDAR_CODE, calendarCode);
    jobData.put(QuartzLaunchJob.CATCH_UP_POLICY, "MANUAL_APPROVAL");
    jobData.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(jobData)
        .storeDurably()
        .build();
    JobDataMap triggerIdentity = new JobDataMap();
    triggerIdentity.put(QuartzLaunchJob.TENANT_ID, TENANT);
    triggerIdentity.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    String scheduleExpression = "0 * * * * ?";
    CronTrigger cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(triggerKey)
        .forJob(job)
        .usingJobData(triggerIdentity)
        .startAt(Date.from(startAt))
        .withSchedule(CronScheduleBuilder.cronSchedule(scheduleExpression)
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
    Date originalFireTime = cronTrigger.getFireTimeAfter(Date.from(startAt.minusMillis(1)));
    cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(triggerKey)
        .forJob(job)
        .usingJobData(triggerIdentity)
        .startAt(Date.from(startAt))
        .endAt(originalFireTime)
        .withSchedule(CronScheduleBuilder.cronSchedule(scheduleExpression)
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
    assertThat(originalFireTime).isBefore(Date.from(Instant.now().minusSeconds(60)));
    assertThat(originalFireTime.toInstant().atZone(businessZone).toLocalDate()).isEqualTo(bizDate);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, bizDate);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(TENANT);
    descriptor.setWorkflowDefinitionId(seed.workflowDefinitionId());
    descriptor.setWorkflowCode(seed.jobCode());
    descriptor.setWorkflowVersion(1);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression(scheduleExpression);
    descriptor.setTimezone("UTC");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCalendarCode(calendarCode);
    descriptor.setEnabled(true);
    descriptor.setWorkflowEnabled(true);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql,
        Timestamp.from(startAt.minus(Duration.ofDays(1))),
        TENANT);
    var plannedFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(cronTrigger);
    int materialized = triggerContext
        .getBean(TriggerScheduledOccurrencePlanner.class)
        .materialize(descriptor, plannedFireTimes, originalFireTime.toInstant());
    assertThat(materialized).isEqualTo(1);
    String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(originalFireTime.toInstant()));
    assertThat(selectedOccurrenceStatus).isEqualTo("PLANNED");
    LocalDate selectedBizDate = jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(originalFireTime.toInstant()));
    assertThat(selectedBizDate).isEqualTo(bizDate);
    scheduler.scheduleJob(job, cronTrigger);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedOccurrenceStatusRead2 = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql2,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(originalFireTime.toInstant()));
          assertThat(selectedOccurrenceStatusRead2).isEqualTo("MISFIRE_PENDING");
        });
    scheduler.start();
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedTriggerMisfirePendingCount = jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(originalFireTime.toInstant()));
          assertThat(selectedTriggerMisfirePendingCount).isOne();
        });
    String requestId = jdbcTemplate.queryForObject(
        selectRequestIdSql,
        String.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(originalFireTime.toInstant()));
    return new ManualMisfire(originalFireTime.toInstant(), requestId);
  }

  private record ManualMisfire(Instant fireTime, String requestId) {}

  private record SettlementExecution(
      SettlementExecutionMode mode,
      Future<?> localFuture,
      Process childProcess,
      Path configPath,
      Path logPath,
      String applicationName,
      String calendarCode,
      Path readyPath,
      Path releasePath) {}

  @Test
  @DisplayName("Workflow 完整停用后真实 Quartz AUTO misfire 仍恢复并完成 Worker 业务写入")
  void disabledWorkflow_autoMisfireReconcilesToWorkerBusinessSuccess() throws Exception {
    final String selectCountSql3 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
        """;

    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do update set status = 'ACTIVE'\
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params = ?::jsonb where tenant_id = ? and job_code = ?\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set catch_up_policy = 'AUTO', catch_up_max_days = 3 where tenant_id = ?\s\
        and calendar_code = 'e2e_calendar'\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition\s\
        set schedule_type = 'CRON', schedule_expr = '0 * * * * ?', timezone = 'UTC', trigger_mode = 'SCHEDULED',\s\
        schedule_enabled = true where tenant_id = ? and id = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, 'e2e_calendar', ?, ?, 'PLANNED')\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectMisfireDetectedAtSql = """
        select misfire_detected_at is not null from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectEnabledSql = """
        select enabled from batch.workflow_definition where tenant_id = ? and id = ?
    """;
    final String selectOccurrenceStatusSql2 = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'CATCH_UP'\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'CATCH_UP'\
        """;
    final String selectCountSql2 = """
                select count(*) from batch.workflow_node_run nr
                join batch.workflow_run wr on wr.id = nr.workflow_run_id
                where wr.tenant_id = ? and wr.workflow_definition_id = ?
                and wr.trigger_request_id = (select id from batch.trigger_request
                where tenant_id = ? and request_id = ?)
                and nr.node_code = 'JOB' and nr.job_instance_id is not null
                """;
    final String selectJobInstanceIdSql = """
        select nr.job_instance_id from batch.workflow_node_run nr
        join batch.workflow_run wr on wr.id = nr.workflow_run_id
        where wr.tenant_id = ? and wr.workflow_definition_id = ?
        and wr.trigger_request_id = (select id from batch.trigger_request
        where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB'
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.trigger_request where tenant_id = ? and request_id = ?
    """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    jdbcTemplate.update(insertTenantSql, TENANT, "disabled Workflow misfire E2E");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "DISABLED_MISFIRE_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Disabled misfire customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        updateJobDefinitionSql,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());
    jdbcTemplate.update(updateBusinessCalendarSql, TENANT);
    jdbcTemplate.update(updateWorkflowDefinitionSql, TENANT, seed.workflowDefinitionId());

    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence())
        .as("this scenario requires the production-style JDBC Quartz JobStore")
        .isTrue();
    // 不要切换共享 Worker/Orchestrator 上下文的安全模式。
    // 启动 Quartz，使其 JDBC misfire 处理线程运行，然后暂停作业执行，同时
    scheduler.start();
    scheduler.standby();
    Instant startAt = Instant.now().minus(Duration.ofMinutes(4)).truncatedTo(ChronoUnit.MINUTES);
    JobKey primaryJobKey =
        JobKey.jobKey(TENANT + ":WF:" + seed.jobCode(), TriggerSchedulerFacade.JOB_GROUP);
    TriggerKey primaryTriggerKey =
        TriggerKey.triggerKey(primaryJobKey.getName(), primaryJobKey.getGroup());
    JobDataMap jobData = new JobDataMap();
    jobData.put(QuartzLaunchJob.TENANT_ID, TENANT);
    jobData.put(QuartzLaunchJob.JOB_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    jobData.put(QuartzLaunchJob.WORKFLOW_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobData.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 1);
    jobData.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobData.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 * * * * ?");
    jobData.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobData.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobData.put(QuartzLaunchJob.CALENDAR_CODE, "e2e_calendar");
    jobData.put(QuartzLaunchJob.CATCH_UP_POLICY, "AUTO");
    jobData.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(primaryJobKey)
        .usingJobData(jobData)
        .storeDurably()
        .build();
    JobDataMap triggerIdentity = new JobDataMap();
    triggerIdentity.put(QuartzLaunchJob.TENANT_ID, TENANT);
    triggerIdentity.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    CronTrigger cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(primaryTriggerKey)
        .forJob(job)
        .usingJobData(triggerIdentity)
        .startAt(Date.from(startAt))
        .withSchedule(CronScheduleBuilder.cronSchedule("0 * * * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
    Date originalFireTime = cronTrigger.getFireTimeAfter(Date.from(startAt.minusMillis(1)));
    assertThat(originalFireTime)
        .as("the JDBC JobStore must expose an overdue original cron fire")
        .isBefore(Date.from(Instant.now().minusSeconds(60)));
    LocalDate bizDate = originalFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        seed.workflowDefinitionId(),
        seed.jobCode(),
        Timestamp.from(originalFireTime.toInstant()),
        bizDate);
    scheduler.scheduleJob(job, cronTrigger);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(originalFireTime.toInstant()));
          assertThat(selectedOccurrenceStatus).isEqualTo("MISFIRE_PENDING");
        });
    Boolean selectedMisfireDetectedAt = jdbcTemplate.queryForObject(
        selectMisfireDetectedAtSql,
        Boolean.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(originalFireTime.toInstant()));
    assertThat(selectedMisfireDetectedAt).isTrue();

    JsonNode disabled = patchConsoleJson(
        "/api/console/workflow-definitions/" + seed.workflowDefinitionId(),
        "{\"tenantId\":\"t1\",\"enabled\":false}",
        "disable-before-misfire-recovery-" + UUID.randomUUID());
    assertThat(disabled.path("code").asText()).isIn("0", "SUCCESS", "OK");
    Boolean selectedEnabled = jdbcTemplate.queryForObject(
        selectEnabledSql, Boolean.class, TENANT, seed.workflowDefinitionId());
    assertThat(selectedEnabled).isFalse();

    AopTestUtils.<TriggerReconciler>getTargetObject(triggerContext.getBean(TriggerReconciler.class))
        .reconcile();
    JobKey recoveryJobKey = JobKey.jobKey(
        TENANT + ":RECOVERY:" + seed.workflowDefinitionId(), TriggerSchedulerFacade.JOB_GROUP);
    assertThat(scheduler.checkExists(primaryJobKey))
        .as("disabling a Workflow removes its ordinary Quartz schedule")
        .isFalse();
    assertThat(scheduler.checkExists(recoveryJobKey))
        .as("the reconciler retains a durable JobDetail for the pending fire")
        .isTrue();
    TriggerKey recoveryTriggerKey = TriggerKey.triggerKey(
        "misfire-recovery-" + seed.workflowDefinitionId() + "-"
            + originalFireTime.toInstant().toEpochMilli(),
        "batch-trigger-recovery");
    assertThat(scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals("batch-trigger-recovery")))
        .containsExactly(recoveryTriggerKey);
    assertThat(scheduler.getTrigger(recoveryTriggerKey).getJobKey()).isEqualTo(recoveryJobKey);

    scheduler.start();
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedOccurrenceStatusRead2 = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql2,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(originalFireTime.toInstant()));
          assertThat(selectedOccurrenceStatusRead2).isEqualTo("REQUESTED");
          Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
              selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId());
          assertThat(selectedTriggerRequestCount).isEqualTo(1);
        });
    String recoveryRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId());
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer selectedWorkflowNodeRunCount = jdbcTemplate.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              TENANT,
              recoveryRequestId);
          assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
        });
    Long jobInstanceId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql,
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        recoveryRequestId);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          String selectedRunStatus = jdbcTemplate.queryForObject(
              selectRunStatusSql, String.class, TENANT, TENANT, recoveryRequestId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          Integer selectedCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql3, Integer.class, TENANT, customerNo);
          assertThat(selectedCount).isEqualTo(1);
        });
    LocalDate selectedBizDate =
        jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, TENANT, recoveryRequestId);
    assertThat(selectedBizDate).isEqualTo(bizDate);
    Boolean selectedEnabledRead2 = jdbcTemplate.queryForObject(
        selectEnabledSql, Boolean.class, TENANT, seed.workflowDefinitionId());
    assertThat(selectedEnabledRead2).isFalse();
    assertThat(scheduler.getTrigger(primaryTriggerKey)).isNull();
  }

  @Test
  @DisplayName("两个真实 Quartz misfire callback 同一 fire 经 Kafka 到达唯一 RootRun 和业务行")
  void duplicateQuartzMisfireCallbacks_createOneRootRunAndBusinessEffect() throws Exception {
    final String selectCountSql5 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
        """;

    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params = ?::jsonb where tenant_id = ? and job_code = ?\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set catch_up_policy = 'AUTO', catch_up_max_days = 3 where tenant_id = ?\s\
        and calendar_code = 'e2e_calendar'\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition\s\
        set schedule_type = 'CRON', schedule_expr = '0 * * * * ?', timezone = 'UTC', trigger_mode = 'SCHEDULED',\s\
        schedule_enabled = true where tenant_id = ? and id = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, 'e2e_calendar', ?, ?, 'PLANNED')\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'CATCH_UP'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.trigger_outbox_event event
        join batch.trigger_request request using (tenant_id, request_id)
        where request.tenant_id = ? and request.workflow_definition_id = ?
          and request.trigger_type = 'CATCH_UP'
    """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'CATCH_UP'\
        """;
    final String selectIdSql = """
        select id from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_request_id = ?\
        """;
    final String selectCountSql4 = """
        select count(*) from batch.workflow_node_run nr join batch.workflow_run wr on wr.id = nr.workflow_run_id\s\
        where wr.tenant_id = ? and wr.trigger_request_id = ? and nr.node_code = 'JOB'\s\
        and nr.job_instance_id is not null\
        """;
    final String selectJobInstanceIdSql = """
        select nr.job_instance_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and wr.trigger_request_id = ? and nr.node_code = 'JOB'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and trigger_request_id = ?\
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and id = ?
    """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "DUP_MISFIRE_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Duplicate misfire customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        updateJobDefinitionSql,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());
    jdbcTemplate.update(updateBusinessCalendarSql, TENANT);
    jdbcTemplate.update(updateWorkflowDefinitionSql, TENANT, seed.workflowDefinitionId());

    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence())
        .as("duplicate callback acceptance requires the production-style JDBC Quartz JobStore")
        .isTrue();
    scheduler.start();
    scheduler.standby();
    Instant startAt = Instant.now().minus(Duration.ofMinutes(4)).truncatedTo(ChronoUnit.MINUTES);
    JobKey jobKey =
        JobKey.jobKey(TENANT + ":WF:" + seed.jobCode(), TriggerSchedulerFacade.JOB_GROUP);
    JobDataMap jobData = new JobDataMap();
    jobData.put(QuartzLaunchJob.TENANT_ID, TENANT);
    jobData.put(QuartzLaunchJob.JOB_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    jobData.put(QuartzLaunchJob.WORKFLOW_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobData.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 1);
    jobData.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobData.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 * * * * ?");
    jobData.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobData.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobData.put(QuartzLaunchJob.CALENDAR_CODE, "e2e_calendar");
    jobData.put(QuartzLaunchJob.CATCH_UP_POLICY, "AUTO");
    jobData.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(jobData)
        .storeDurably()
        .build();
    JobDataMap triggerIdentity = new JobDataMap();
    triggerIdentity.put(QuartzLaunchJob.TENANT_ID, TENANT);
    triggerIdentity.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    CronTrigger firstTrigger =
        duplicateMisfireTrigger("duplicate-e2e-a", jobKey, startAt, triggerIdentity);
    CronTrigger secondTrigger =
        duplicateMisfireTrigger("duplicate-e2e-b", jobKey, startAt, triggerIdentity);
    Date originalFireTime = firstTrigger.getFireTimeAfter(Date.from(startAt.minusMillis(1)));
    assertThat(secondTrigger.getFireTimeAfter(Date.from(startAt.minusMillis(1))))
        .isEqualTo(originalFireTime);
    assertThat(originalFireTime).isBefore(Date.from(Instant.now().minusSeconds(60)));
    LocalDate bizDate = originalFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        seed.workflowDefinitionId(),
        seed.jobCode(),
        Timestamp.from(originalFireTime.toInstant()),
        bizDate);
    scheduler.addJob(job, true);
    scheduler.scheduleJob(firstTrigger);
    scheduler.scheduleJob(secondTrigger);

    AtomicInteger observedMisfireCallbacks = new AtomicInteger();
    scheduler
        .getListenerManager()
        .addTriggerListener(
            new TriggerListener() {
              @Override
              public String getName() {
                return "duplicate-e2e-misfire-callback-observer";
              }

              @Override
              public void triggerFired(Trigger trigger, JobExecutionContext context) {}

              @Override
              public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
                return false;
              }

              @Override
              public void triggerMisfired(Trigger trigger) {
                observedMisfireCallbacks.incrementAndGet();
              }

              @Override
              public void triggerComplete(
                  Trigger trigger,
                  JobExecutionContext context,
                  Trigger.CompletedExecutionInstruction triggerInstructionCode) {}
            },
            EverythingMatcher.allTriggers());
    scheduler.start();

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      assertThat(observedMisfireCallbacks.get())
          .as("both JDBC JobStore overdue triggers must deliver a real callback")
          .isEqualTo(2);
      String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
          selectOccurrenceStatusSql,
          String.class,
          TENANT,
          seed.workflowDefinitionId(),
          Timestamp.from(originalFireTime.toInstant()));
      assertThat(selectedOccurrenceStatus).isEqualTo("REQUESTED");
      Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId());
      assertThat(selectedTriggerRequestCount).isEqualTo(1);
      Integer selectedTriggerOutboxEventCount = jdbcTemplate.queryForObject(
          selectCountSql2, Integer.class, TENANT, seed.workflowDefinitionId());
      assertThat(selectedTriggerOutboxEventCount).isEqualTo(1);
    });

    String catchUpRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId());
    Long triggerRequestId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, catchUpRequestId);
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
          selectCountSql3, Integer.class, TENANT, seed.workflowDefinitionId(), triggerRequestId);
      assertThat(selectedWorkflowRunCount).isEqualTo(1);
      Integer selectedWorkflowNodeRunCount =
          jdbcTemplate.queryForObject(selectCountSql4, Integer.class, TENANT, triggerRequestId);
      assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
    });
    Long jobInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, TENANT, triggerRequestId);
    await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
      String selectedRunStatus =
          jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, triggerRequestId);
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");
      String selectedInstanceStatus =
          jdbcTemplate.queryForObject(selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
      assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
      Integer selectedCount = new JdbcTemplate(businessDataSource)
          .queryForObject(selectCountSql5, Integer.class, TENANT, customerNo);
      assertThat(selectedCount).isEqualTo(1);
    });
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, triggerRequestId);
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
  }

  @Test
  @DisplayName("DST gap 后的真实 Quartz misfire 按节假日滚动业务日完成 Worker 并结算")
  void dstHolidayQuartzMisfire_completesWorkerBeforeBatchDaySettlement() throws Exception {
    final String selectCountSql6 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
        """;

    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do update set status = 'ACTIVE'\
        """;
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id = ? and job_code = ?
    """;
    final String selectIdSql2 = """
        select id from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance (tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,\s\
        biz_date, trigger_type, instance_status, queue_code, worker_group, priority, dedup_key, run_attempt,\s\
        version, expected_partition_count, success_partition_count, failed_partition_count, params_snapshot)\s\
        values (?, ?, ?, ?, ?, ?, 'API', 'SUCCESS', 'e2e-q', 'DISPATCH', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb)\s\
        returning id\
        """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set request_status = 'LAUNCHED' where tenant_id = ? and request_id = ?\
        """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set cross_day_dependencies = ?::jsonb, cross_day_dependency_timeout_seconds =\s\
        600 where tenant_id = ? and workflow_definition_id = ? and node_code = 'JOB'\
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params = ?::jsonb where tenant_id = ? and job_code = ?\
        """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        holiday_roll_rule, catch_up_policy, catch_up_max_days, enabled)\s\
        values (?, ?, 'DST holiday misfire calendar', 'America/New_York', '02:30:00', 'NEXT_WORKDAY', 'AUTO', 0,\s\
        true) returning id\
        """;
    final String insertCalendarHolidaySql = """
        insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)\s\
        values (?, ?, DATE '2026-03-08', 'HOLIDAY', 'DST test holiday', 'CALENDAR')\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition\s\
        set schedule_type = 'CRON', schedule_expr = '0 0 3 * * ?', timezone = 'America/New_York', trigger_mode =\s\
        'SCHEDULED', calendar_code = ?, schedule_enabled = true where tenant_id = ? and id = ?\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at) values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp)\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set cutoff_at = clock_timestamp() where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'CATCH_UP'\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_type = 'CATCH_UP'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_request_id = ?\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and wr.trigger_request_id = ? and nr.node_code = 'JOB' order by nr.run_seq desc limit 1\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_node_run nr join batch.workflow_run wr on wr.id = nr.workflow_run_id\s\
        where wr.tenant_id = ? and wr.trigger_request_id = ? and nr.node_code = 'JOB'\s\
        and nr.job_instance_id is not null\
        """;
    final String insertResultVersionSql = """
        insert into batch.result_version (tenant_id, business_key, version_no, job_instance_id,
          status, payload_storage, payload_json, generated_at, generated_by,
          promotion_policy) values (?, ?, 1,
          (select id from batch.job_instance where tenant_id = ? and dedup_key = ?),
          'PENDING', 'INLINE_JSON', '{"sourceBatch":"dst-prior-workday"}'::jsonb,
          current_timestamp, 'dst-holiday-e2e', 'AUTO_LATEST')
        """;
    final String selectIdSql3 = """
        select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 1\
        """;
    final String selectStatusSql = """
        select status from batch.result_version where tenant_id = ? and id = ?
    """;
    final String selectCountSql4 = """
        select count(*) from batch.workflow_node_run nr join batch.workflow_run wr on wr.id = nr.workflow_run_id\s\
        where wr.tenant_id = ? and wr.trigger_request_id = ? and nr.node_code = 'JOB'\s\
        and nr.job_instance_id is not null\
        """;
    final String selectJobInstanceIdSql = """
        select nr.job_instance_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and wr.trigger_request_id = ? and nr.node_code = 'JOB' and nr.job_instance_id is not null\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.trigger_request where tenant_id = ? and id = ?
    """;
    final String selectBizDateSql2 = """
        select biz_date from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectParamsSnapshotSql = """
        select params_snapshot->'effectiveParams'->'crossDay'->'prior_workday'->>'resultVersionId'\s\
        from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectParamsSnapshotSql2 = """
        select params_snapshot->'effectiveParams'->'crossDay'->'prior_workday'->'output'->>'sourceBatch'\s\
        from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and trigger_request_id = ?\
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectCountSql5 = """
        select count(*) from batch.workflow_definition wf\s\
        join batch.batch_day_instance day on day.tenant_id = wf.tenant_id\s\
        and day.calendar_code = wf.calendar_code where wf.tenant_id = ? and wf.id = ? and day.biz_date = ?\s\
        and wf.schedule_enabled = true and wf.schedule_type in ('CRON', 'FIXED_RATE')\s\
        and wf.trigger_mode in ('SCHEDULED', 'MIXED') and not exists (select 1\s\
        from batch.scheduled_occurrence_coverage coverage where coverage.tenant_id = wf.tenant_id\s\
        and coverage.workflow_definition_id = wf.id and coverage.schedule_version = wf.version\s\
        and coverage.calendar_code is not distinct from wf.calendar_code\s\
        and coverage.coverage_from_at <= day.cutoff_at and coverage.coverage_through_at >= day.cutoff_at\s\
        and coverage.last_success_at >= coalesce((select revision.updated_at\s\
        from batch.tenant_configuration_revision revision\s\
        where revision.tenant_id = wf.tenant_id), '-infinity'::timestamptz))\
        """;

    startTriggerHttpServer();
    awaitWorkerOnline();
    jdbcTemplate.update(insertTenantSql, TENANT, "DST holiday misfire E2E");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LocalDate expectedBizDate = LocalDate.of(2026, 3, 9);
    LaunchSeed source = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    Long sourceJobDefinitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, source.jobCode());
    Long sourceTriggerRequestId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, source.requestId());
    LocalDate sourceBizDate = expectedBizDate.minusDays(3);
    String sourceDedupKey = "dst-holiday-source-" + UUID.randomUUID();
    jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        TENANT,
        sourceJobDefinitionId,
        sourceTriggerRequestId,
        source.jobCode(),
        "dst-holiday-source-" + UUID.randomUUID(),
        sourceBizDate,
        sourceDedupKey);
    jdbcTemplate.update(updateTriggerRequestSql, TENANT, source.requestId());
    jdbcTemplate.update(
        updateWorkflowNodeSql,
        """
        [{"alias":"prior_workday","jobCode":"%s","bizDateOffset":-3,
          "scope":"REQUIRED","consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(source.jobCode()),
        TENANT,
        seed.workflowDefinitionId());
    String customerNo = "DST_HOLIDAY_MISFIRE_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"DST holiday misfire customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        updateJobDefinitionSql,
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());

    String calendarCode = "dst_holiday_" + UUID.randomUUID().toString().replace("-", "");
    Long calendarId =
        jdbcTemplate.queryForObject(insertBusinessCalendarSql, Long.class, TENANT, calendarCode);
    jdbcTemplate.update(insertCalendarHolidaySql, TENANT, calendarId);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql, calendarCode, TENANT, seed.workflowDefinitionId());

    Instant expectedFireTime = Instant.parse("2026-03-08T07:00:00Z");
    assertThat(expectedFireTime.atZone(ZoneId.of("America/New_York")).toLocalTime())
        .isEqualTo(LocalTime.of(3, 0));
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, expectedBizDate);

    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
    scheduler.start();
    scheduler.standby();
    JobKey jobKey =
        JobKey.jobKey(TENANT + ":WF:" + seed.jobCode(), TriggerSchedulerFacade.JOB_GROUP);
    JobDataMap jobData = new JobDataMap();
    jobData.put(QuartzLaunchJob.TENANT_ID, TENANT);
    jobData.put(QuartzLaunchJob.JOB_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    jobData.put(QuartzLaunchJob.WORKFLOW_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobData.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 1);
    jobData.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobData.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 0 3 * * ?");
    jobData.put(QuartzLaunchJob.TIMEZONE, "America/New_York");
    jobData.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobData.put(QuartzLaunchJob.CALENDAR_CODE, calendarCode);
    jobData.put(QuartzLaunchJob.CATCH_UP_POLICY, "AUTO");
    jobData.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 0);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(jobKey)
        .usingJobData(jobData)
        .storeDurably()
        .build();
    JobDataMap triggerIdentity = new JobDataMap();
    triggerIdentity.put(QuartzLaunchJob.TENANT_ID, TENANT);
    triggerIdentity.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    Instant startAt = expectedFireTime.minusSeconds(1);
    CronTrigger cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(jobKey.getName(), jobKey.getGroup())
        .forJob(job)
        .usingJobData(triggerIdentity)
        .startAt(Date.from(startAt))
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 3 * * ?")
            .inTimeZone(TimeZone.getTimeZone("America/New_York"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
    Date originalFireTime = cronTrigger.getFireTimeAfter(Date.from(startAt.minusMillis(1)));
    assertThat(originalFireTime.toInstant()).isEqualTo(expectedFireTime);
    assertThat(originalFireTime).isBefore(Date.from(Instant.now().minus(Duration.ofDays(30))));
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        TENANT,
        seed.workflowDefinitionId(),
        seed.jobCode(),
        calendarCode,
        Timestamp.from(expectedFireTime),
        expectedBizDate);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(TENANT);
    descriptor.setWorkflowDefinitionId(seed.workflowDefinitionId());
    descriptor.setWorkflowCode(seed.jobCode());
    descriptor.setWorkflowVersion(1);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 3 * * ?");
    descriptor.setTimezone("America/New_York");
    descriptor.setTriggerMode("SCHEDULED");
    descriptor.setCalendarCode(calendarCode);
    descriptor.setEnabled(true);
    descriptor.setWorkflowEnabled(true);
    TriggerScheduledOccurrencePlanner occurrencePlanner =
        triggerContext.getBean(TriggerScheduledOccurrencePlanner.class);
    var plannedFireTimes = QuartzScheduledFireEnumerator.fireTimeSource(cronTrigger);
    assertThat(occurrencePlanner.materialize(descriptor, plannedFireTimes, Instant.now()))
        .isPositive();
    jdbcTemplate.update(updateBatchDayInstanceSql, TENANT, calendarCode, expectedBizDate);
    scheduler.scheduleJob(job, cronTrigger);

    AtomicInteger misfireCallbacks = new AtomicInteger();
    scheduler
        .getListenerManager()
        .addTriggerListener(
            new TriggerListener() {
              @Override
              public String getName() {
                return "dst-holiday-misfire-callback-observer";
              }

              @Override
              public void triggerFired(Trigger trigger, JobExecutionContext context) {}

              @Override
              public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
                return false;
              }

              @Override
              public void triggerMisfired(Trigger trigger) {
                misfireCallbacks.incrementAndGet();
              }

              @Override
              public void triggerComplete(
                  Trigger trigger,
                  JobExecutionContext context,
                  Trigger.CompletedExecutionInstruction triggerInstructionCode) {}
            },
            EverythingMatcher.allTriggers());
    scheduler.start();

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          assertThat(misfireCallbacks.get()).isEqualTo(1);
          String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(expectedFireTime));
          // 独立 Trigger 进程的 misfire 自动恢复（200ms reconcile）可能已把 PENDING 推进为
          // REQUESTED；两种状态都证明 misfire 已被记录，重启持久化断言仅在仍为 PENDING 时验证。
          assertThat(selectedOccurrenceStatus).isIn("MISFIRE_PENDING", "REQUESTED");
        });
    String occurrenceStatusBeforeRestart = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        TENANT,
        seed.workflowDefinitionId(),
        Timestamp.from(expectedFireTime));
    restartTriggerHttpServer();
    Scheduler restartedScheduler = triggerContext.getBean(Scheduler.class);
    assertThat(restartedScheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
    if ("MISFIRE_PENDING".equals(occurrenceStatusBeforeRestart)) {
      assertThat(
              triggerContext.getBean(TriggerDefinitionLoader.class).loadPendingMisfireDefinitions())
          .extracting(TriggerDescriptor::getWorkflowDefinitionId)
          .contains(seed.workflowDefinitionId());
      AopTestUtils.<TriggerReconciler>getTargetObject(
              triggerContext.getBean(TriggerReconciler.class))
          .reconcile();
    }
    restartedScheduler.start();
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedOccurrenceStatusRead2 = jdbcTemplate.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              Timestamp.from(expectedFireTime));
          assertThat(selectedOccurrenceStatusRead2).isEqualTo("REQUESTED");
          Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
              selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId());
          assertThat(selectedTriggerRequestCount).isEqualTo(1);
        });
    String requestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, seed.workflowDefinitionId());
    Long triggerRequestId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, requestId);
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              seed.workflowDefinitionId(),
              triggerRequestId);
          assertThat(selectedWorkflowRunCount).isEqualTo(1);
        });
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, expectedBizDate)).isEqualTo("IN_FLIGHT");
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          List<String> selectedNodeStatusValues = jdbcTemplate.query(
              selectNodeStatusSql, (rs, rowNum) -> rs.getString(1), TENANT, triggerRequestId);
          assertThat(selectedNodeStatusValues).containsExactly("WAITING_DEPENDENCY");
        });
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, triggerRequestId);
    assertThat(selectedWorkflowNodeRunCount).isZero();
    String sourceBusinessKey = "job:" + source.jobCode() + ":" + sourceBizDate;
    jdbcTemplate.update(insertResultVersionSql, TENANT, sourceBusinessKey, TENANT, sourceDedupKey);
    Long expectedResultVersionId =
        jdbcTemplate.queryForObject(selectIdSql3, Long.class, TENANT, sourceBusinessKey);
    resultVersionPromoteService.promote(TENANT, expectedResultVersionId);
    String selectedStatus =
        jdbcTemplate.queryForObject(selectStatusSql, String.class, TENANT, expectedResultVersionId);
    assertThat(selectedStatus).isEqualTo("EFFECTIVE");
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedWorkflowNodeRunCountRead2 =
              jdbcTemplate.queryForObject(selectCountSql4, Integer.class, TENANT, triggerRequestId);
          assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1);
        });
    Long jobInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, TENANT, triggerRequestId);
    LocalDate selectedBizDate =
        jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, TENANT, triggerRequestId);
    assertThat(selectedBizDate).isEqualTo(expectedBizDate);
    LocalDate selectedBizDateRead2 =
        jdbcTemplate.queryForObject(selectBizDateSql2, LocalDate.class, TENANT, jobInstanceId);
    assertThat(selectedBizDateRead2).isEqualTo(expectedBizDate);
    String selectedParamsSnapshot =
        jdbcTemplate.queryForObject(selectParamsSnapshotSql, String.class, TENANT, jobInstanceId);
    assertThat(selectedParamsSnapshot).isEqualTo(expectedResultVersionId.toString());
    String selectedParamsSnapshotRead2 =
        jdbcTemplate.queryForObject(selectParamsSnapshotSql2, String.class, TENANT, jobInstanceId);
    assertThat(selectedParamsSnapshotRead2).isEqualTo("dst-prior-workday");
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedRunStatus = jdbcTemplate.queryForObject(
              selectRunStatusSql, String.class, TENANT, triggerRequestId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          Integer selectedCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql6, Integer.class, TENANT, customerNo);
          assertThat(selectedCount).isEqualTo(1);
        });
    Integer selectedWorkflowDefinitionCount = jdbcTemplate.queryForObject(
        selectCountSql5, Integer.class, TENANT, seed.workflowDefinitionId(), expectedBizDate);
    assertThat(selectedWorkflowDefinitionCount)
        .as("the scheduled workflow must have current occurrence coverage before settlement")
        .isZero();
    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(calendarCode, expectedBizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("根 Workflow 跨日依赖等待后由恢复扫描器派发,Worker 消费恢复参数并完成业务写入")
  void crossDayDependencyWait_recoversRootPayloadAndCompletesImportBusinessEffect()
      throws Exception {
    final String selectCountSql3 = """
            select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
            """;

    final String selectIdSql = """
            select id from batch.job_definition where tenant_id = ? and job_code = ?
        """;
    final String selectIdSql2 = """
            select id from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String insertJobInstanceSql = """
            insert into batch.job_instance (tenant_id, job_definition_id, trigger_request_id, job_code,\s\
            instance_no, biz_date, trigger_type, instance_status, queue_code, worker_group, priority, dedup_key,\s\
            run_attempt, version, expected_partition_count, success_partition_count, failed_partition_count,\s\
            params_snapshot) values (?, ?, ?, ?, ?, ?, 'API', 'SUCCESS', 'e2e-q', 'DISPATCH', 5, ?, 1, 0, 0, 0,\s\
            0, '{}'::jsonb) returning id\
            """;
    final String updateTriggerRequestSql = """
            update batch.trigger_request set request_status = 'LAUNCHED' where tenant_id = ? and request_id = ?\
            """;
    final String updateWorkflowNodeSql = """
            update batch.workflow_node set cross_day_dependencies = ?::jsonb,\s\
            cross_day_dependency_timeout_seconds = 600 where tenant_id = ? and workflow_definition_id = ?\s\
            and node_code = 'JOB'\
            """;
    final String deleteTriggerRequestSql = """
            delete from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectCountSql = """
            select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
            and request_id = ?)\
            """;
    final String selectIdSql3 = """
            select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
            and request_id = ?)\
            """;
    final String selectCoalesceSql = """
            select coalesce(string_agg(node_code || ':' || node_status, ',' order by id), '')\s\
            from batch.workflow_node_run where workflow_run_id = ?\
            """;
    final String selectRunStatusSql = """
            select run_status || ':' || coalesce(current_node_code, '') from batch.workflow_run\s\
            where tenant_id = ? and id = ?\
            """;
    final String selectNodeStatusSql = """
            select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB'\s\
            order by run_seq desc limit 1\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB'\s\
            and job_instance_id is not null\
            """;
    final String insertResultVersionSql = """
        insert into batch.result_version (tenant_id, business_key, version_no, job_instance_id,
          status, payload_storage, payload_json, generated_at, generated_by,
          promotion_policy) values (?, ?, 1, ?, 'PENDING',
          'INLINE_JSON', '{"sourceBatch":"previous-day"}'::jsonb, current_timestamp,
          'cross-day-e2e', 'AUTO_LATEST')
        """;
    final String selectIdSql4 = """
            select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 1\
            """;
    final String selectStatusSql = """
            select status from batch.result_version where tenant_id = ? and id = ?
        """;
    final String selectJobInstanceIdSql = """
            select job_instance_id from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB'\s\
            and job_instance_id is not null order by run_seq desc limit 1\
            """;
    final String selectParamsSnapshotSql = """
            select params_snapshot->'effectiveParams'->>'rootMarker' from batch.job_instance where tenant_id = ?\s\
            and id = ?\
            """;
    final String selectParamsSnapshotSql2 = """
            select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->'output'->>'sourceBatch'\s\
            from batch.job_instance where tenant_id = ? and id = ?\
            """;
    final String selectParamsSnapshotSql3 = """
            select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->>'resultVersionId'\s\
            from batch.job_instance where tenant_id = ? and id = ?\
            """;
    final String selectBizDateSql = """
            select biz_date from batch.job_instance where tenant_id = ? and id = ?
        """;
    final String selectDataIntervalStartSql = """
            select data_interval_start from batch.job_instance where tenant_id = ? and id = ?\
            """;
    final String selectDataIntervalEndSql = """
            select data_interval_end from batch.job_instance where tenant_id = ? and id = ?\
            """;
    final String selectInstanceStatusSql = """
            select instance_status from batch.job_instance where tenant_id = ? and id = ?
        """;
    final String selectRunStatusSql2 = """
            select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;

    LocalDate crossDayBizDate = BIZ_DATE.plusDays(2);
    startTriggerHttpServer();
    awaitWorkerOnline();

    LaunchSeed source = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    Long sourceJobDefinitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, source.jobCode());
    Long sourceTriggerRequestId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, source.requestId());
    String sourceInstanceNo = "cross-day-source-" + UUID.randomUUID();
    String sourceDedupKey = "cross-day-source-dedup-" + UUID.randomUUID();
    Long sourceJobInstanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        TENANT,
        sourceJobDefinitionId,
        sourceTriggerRequestId,
        source.jobCode(),
        sourceInstanceNo,
        crossDayBizDate.minusDays(1),
        sourceDedupKey);
    jdbcTemplate.update(updateTriggerRequestSql, TENANT, source.requestId());
    LaunchSeed downstream = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    jdbcTemplate.update(
        updateWorkflowNodeSql,
        """
        [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-1,"scope":"REQUIRED",
          "consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(source.jobCode()),
        TENANT,
        downstream.workflowDefinitionId());
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, downstream.requestId());

    String customerNo = "CROSSDAY_" + UUID.randomUUID().toString().replace("-", "");
    String rootMarker = "root-" + UUID.randomUUID();
    String content = "[{\"customerNo\":\"" + customerNo
        + "\",\"customerName\":\"Cross-day customer\",\"customerType\":\"PERSONAL\","
        + "\"certificateNo\":\"ID-" + customerNo + "\",\"status\":\"ACTIVE\"}]";
    String requestId = "cross-day-root-" + UUID.randomUUID();
    Instant intervalStart = Instant.parse("2026-05-05T23:30:00Z");
    Instant intervalEnd = Instant.parse("2026-05-06T01:30:00Z");
    Map<String, Object> request = Map.of(
        "tenantId", TENANT,
        "workflowDefinitionId", downstream.workflowDefinitionId(),
        "bizDate", crossDayBizDate.toString(),
        "dataIntervalStart", intervalStart.toString(),
        "dataIntervalEnd", intervalEnd.toString(),
        "triggerType", "API",
        "params",
            Map.of(
                "fileFormatType", "JSON",
                "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
                "bizType", "CUSTOMER",
                "content", content,
                "rootMarker", rootMarker));
    HttpRequest triggerRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + TRIGGER_PORT + "/api/triggers/launch"))
        .header(CommonConstants.INTERNAL_SECRET_HEADER, E2E_INTERNAL_SECRET)
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "cross-day-idem-" + UUID.randomUUID())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId)
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, "trace-" + requestId)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
        .build();
    HttpResponse<String> triggerResponse =
        HTTP_CLIENT.send(triggerRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(triggerResponse.statusCode())
        .as("Trigger launch failed: %s", triggerResponse.body())
        .isBetween(200, 299);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              TENANT,
              downstream.workflowDefinitionId(),
              TENANT,
              requestId);
          assertThat(selectedWorkflowRunCount).isEqualTo(1);
        });
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectIdSql3, Long.class, TENANT, downstream.workflowDefinitionId(), TENANT, requestId);
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String nodeRuns =
              jdbcTemplate.queryForObject(selectCoalesceSql, String.class, workflowRunId);
          String runState =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          List<String> selectedNodeStatusValues = jdbcTemplate.query(
              selectNodeStatusSql, (rs, rowNum) -> rs.getString(1), workflowRunId);
          assertThat(selectedNodeStatusValues)
              .as("workflowRun state=%s nodeRuns=%s", runState, nodeRuns)
              .containsExactly("WAITING_DEPENDENCY");
        });
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isZero();

    String sourceBusinessKey = "job:" + source.jobCode() + ":" + crossDayBizDate.minusDays(1);
    jdbcTemplate.update(insertResultVersionSql, TENANT, sourceBusinessKey, sourceJobInstanceId);
    Long expectedResultVersionId =
        jdbcTemplate.queryForObject(selectIdSql4, Long.class, TENANT, sourceBusinessKey);
    resultVersionPromoteService.promote(TENANT, expectedResultVersionId);
    String selectedStatus =
        jdbcTemplate.queryForObject(selectStatusSql, String.class, TENANT, expectedResultVersionId);
    assertThat(selectedStatus).isEqualTo("EFFECTIVE");

    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          List<Long> jobInstanceIds = jdbcTemplate.query(
              selectJobInstanceIdSql, (rs, rowNum) -> rs.getLong(1), workflowRunId);
          assertThat(jobInstanceIds).hasSize(1);
          Long jobInstanceId = jobInstanceIds.getFirst();
          String selectedParamsSnapshot = jdbcTemplate.queryForObject(
              selectParamsSnapshotSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedParamsSnapshot).isEqualTo(rootMarker);
          String selectedParamsSnapshotRead2 = jdbcTemplate.queryForObject(
              selectParamsSnapshotSql2, String.class, TENANT, jobInstanceId);
          assertThat(selectedParamsSnapshotRead2).isEqualTo("previous-day");
          String selectedParamsSnapshotRead3 = jdbcTemplate.queryForObject(
              selectParamsSnapshotSql3, String.class, TENANT, jobInstanceId);
          assertThat(selectedParamsSnapshotRead3).isEqualTo(expectedResultVersionId.toString());
          LocalDate selectedBizDate =
              jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, TENANT, jobInstanceId);
          assertThat(selectedBizDate).isEqualTo(crossDayBizDate);
          Timestamp selectedDataIntervalStart = jdbcTemplate.queryForObject(
              selectDataIntervalStartSql, Timestamp.class, TENANT, jobInstanceId);
          assertThat(selectedDataIntervalStart).isEqualTo(Timestamp.from(intervalStart));
          Timestamp selectedDataIntervalEnd = jdbcTemplate.queryForObject(
              selectDataIntervalEndSql, Timestamp.class, TENANT, jobInstanceId);
          assertThat(selectedDataIntervalEnd).isEqualTo(Timestamp.from(intervalEnd));
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          String selectedRunStatus =
              jdbcTemplate.queryForObject(selectRunStatusSql2, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          Integer selectedCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql3, Integer.class, TENANT, customerNo);
          assertThat(selectedCount).isEqualTo(1);
        });
  }

  @Test
  @DisplayName("SDK 中断并经 lease 回收后仍从 CLAIM 消费 gate 固定版本")
  void shouldConsumePinnedVersion_whenUpstreamVersionPromoted(@TempDir Path sdkProcessDir)
      throws Exception {
    final String selectCountSql3 = """
            select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?
        """;
    final String selectCustomerNameSql = """
            select customer_name from biz.customer_account where tenant_id = ? and customer_no = ?\
            """;
    final String selectCountSql4 = """
            select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?\
            """;
    final String selectSourceBatchNoSql = """
            select source_batch_no from biz.customer_account where tenant_id = ? and customer_no = ?\
            """;

    final String selectIdSql = """
            select id from batch.job_definition where tenant_id = ? and job_code = ?
        """;
    final String selectIdSql2 = """
            select id from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String insertJobInstanceSql = """
            insert into batch.job_instance (tenant_id, job_definition_id, trigger_request_id, job_code,\s\
            instance_no, biz_date, trigger_type, instance_status, queue_code, worker_group, priority, dedup_key,\s\
            run_attempt, version, expected_partition_count, success_partition_count, failed_partition_count,\s\
            params_snapshot) values (?, ?, ?, ?, ?, ?, 'API', 'SUCCESS', 'e2e-q', 'DISPATCH', 5, ?, 1, 0, 0, 0,\s\
            0, '{}'::jsonb) returning id\
            """;
    final String updateTriggerRequestSql = """
            update batch.trigger_request set request_status = 'LAUNCHED' where tenant_id = ? and request_id = ?\
            """;
    final String updateWorkflowNodeSql = """
            update batch.workflow_node set cross_day_dependencies = ?::jsonb,\s\
            cross_day_dependency_timeout_seconds = 600 where tenant_id = ? and workflow_definition_id = ?\s\
            and node_code = 'JOB'\
            """;
    final String deleteTriggerRequestSql = """
            delete from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String selectCountSql = """
            select count(*) from batch.worker_registry where tenant_id = ? and worker_code = ?\s\
            and is_self_hosted\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
            and request_id = ?)\
            """;
    final String selectIdSql3 = """
            select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
            and request_id = ?)\
            """;
    final String selectNodeStatusSql = """
            select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB'\s\
            order by run_seq desc limit 1\
            """;
    final String insertResultVersionSql = """
          insert into batch.result_version (tenant_id, business_key, version_no, job_instance_id,
            status, effective_at, payload_storage, payload_json, generated_at, generated_by,
            promotion_policy) values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp,
            'INLINE_JSON', '{"sourceBatch":"previous-day-v1","recordCount":17}'::jsonb,
            current_timestamp, 'cross-day-sdk-e2e', 'AUTO_LATEST')
          """;
    final String selectIdSql4 = """
            select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 1\
            """;
    final String insertResultVersionSql2 = """
          insert into batch.result_version (tenant_id, business_key, version_no, job_instance_id,
            status, payload_storage, payload_json, generated_at, generated_by, promotion_policy)
          values (?, ?, 2, ?, 'PENDING', 'INLINE_JSON',
            '{"sourceBatch":"newer-v2","recordCount":99}'::jsonb, current_timestamp,
            'cross-day-sdk-e2e-v2', 'MANUAL_APPROVAL')
          """;
    final String selectIdSql5 = """
            select id from batch.result_version where tenant_id = ? and business_key = ? and version_no = 2\
            """;
    final String selectStatusSql = """
            select status from batch.result_version where tenant_id = ? and id = ?
        """;
    final String selectPayloadJsonSql = """
            select payload_json from batch.result_version where tenant_id = ? and id = ?
        """;
    final String selectPayloadJsonSql2 = """
            select payload_json::text from archive.result_version_archive where tenant_id = ? and id = ?\
            """;
    final String selectIdSql6 = """
            select id from batch.job_partition where tenant_id = ? and job_instance_id = (select job_instance_id\s\
            from batch.job_task where tenant_id = ? and id = ?)\
            """;
    final String selectCurrentInvocationIdSql = """
            select current_invocation_id from batch.job_partition where tenant_id = ? and id = ?
        """;
    final String selectTaskStatusSql = """
            select task_status from batch.job_task where tenant_id = ? and id = ?
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectNonIdempotentStartCountSql = """
        select count(*) from batch.job_task
         where tenant_id = ? and id = ? and non_idempotent_started_at is not null
        """;
    final String selectPublishStatusSql = """
                    select publish_status from batch.outbox_event
                    where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'
                      and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'
                    """;

    LocalDate crossDayBizDate = BIZ_DATE.plusDays(3);
    startTriggerHttpServer();

    LaunchSeed source = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "DISPATCH", TriggerType.API);
    Long sourceJobDefinitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, source.jobCode());
    Long sourceTriggerRequestId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, source.requestId());
    Long sourceJobInstanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        TENANT,
        sourceJobDefinitionId,
        sourceTriggerRequestId,
        source.jobCode(),
        "cross-day-sdk-source-" + UUID.randomUUID(),
        crossDayBizDate.minusDays(1),
        "cross-day-sdk-source-dedup-" + UUID.randomUUID());
    jdbcTemplate.update(updateTriggerRequestSql, TENANT, source.requestId());

    LaunchSeed downstream = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "sdk-self-hosted", TriggerType.API);
    jdbcTemplate.update(
        updateWorkflowNodeSql,
        """
        [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-1,
          "scope":"REQUIRED","consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(source.jobCode()),
        TENANT,
        downstream.workflowDefinitionId());
    jdbcTemplate.update(deleteTriggerRequestSql, TENANT, downstream.requestId());

    String customerNo = "CROSSDAY_SDK_" + UUID.randomUUID().toString().replace("-", "");
    String rootMarker = "sdk-root-" + UUID.randomUUID();
    String requestId = "cross-day-sdk-root-" + UUID.randomUUID();
    JdbcTemplate businessJdbcTemplate = new JdbcTemplate(businessDataSource);
    String workerCode = "cross-day-sdk-" + UUID.randomUUID().toString().replace("-", "");
    Path firstReady = sdkProcessDir.resolve("first-ready");
    Path firstEntered = sdkProcessDir.resolve("first-entered");
    Path firstStop = sdkProcessDir.resolve("first-stop");
    Path recoveredReady = sdkProcessDir.resolve("recovered-ready");
    Path recoveredEntered = sdkProcessDir.resolve("recovered-entered");
    Path recoveredCompleted = sdkProcessDir.resolve("recovered-completed");
    Path recoveredStop = sdkProcessDir.resolve("recovered-stop");
    Process firstWorker = null;
    Process recoveredWorker = null;

    try {
      createKafkaTopic(
          BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_PROCESS, workerCode));
      firstWorker = startSdkWorkerProcess(
          sdkProcessDir,
          "block",
          workerCode,
          customerNo,
          rootMarker,
          firstReady,
          firstEntered,
          sdkProcessDir.resolve("first-completed"),
          firstStop);
      awaitSdkWorkerStarted(firstWorker, firstReady, sdkProcessDir.resolve("block.log"));
      await()
          .atMost(Duration.ofSeconds(20))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            Integer selectedWorkerRegistryCount =
                jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, workerCode);
            assertThat(selectedWorkerRegistryCount).isOne();
          });

      Map<String, Object> request = Map.of(
          "tenantId", TENANT,
          "workflowDefinitionId", downstream.workflowDefinitionId(),
          "bizDate", crossDayBizDate.toString(),
          "triggerType", "API",
          "params", Map.of("rootMarker", rootMarker));
      HttpRequest triggerRequest = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + TRIGGER_PORT + "/api/triggers/launch"))
          .header(CommonConstants.INTERNAL_SECRET_HEADER, E2E_INTERNAL_SECRET)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "cross-day-sdk-idem-" + UUID.randomUUID())
          .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId)
          .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, "trace-" + requestId)
          .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
          .build();
      HttpResponse<String> triggerResponse =
          HTTP_CLIENT.send(triggerRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(triggerResponse.statusCode())
          .as("Trigger launch failed: %s", triggerResponse.body())
          .isBetween(200, 299);

      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
                selectCountSql2,
                Integer.class,
                TENANT,
                downstream.workflowDefinitionId(),
                TENANT,
                requestId);
            assertThat(selectedWorkflowRunCount).isOne();
          });
      Long workflowRunId = jdbcTemplate.queryForObject(
          selectIdSql3, Long.class, TENANT, downstream.workflowDefinitionId(), TENANT, requestId);
      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            String selectedNodeStatus =
                jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
            assertThat(selectedNodeStatus).isEqualTo("WAITING_DEPENDENCY");
          });

      String sourceBusinessKey = "job:" + source.jobCode() + ":" + crossDayBizDate.minusDays(1);
      jdbcTemplate.update(insertResultVersionSql, TENANT, sourceBusinessKey, sourceJobInstanceId);
      Long selectedVersionId =
          jdbcTemplate.queryForObject(selectIdSql4, Long.class, TENANT, sourceBusinessKey);

      awaitSdkWorkerMarker(
          firstWorker,
          firstEntered,
          sdkProcessDir.resolve("block.log"),
          "first SDK process to CLAIM and enter the Workflow child handler");
      Long claimedTaskId = Long.valueOf(Files.readString(firstEntered).substring("task=".length()));
      assertThat(claimedTaskId).isPositive();
      Integer firstStartCount = jdbcTemplate.queryForObject(
          selectNonIdempotentStartCountSql, Integer.class, TENANT, claimedTaskId);
      assertThat(firstStartCount)
          .as("the replay-safe SDK fixture must not create an unsafe-start marker before blocking")
          .isZero();
      Integer selectedCustomerAccountCount =
          businessJdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, customerNo);
      assertThat(selectedCustomerAccountCount).isZero();

      jdbcTemplate.update(insertResultVersionSql2, TENANT, sourceBusinessKey, sourceJobInstanceId);
      Long promotedVersionId =
          jdbcTemplate.queryForObject(selectIdSql5, Long.class, TENANT, sourceBusinessKey);
      resultVersionPromoteService.promote(TENANT, promotedVersionId);
      assertThat(promotedVersionId).isNotEqualTo(selectedVersionId);
      String selectedStatus =
          jdbcTemplate.queryForObject(selectStatusSql, String.class, TENANT, selectedVersionId);
      assertThat(selectedStatus).isEqualTo("SUPERSEDED");
      Instant archiveCutoff = Instant.now().plus(Duration.ofDays(200));
      assertThat(resultVersionRetentionScheduler.demoteSupersededBatch(archiveCutoff))
          .as("expired superseded source v1 is archived before the SDK restart")
          .isPositive();
      String selectedStatusRead2 =
          jdbcTemplate.queryForObject(selectStatusSql, String.class, TENANT, selectedVersionId);
      assertThat(selectedStatusRead2).isEqualTo("ARCHIVED");
      String selectedPayloadJson = jdbcTemplate.queryForObject(
          selectPayloadJsonSql, String.class, TENANT, selectedVersionId);
      assertThat(selectedPayloadJson).isNull();
      String selectedPayloadJsonRead2 = jdbcTemplate.queryForObject(
          selectPayloadJsonSql2, String.class, TENANT, selectedVersionId);
      assertThat(selectedPayloadJsonRead2).contains("previous-day-v1");

      Long firstPartitionId =
          jdbcTemplate.queryForObject(selectIdSql6, Long.class, TENANT, TENANT, claimedTaskId);
      String firstInvocationId = jdbcTemplate.queryForObject(
          selectCurrentInvocationIdSql, String.class, TENANT, firstPartitionId);
      firstWorker.destroyForcibly();
      assertThat(firstWorker.waitFor(10, TimeUnit.SECONDS))
          .as("first SDK operating-system process must be forcibly terminated")
          .isTrue();
      String selectedTaskStatus =
          jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, claimedTaskId);
      assertThat(selectedTaskStatus).isEqualTo("RUNNING");

      jdbcTemplate.update(updateJobPartitionSql, TENANT, firstPartitionId);
      partitionReclaimUnit.reclaim(jobPartitionMapper.selectById(TENANT, firstPartitionId));
      String selectedTaskStatusRead2 =
          jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, claimedTaskId);
      assertThat(selectedTaskStatusRead2).isEqualTo("READY");

      recoveredWorker = startSdkWorkerProcess(
          sdkProcessDir,
          "consume",
          workerCode,
          customerNo,
          rootMarker,
          recoveredReady,
          recoveredEntered,
          recoveredCompleted,
          recoveredStop);
      awaitSdkWorkerStarted(recoveredWorker, recoveredReady, sdkProcessDir.resolve("consume.log"));
      awaitSdkWorkerMarker(
          recoveredWorker,
          recoveredEntered,
          sdkProcessDir.resolve("consume.log"),
          "restarted SDK process to CLAIM the re-dispatched task");
      String recoveredTaskMarker = Files.readString(recoveredEntered);
      String recoveredTaskValue = recoveredTaskMarker.substring("task=".length());
      Long recoveredTaskId = Long.valueOf(recoveredTaskValue);
      assertThat(recoveredTaskId).isEqualTo(claimedTaskId);
      Integer recoveredStartCount = jdbcTemplate.queryForObject(
          selectNonIdempotentStartCountSql, Integer.class, TENANT, recoveredTaskId);
      assertThat(recoveredStartCount)
          .as("the new invocation retains the fixture's proven replay-safe execution contract")
          .isZero();
      String recoveredInvocationId = jdbcTemplate.queryForObject(
          selectCurrentInvocationIdSql, String.class, TENANT, firstPartitionId);
      assertThat(recoveredInvocationId).isNotBlank().isNotEqualTo(firstInvocationId);
      awaitSdkWorkerMarker(
          recoveredWorker,
          recoveredCompleted,
          sdkProcessDir.resolve("consume.log"),
          "restarted SDK handler to commit its business effect");
      assertThat(Files.readString(recoveredCompleted))
          .isEqualTo(selectedVersionId + ":previous-day-v1");

      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            String selectedRunStatus = jdbcTemplate.queryForObject(
                selectRunStatusSql, String.class, TENANT, workflowRunId);
            assertThat(selectedRunStatus).isEqualTo("SUCCESS");
            String selectedCustomerName = businessJdbcTemplate.queryForObject(
                selectCustomerNameSql, String.class, TENANT, customerNo);
            assertThat(selectedCustomerName).isEqualTo("previous-day-v1");
            Integer selectedCustomerAccountCountRead2 = businessJdbcTemplate.queryForObject(
                selectCountSql4, Integer.class, TENANT, customerNo);
            assertThat(selectedCustomerAccountCountRead2)
                .as(
                    "different claim invocations retain one business effect for the stable customer key")
                .isOne();
            String selectedSourceBatchNo = businessJdbcTemplate.queryForObject(
                selectSourceBatchNoSql, String.class, TENANT, customerNo);
            assertThat(selectedSourceBatchNo).isEqualTo(selectedVersionId.toString());
            String selectedPublishStatus = jdbcTemplate.queryForObject(
                selectPublishStatusSql, String.class, TENANT, workflowRunId);
            assertThat(selectedPublishStatus).isEqualTo("PUBLISHED");
          });
    } finally {
      if (recoveredWorker != null && recoveredWorker.isAlive()) {
        Files.writeString(recoveredStop, "stop");
        if (!recoveredWorker.waitFor(20, TimeUnit.SECONDS)) {
          recoveredWorker.destroyForcibly();
          recoveredWorker.waitFor(10, TimeUnit.SECONDS);
        }
      }
      if (firstWorker != null && firstWorker.isAlive()) {
        Files.writeString(firstStop, "stop");
        if (!firstWorker.waitFor(10, TimeUnit.SECONDS)) {
          firstWorker.destroyForcibly();
          firstWorker.waitFor(10, TimeUnit.SECONDS);
        }
      }
    }
  }

  private static void createKafkaTopic(String topic) throws Exception {
    try (AdminClient adminClient = AdminClient.create(
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers()))) {
      adminClient.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    } catch (ExecutionException failure) {
      if (!(failure.getCause() instanceof TopicExistsException)) {
        throw failure;
      }
    }
  }

  private Process startSdkWorkerProcess(
      Path processDir,
      String mode,
      String workerCode,
      String customerNo,
      String rootMarker,
      Path ready,
      Path entered,
      Path completed,
      Path stop)
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty("mode", mode);
    properties.setProperty("base-url", "http://127.0.0.1:" + consolePort);
    properties.setProperty("tenant", TENANT);
    properties.setProperty("worker-code", workerCode);
    properties.setProperty("kafka-bootstrap", kafkaBootstrapServers());
    properties.setProperty("customer-no", customerNo);
    properties.setProperty("root-marker", rootMarker);
    properties.setProperty("business.jdbc.url", businessJdbcUrl());
    properties.setProperty("business.jdbc.username", businessJdbcUsername());
    properties.setProperty("business.jdbc.password", businessJdbcPassword());
    properties.setProperty("ready", ready.toString());
    properties.setProperty("entered", entered.toString());
    properties.setProperty("completed", completed.toString());
    properties.setProperty("stop", stop.toString());
    Path propertiesFile = processDir.resolve(mode + "-worker.properties");
    try (var output = Files.newOutputStream(propertiesFile)) {
      properties.store(output, "separate SDK worker process");
    }
    Path log = processDir.resolve(mode + ".log");
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    assertThat(classpath).isNotBlank();
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            SdkWorkerRestartChildProcess.class.getName(),
            propertiesFile.toString())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
  }

  private void awaitSdkWorkerStarted(Process process, Path ready, Path log) throws Exception {
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> Files.exists(ready) || !process.isAlive());
    assertThat(process.isAlive())
        .as("separate SDK worker process starts; log: %s", Files.readString(log))
        .isTrue();
  }

  private void awaitSdkWorkerMarker(Process process, Path marker, Path log, String description)
      throws Exception {
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> Files.exists(marker) || !process.isAlive());
    assertThat(process.isAlive())
        .as("%s; log: %s", description, Files.readString(log))
        .isTrue();
    assertThat(marker).as(description).exists();
  }

  private void startTriggerHttpServer() {
    if (triggerContext != null) {
      return;
    }
    triggerContext = new SpringApplicationBuilder(E2eTriggerApplication.class)
        .profiles("test", "e2e")
        .run(
            "--spring.main.web-application-type=servlet",
            "--server.port=" + TRIGGER_PORT,
            "--spring.datasource.url=" + platformJdbcUrl(),
            "--spring.datasource.username=" + platformJdbcUsername(),
            "--spring.datasource.password=" + platformJdbcPassword(),
            "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
            "--spring.flyway.enabled=false",
            "--spring.quartz.job-store-type=jdbc",
            "--spring.quartz.jdbc.initialize-schema=never",
            "--spring.quartz.auto-startup=false",
            "--spring.quartz.properties.org.quartz.jobStore.class=org.quartz.impl.jdbcjobstore.JobStoreTX",
            "--spring.quartz.properties.org.quartz.jobStore.dataSource=quartzJobstoreDs",
            "--spring.quartz.properties.org.quartz.jobStore.tablePrefix=quartz.QRTZ_",
            "--spring.quartz.properties.org.quartz.jobStore.driverDelegateClass=org.quartz.impl.jdbcjobstore.PostgreSQLDelegate",
            "--spring.quartz.properties.org.quartz.jobStore.isClustered=false",
            "--spring.quartz.properties.org.quartz.jobStore.misfireThreshold=1000",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.driver=org.postgresql.Driver",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.provider=hikaricp",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.URL="
                + platformJdbcUrl(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.user="
                + platformJdbcUsername(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.password="
                + platformJdbcPassword(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.maxConnections=5",
            "--batch.startup-self-check.enabled=false",
            "--batch.security.bypass-mode=false",
            "--batch.security.internal-secret=" + E2E_INTERNAL_SECRET,
            "--batch.orchestrator.base-url=http://127.0.0.1:" + consolePort,
            "--batch.trigger.outbox.poll-interval-millis=200",
            "--batch.trigger.misfire-pending-reconcile-interval-millis=200",
            "--batch.trigger.kafka.send-timeout-seconds=5");
  }

  private void restartTriggerHttpServer() {
    if (triggerContext != null) {
      triggerContext.close();
      triggerContext = null;
    }
    startTriggerHttpServer();
  }

  private void awaitWorkerOnline() {
    final String selectCountSql = """
        select count(1)::int from batch.worker_registry where tenant_id = ? and worker_code = 'e2e-import-1'\s\
        and worker_group = 'IMPORT' and status = 'ONLINE'\
        """;

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer selectedCount =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
          assertThat(selectedCount).isEqualTo(1);
        });
  }

  private void fireClosedDayScheduleThroughQuartz(LaunchSeed seed) throws Exception {
    fireScheduledOccurrenceThroughQuartz(seed, FIRE_TIME, "closed-day-occurrence-e2e");
  }

  private void fireScheduledOccurrenceThroughQuartz(
      LaunchSeed seed, Instant scheduledFireTime, String identitySuffix) throws Exception {
    fireScheduledOccurrenceThroughQuartz(
        seed, scheduledFireTime, identitySuffix, "e2e_calendar", "UTC", "0 30 9 * * ?");
  }

  private void fireScheduledOccurrenceThroughQuartz(
      LaunchSeed seed,
      Instant scheduledFireTime,
      String identitySuffix,
      String calendarCode,
      String timezone,
      String scheduleExpression)
      throws Exception {
    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    JobDataMap jobData = new JobDataMap();
    jobData.put(QuartzLaunchJob.TENANT_ID, TENANT);
    jobData.put(QuartzLaunchJob.JOB_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    jobData.put(QuartzLaunchJob.WORKFLOW_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobData.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 1);
    jobData.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobData.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, scheduleExpression);
    jobData.put(QuartzLaunchJob.TIMEZONE, timezone);
    jobData.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobData.put(QuartzLaunchJob.CALENDAR_CODE, calendarCode);
    jobData.put(QuartzLaunchJob.CATCH_UP_POLICY, "NONE");
    jobData.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 0);
    jobData.put(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME, scheduledFireTime.toEpochMilli());
    scheduler.start();
    scheduler.scheduleJob(
        JobBuilder.newJob(QuartzLaunchJob.class)
            .withIdentity(identitySuffix, "workflow-daily-e2e")
            .usingJobData(jobData)
            .build(),
        TriggerBuilder.newTrigger()
            .withIdentity(identitySuffix + "-fire", "workflow-daily-e2e")
            .startNow()
            .build());
  }

  private CronTrigger duplicateMisfireTrigger(
      String identity, JobKey jobKey, Instant startAt, JobDataMap triggerIdentity) {
    return TriggerBuilder.newTrigger()
        .withIdentity(identity, TriggerSchedulerFacade.JOB_GROUP)
        .forJob(jobKey)
        .usingJobData(triggerIdentity)
        .startAt(Date.from(startAt))
        .withSchedule(CronScheduleBuilder.cronSchedule("0 * * * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
  }

  private JsonNode postConsoleJson(String path, String body, String idempotencyKey)
      throws Exception {
    return sendConsoleJson("POST", path, body, idempotencyKey);
  }

  private JsonNode patchConsoleJson(String path, String body, String idempotencyKey)
      throws Exception {
    return sendConsoleJson("PATCH", path, body, idempotencyKey);
  }

  private JsonNode sendConsoleJson(String method, String path, String body, String idempotencyKey)
      throws Exception {
    HttpResponse<String> response = postConsoleResponse(method, path, body, idempotencyKey);
    assertThat(response.statusCode())
        .as("Console request failed: %s", response.body())
        .isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private HttpResponse<String> postConsoleResponse(String path, String body, String idempotencyKey)
      throws Exception {
    return postConsoleResponse("POST", path, body, idempotencyKey);
  }

  private HttpResponse<String> postConsoleResponse(
      String method, String path, String body, String idempotencyKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + consolePort + path))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header("X-Console-User", OPERATOR)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, "e2e-" + UUID.randomUUID())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, "e2e-" + UUID.randomUUID())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static int reservePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot reserve an E2E Trigger HTTP port", exception);
    }
  }
}
