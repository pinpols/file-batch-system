package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.e2e.apps.E2eReportRecoveryOrchestratorApplication;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchPreparationSpec;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.application.service.governance.AdminTestDataCleanupService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClient;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.client.SdkClientMetrics;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatcher;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RemoveMembersFromConsumerGroupOptions;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 真实 Kafka、平台 HTTP、平台 PG 与独立业务 PG 的普通 SDK 恢复验证。
 * 执行线程 Error、独立 SDK 进程强杀与 REPORT 响应实际断连分别建立证据。
 */
@E2eSpringBootTest(
    classes = E2eReportRecoveryOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.application.name=sdk-ordinary-lease-recovery-e2e",
      "batch.retry.poll-interval-millis=600000",
      "batch.batch-day.open-scan-interval-millis=600000"
    })
@DisplayName("普通 SDK 效果提交后失去执行：未知动作阻断，真实幂等动作经 outbox 恢复")
class SdkOrdinaryLeaseRecoveryE2eIT extends AbstractIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(45);
  private static final LocalDate BIZ_DATE = LocalDate.of(2099, 1, 15);
  private static final String SELECT_TASK_SQL = """
      select t.id, t.job_partition_id, t.task_status, t.error_code,
             t.non_idempotent_started_at, p.current_invocation_id
        from batch.job_task t
        join batch.job_partition p on p.tenant_id = t.tenant_id and p.id = t.job_partition_id
        join batch.job_instance i on i.tenant_id = t.tenant_id
             and i.id = t.job_instance_id and i.biz_date = t.job_instance_biz_date
       where i.tenant_id = ? and i.job_code = ? and i.biz_date = ?
      """;
  private static final String EFFECT_COUNT_SQL = """
      select count(*) from biz.customer_account where tenant_id = ?
      """;
  private static final String RETRY_COUNT_SQL = """
      select count(*) from batch.retry_schedule where tenant_id = ?
      """;

  private final JdbcTemplate platformJdbc;

  private final WorkflowRunLaunchService launchService;

  private final E2eOutboxPublishSupport outboxPublishSupport;

  private final JobPartitionMapper partitionMapper;

  private final PartitionReclaimUnit reclaimUnit;

  private final AdminTestDataCleanupService cleanupService;

  private final KafkaTemplate<String, String> kafkaTemplate;

  @MockitoSpyBean
  private TaskControllerApplicationService taskController;

  @LocalServerPort
  private int serverPort;

  @Autowired
  SdkOrdinaryLeaseRecoveryE2eIT(
      JdbcTemplate platformJdbc,
      WorkflowRunLaunchService launchService,
      E2eOutboxPublishSupport outboxPublishSupport,
      JobPartitionMapper partitionMapper,
      PartitionReclaimUnit reclaimUnit,
      AdminTestDataCleanupService cleanupService,
      KafkaTemplate<String, String> kafkaTemplate) {
    this.platformJdbc = platformJdbc;
    this.launchService = launchService;
    this.outboxPublishSupport = outboxPublishSupport;
    this.partitionMapper = partitionMapper;
    this.reclaimUnit = reclaimUnit;
    this.cleanupService = cleanupService;
    this.kafkaTemplate = kafkaTemplate;
  }

  @Test
  @DisplayName("独立 SDK 业务提交后强杀进程，租约回收为 UNKNOWN 且原消息不能重复动作")
  void shouldBlockUnsafeEffectAfterSdkProcessIsKilled() throws Exception {
    try (Scenario scenario = new Scenario()) {
      scenario.prepare();
      try (SdkChild child = new SdkChild(scenario, true, "http://127.0.0.1:" + serverPort)) {
        child.awaitReady();
        launch(scenario);
        await().atMost(WAIT).untilAsserted(() -> {
          assertThat(Files.exists(child.committed)).isTrue();
          assertEffectCount(scenario, 1L);
        });
        Map<String, Object> running = task(scenario);
        assertThat(running.get("task_status")).isEqualTo("RUNNING");
        assertThat(running.get("non_idempotent_started_at")).isNotNull();
        long taskId = ((Number) running.get("id")).longValue();
        long partitionId = ((Number) running.get("job_partition_id")).longValue();
        String originalPayload = dispatchPayload(scenario, taskId, false);
        child.kill();
        expireAndReclaim(scenario, partitionId);
        Map<String, Object> closed = task(scenario);
        assertThat(closed)
            .containsEntry("task_status", "FAILED")
            .containsEntry("error_code", "EXTERNAL_OUTCOME_UNKNOWN");
        Long retries = platformJdbc.queryForObject(RETRY_COUNT_SQL, Long.class, scenario.tenant);
        assertThat(retries).isZero();
        AtomicInteger rejectedClaims = new AtomicInteger();
        doAnswer(invocation -> {
              try {
                return invocation.callRealMethod();
              } catch (BizException rejected) {
                if ("error.task.claim_not_available".equals(rejected.getMessageKey())) {
                  rejectedClaims.incrementAndGet();
                }
                throw rejected;
              }
            })
            .when(taskController)
            .claim(eq(taskId), any(TaskClaimCommand.class));
        startWorker(scenario, "replacement", false, false);
        kafkaTemplate
            .send(BatchTopics.TASK_DISPATCH_ATOMIC, Long.toString(taskId), originalPayload)
            .get(10, TimeUnit.SECONDS);
        await()
            .atMost(WAIT)
            .untilAsserted(() -> assertThat(rejectedClaims.get()).isPositive());
        assertThat(scenario.executions.get()).isZero();
        assertEffectCount(scenario, 1L);
      }
    }
  }

  @Test
  @DisplayName("独立 SDK REPORT 提交后真实断开 HTTP 响应，重试幂等且业务效果唯一")
  void shouldRecoverSdkReportAfterCommittedResponseIsDisconnected() throws Exception {
    try (Scenario scenario = new Scenario()) {
      scenario.prepare();
      AtomicInteger reports = new AtomicInteger();
      List<String> reportKeys = new CopyOnWriteArrayList<>();
      List<String> reportBodies = new CopyOnWriteArrayList<>();
      CountDownLatch dropped = new CountDownLatch(1);
      HttpClient forwardingClient = HttpClient.newHttpClient();
      ExecutorService forwardingExecutor = Executors.newCachedThreadPool();
      HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      proxy.setExecutor(forwardingExecutor);
      proxy.createContext("/", exchange -> {
        try {
          byte[] requestBody = exchange.getRequestBody().readAllBytes();
          URI target = URI.create("http://127.0.0.1:" + serverPort + exchange.getRequestURI());
          HttpRequest.Builder request = HttpRequest.newBuilder(target)
              .method(
                  exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
          exchange.getRequestHeaders().forEach((name, values) -> {
            if (!List.of("host", "connection", "content-length", "upgrade", "http2-settings")
                .contains(name.toLowerCase(Locale.ROOT))) {
              values.forEach(value -> request.header(name, value));
            }
          });
          HttpResponse<byte[]> response =
              forwardingClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
          if (exchange.getRequestURI().getPath().endsWith("/report")) {
            reportKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            reportBodies.add(new String(requestBody, StandardCharsets.UTF_8));
            if (reports.getAndIncrement() == 0) {
              assertThat(response.statusCode()).isEqualTo(200);
              dropped.countDown();
              // 后端已经提交；不发送任何 HTTP 状态行，实际关闭 SDK 的响应连接。
              return;
            }
          }
          response
              .headers()
              .firstValue("Content-Type")
              .ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
          exchange.sendResponseHeaders(response.statusCode(), response.body().length);
          exchange.getResponseBody().write(response.body());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        } finally {
          exchange.close();
        }
      });
      proxy.start();
      try (SdkChild child =
          new SdkChild(scenario, false, "http://127.0.0.1:" + proxy.getAddress().getPort())) {
        child.awaitReady();
        launch(scenario);
        assertThat(dropped.await(45, TimeUnit.SECONDS)).isTrue();
        await().atMost(WAIT).untilAsserted(() -> {
          assertThat(reports.get()).isGreaterThanOrEqualTo(2);
          Map<String, Object> completed = task(scenario);
          assertThat(completed.get("task_status")).isEqualTo("SUCCESS");
          assertEffectCount(scenario, 1L);
        });
        assertThat(reportKeys).doesNotContainNull().allMatch(reportKeys.getFirst()::equals);
        assertThat(reportBodies).allMatch(reportBodies.getFirst()::equals);
      } finally {
        proxy.stop(0);
        forwardingExecutor.shutdownNow();
      }
    }
  }

  private final class SdkChild implements AutoCloseable {
    private final Path directory;
    private final Path committed;
    private final Path ready;
    private final Path log;
    private final Process process;

    private SdkChild(Scenario scenario, boolean block, String baseUrl) throws Exception {
      directory = Files.createTempDirectory("sdk-fault-boundary-");
      committed = directory.resolve("committed");
      ready = directory.resolve("ready");
      log = directory.resolve("worker.log");
      Properties properties = new Properties();
      properties.setProperty("business.url", businessJdbcUrl());
      properties.setProperty("business.username", businessJdbcUsername());
      properties.setProperty("business.password", businessJdbcPassword());
      properties.setProperty("tenant", scenario.tenant);
      properties.setProperty("marker", scenario.marker);
      properties.setProperty("base-url", baseUrl);
      properties.setProperty("worker-code", scenario.workerCode("first"));
      properties.setProperty("group", scenario.tenant + "-consumer");
      properties.setProperty("kafka-bootstrap", KAFKA.getBootstrapServers());
      properties.setProperty("block-after-commit", Boolean.toString(block));
      properties.setProperty("committed", committed.toString());
      properties.setProperty("ready", ready.toString());
      Path configuration = directory.resolve("worker.properties");
      try (var output = Files.newOutputStream(configuration)) {
        properties.store(output, "isolated SDK fault fixture");
      }
      String classpath =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      process = new ProcessBuilder(
              java,
              "-cp",
              classpath,
              SdkCommittedEffectChildProcess.class.getName(),
              configuration.toString())
          .redirectErrorStream(true)
          .redirectOutput(log.toFile())
          .start();
    }

    private void awaitReady() {
      await().atMost(WAIT).untilAsserted(() -> {
        assertThat(process.isAlive()).as("SDK child log: %s", log).isTrue();
        assertThat(Files.exists(ready)).isTrue();
        String selectWorkerSql = "select count(*) from batch.worker_registry where worker_code = ?";
        String worker = "";
        try (var input = Files.newInputStream(directory.resolve("worker.properties"))) {
          Properties properties = new Properties();
          properties.load(input);
          worker = properties.getProperty("worker-code");
        }
        Integer workers = platformJdbc.queryForObject(selectWorkerSql, Integer.class, worker);
        assertThat(workers).isPositive();
      });
    }

    private void kill() throws Exception {
      process.destroyForcibly();
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(process.isAlive()).isFalse();
    }

    @Override
    public void close() throws Exception {
      kill();
      Files.deleteIfExists(directory.resolve("worker.properties"));
    }
  }

  @Test
  @DisplayName("普通 INSERT 已提交后 Error 退栈，过期回收为 UNKNOWN 且原消息再投也不能重复动作")
  void shouldBlockUnsafeEffectAfterSdkExecutionTerminates() throws Exception {
    try (Scenario scenario = new Scenario()) {
      scenario.prepare();
      BatchPlatformClient first = startWorker(scenario, "first", false, true);
      launch(scenario);
      assertCrashedAfterCommittedEffect(scenario, first, true);
      Map<String, Object> before = task(scenario);
      long taskId = ((Number) before.get("id")).longValue();
      long partitionId = ((Number) before.get("job_partition_id")).longValue();
      String originalPayload = dispatchPayload(scenario, taskId, false);
      first.stop(Duration.ofSeconds(5));
      assertFirstDispatchCommitted(scenario);

      expireAndReclaim(scenario, partitionId);

      Map<String, Object> closed = task(scenario);
      assertThat(closed.get("task_status")).isEqualTo("FAILED");
      assertThat(closed.get("error_code")).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
      Long retries = platformJdbc.queryForObject(RETRY_COUNT_SQL, Long.class, scenario.tenant);
      assertThat(retries).isZero();
      long recovered = recoveryEventCount(scenario, taskId);
      assertThat(recovered).isZero();
      assertEffectCount(scenario, 1L);

      AtomicInteger rejectedClaims = new AtomicInteger();
      doAnswer(invocation -> {
            try {
              return invocation.callRealMethod();
            } catch (BizException rejected) {
              if (rejected.getCode() == ResultCode.NOT_FOUND
                  && "error.task.claim_not_available".equals(rejected.getMessageKey())) {
                rejectedClaims.incrementAndGet();
              }
              throw rejected;
            }
          })
          .when(taskController)
          .claim(eq(taskId), any(TaskClaimCommand.class));
      BatchPlatformClient replacement = startWorker(scenario, "replacement", false, false);
      String topic = BatchTopics.TASK_DISPATCH_ATOMIC;
      for (int replay = 1; replay <= 6; replay++) {
        kafkaTemplate.send(topic, Long.toString(taskId), originalPayload).get(10, TimeUnit.SECONDS);
        int expectedRejections = replay;
        await().atMost(WAIT).untilAsserted(() -> {
          assertThat(rejectedClaims.get()).isEqualTo(expectedRejections);
          SdkClientMetrics metrics = replacement.metrics();
          assertThat(metrics.inFlightTaskCount()).isZero();
          assertThat(metrics.healthy()).isTrue();
        });
      }
      assertThat(scenario.executions.get()).isEqualTo(1);
      assertEffectCount(scenario, 1L);
      Map<String, Object> afterReplay = task(scenario);
      assertThat(afterReplay.get("task_status")).isEqualTo("FAILED");
      assertThat(afterReplay.get("error_code")).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
    }
  }

  @Test
  @DisplayName("同 owner 的执行 Error 已退栈但尚未回收时，延迟重投原任务不得产生第二次真实业务效果")
  void shouldNotRepeatUnsafeEffect_whenSameInvocationIsRedeliveredAfterError() throws Exception {
    try (Scenario scenario = new Scenario()) {
      scenario.prepare();
      BatchPlatformClient first = startWorker(scenario, "first", false, true);
      // 只观察真实执行器公开的提交计数，覆盖CLAIM阶段；不替换执行器或模拟业务动作。
      TaskDispatcher dispatcher =
          (TaskDispatcher) ReflectionTestUtils.getField(first, "dispatcher");
      assertThat(dispatcher).isNotNull();
      launch(scenario);
      assertCrashedAfterCommittedEffect(scenario, first, true);
      Map<String, Object> before = task(scenario);
      long taskId = ((Number) before.get("id")).longValue();
      long partitionId = ((Number) before.get("job_partition_id")).longValue();
      String firstInvocation = String.valueOf(before.get("current_invocation_id"));
      String originalPayload = dispatchPayload(scenario, taskId, false);
      String topic = BatchTopics.directDispatchTopic(
          BatchTopics.TASK_DISPATCH_ATOMIC, scenario.workerCode("first"));

      var replay = kafkaTemplate
          .send(topic, Long.toString(taskId), originalPayload)
          .get(10, TimeUnit.SECONDS);
      long nextOffset = replay.getRecordMetadata().offset() + 1L;
      awaitRedeliverySettled(scenario, first, dispatcher, topic, nextOffset);

      assertThat(first.isHealthy()).isFalse();
      assertEffectCount(scenario, 1L);
      assertThat(scenario.executions.get()).isEqualTo(1);
      assertThat(scenario.invocations).containsExactly(firstInvocation);
      Map<String, Object> afterReplay = task(scenario);
      assertThat(afterReplay.get("task_status")).isEqualTo("RUNNING");
      assertThat(afterReplay.get("current_invocation_id")).isEqualTo(firstInvocation);
      assertThat(afterReplay.get("non_idempotent_started_at")).isNotNull();
      first.stop(Duration.ofSeconds(5));
      assertFirstDispatchCommitted(scenario);
      expireAndReclaim(scenario, partitionId);
      Map<String, Object> closed = task(scenario);
      assertThat(closed.get("task_status")).isEqualTo("FAILED");
      assertThat(closed.get("error_code")).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
      Long retries = platformJdbc.queryForObject(RETRY_COUNT_SQL, Long.class, scenario.tenant);
      assertThat(retries).isZero();
      assertEffectCount(scenario, 1L);
    }
  }

  @Test
  @DisplayName("真实稳定业务键幂等动作提交后 Error 退栈，经生产 RECOVER outbox 和新 CLAIM 成功恢复")
  void shouldRecoverSafeEffectThroughProductionOutboxAndKafka() throws Exception {
    try (Scenario scenario = new Scenario()) {
      scenario.prepare();
      BatchPlatformClient first = startWorker(scenario, "first", true, true);
      launch(scenario);
      assertCrashedAfterCommittedEffect(scenario, first, false);
      Map<String, Object> before = task(scenario);
      long taskId = ((Number) before.get("id")).longValue();
      long partitionId = ((Number) before.get("job_partition_id")).longValue();
      String firstInvocation = String.valueOf(before.get("current_invocation_id"));
      first.stop(Duration.ofSeconds(5));
      assertFirstDispatchCommitted(scenario);

      expireAndReclaim(scenario, partitionId);

      Map<String, Object> ready = task(scenario);
      assertThat(ready.get("task_status")).isEqualTo("READY");
      long recovered = recoveryEventCount(scenario, taskId);
      assertThat(recovered).isEqualTo(1L);
      String selectRunModeSql = """
          select task_payload ->> 'run_mode' from batch.job_task where tenant_id = ? and id = ?
          """;
      String recoveredRunMode =
          platformJdbc.queryForObject(selectRunModeSql, String.class, scenario.tenant, taskId);
      assertThat(recoveredRunMode).isEqualTo("RECOVER");
      startWorker(scenario, "replacement", true, false);
      outboxPublishSupport.publishAllPending(scenario.tenant);
      await().atMost(WAIT).untilAsserted(() -> {
        Map<String, Object> finished = task(scenario);
        assertThat(finished.get("task_status")).isEqualTo("SUCCESS");
        assertThat(finished.get("non_idempotent_started_at")).isNull();
      });
      assertThat(scenario.executions.get()).isEqualTo(2);
      assertThat(scenario.invocations).hasSize(2).doesNotHaveDuplicates();
      assertThat(scenario.invocations.getFirst()).isEqualTo(firstInvocation);
      assertEffectCount(scenario, 1L);
      String selectWorkflowStatusSql = """
          select run_status from batch.workflow_run
           where tenant_id = ? and trigger_request_id = (
               select id from batch.trigger_request where tenant_id = ? and request_id = ?)
          """;
      await().atMost(WAIT).untilAsserted(() -> {
        String status = platformJdbc.queryForObject(
            selectWorkflowStatusSql,
            String.class,
            scenario.tenant,
            scenario.tenant,
            scenario.seed.requestId());
        assertThat(status).isEqualTo("SUCCESS");
      });
    }
  }

  private void launch(Scenario scenario) {
    Map<String, Object> parameters = Map.of("recoveryMarker", scenario.marker);
    LaunchRequest request = new LaunchRequest(
        scenario.tenant,
        scenario.seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        scenario.seed.requestId(),
        scenario.marker,
        parameters);
    launchService.launch(request);
    outboxPublishSupport.publishAllPending(scenario.tenant);
  }

  private BatchPlatformClient startWorker(
      Scenario scenario, String suffix, boolean safe, boolean crash) throws Exception {
    String workerCode = scenario.workerCode(suffix);
    String groupId = scenario.tenant + "-consumer";
    BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
        .baseUrl("http://127.0.0.1:" + serverPort)
        .tenantId(scenario.tenant)
        .workerCode(workerCode)
        .kafkaBootstrap(KAFKA.getBootstrapServers())
        .kafkaTopicPattern(Pattern.quote(BatchTopics.TASK_DISPATCH_ATOMIC) + ".*")
        .kafkaGroupId(groupId)
        .maxConcurrentTasks(1)
        .kafkaPollInterval(Duration.ofMillis(100))
        .heartbeatInterval(Duration.ofSeconds(10))
        .leaseRenewInterval(Duration.ofSeconds(10))
        .httpTimeout(Duration.ofSeconds(5))
        .build();
    SdkTaskHandler handler = new BusinessEffectHandler(scenario, safe, crash);
    BatchPlatformClient client =
        BatchPlatformClient.builder(config).register(handler).build();
    scenario.clients.add(client);
    client.start();
    try (AdminClient admin = adminClient()) {
      await().atMost(WAIT).untilAsserted(() -> {
        SdkClientMetrics metrics = client.metrics();
        assertThat(metrics.healthy()).isTrue();
        try {
          var descriptions =
              admin.describeConsumerGroups(List.of(groupId)).all().get(5, TimeUnit.SECONDS);
          var group = descriptions.get(groupId);
          boolean assigned = group.members().stream()
              .anyMatch(member -> member.assignment().topicPartitions().stream()
                  .anyMatch(
                      partition -> BatchTopics.TASK_DISPATCH_ATOMIC.equals(partition.topic())));
          assertThat(assigned).isTrue();
        } catch (ExecutionException missing) {
          if (missing.getCause() instanceof GroupIdNotFoundException) {
            throw new AssertionError("waiting for SDK consumer group registration", missing);
          }
          throw missing;
        }
      });
    }
    return client;
  }

  private void assertCrashedAfterCommittedEffect(
      Scenario scenario, BatchPlatformClient client, boolean marked) throws Exception {
    boolean exited = scenario.crashExited.await(45, TimeUnit.SECONDS);
    assertThat(exited).isTrue();
    assertThat(scenario.uncaught.get()).isSameAs(scenario.crash);
    await().atMost(WAIT).untilAsserted(() -> {
      SdkClientMetrics metrics = client.metrics();
      assertThat(metrics.inFlightTaskCount()).isZero();
      Map<String, Object> running = task(scenario);
      assertThat(running.get("task_status")).isEqualTo("RUNNING");
      assertThat(running.get("current_invocation_id")).isNotNull();
      if (marked) assertThat(running.get("non_idempotent_started_at")).isNotNull();
      else assertThat(running.get("non_idempotent_started_at")).isNull();
      assertEffectCount(scenario, 1L);
    });
  }

  private void expireAndReclaim(Scenario scenario, long partitionId) {
    String expireLeaseSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 second'
         where tenant_id = ? and id = ? and partition_status = 'RUNNING'
        """;
    int expired = platformJdbc.update(expireLeaseSql, scenario.tenant, partitionId);
    assertThat(expired).isEqualTo(1);
    JobPartitionEntity partition = partitionMapper.selectById(scenario.tenant, partitionId);
    reclaimUnit.reclaim(partition);
  }

  private Map<String, Object> task(Scenario scenario) {
    return platformJdbc.queryForMap(
        SELECT_TASK_SQL, scenario.tenant, scenario.seed.jobCode(), BIZ_DATE);
  }

  private void assertEffectCount(Scenario scenario, long expected) {
    Long actual =
        scenario.businessJdbc.queryForObject(EFFECT_COUNT_SQL, Long.class, scenario.tenant);
    assertThat(actual).isEqualTo(expected);
  }

  private long recoveryEventCount(Scenario scenario, long taskId) {
    String countSql = """
        select count(*) from batch.outbox_event
         where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
           and event_key like ?
        """;
    Long result = platformJdbc.queryForObject(
        countSql, Long.class, scenario.tenant, taskId, scenario.tenant + ":reclaim:%");
    return result;
  }

  private String dispatchPayload(Scenario scenario, long taskId, boolean recovered) {
    String selectSql = """
        select payload_json::text from batch.outbox_event
         where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
           and (event_key like ?) = ? order by id limit 1
        """;
    return platformJdbc.queryForObject(
        selectSql,
        String.class,
        scenario.tenant,
        taskId,
        scenario.tenant + ":reclaim:%",
        recovered);
  }

  private void awaitRedeliverySettled(
      Scenario scenario,
      BatchPlatformClient client,
      TaskDispatcher dispatcher,
      String topic,
      long nextOffset)
      throws Exception {
    TopicPartition partition = new TopicPartition(topic, 0);
    try (AdminClient admin = adminClient()) {
      await().atMost(WAIT).untilAsserted(() -> {
        SdkClientMetrics metrics = client.metrics();
        if (!dispatcher.isFatal() && metrics.started()) {
          var offsets = admin
              .listConsumerGroupOffsets(scenario.tenant + "-consumer")
              .partitionsToOffsetAndMetadata()
              .get(5, TimeUnit.SECONDS);
          OffsetAndMetadata committed = offsets.get(partition);
          assertThat(committed)
              .as("actual delayed replay reached the SDK consumer")
              .isNotNull();
          assertThat(committed.offset()).isGreaterThanOrEqualTo(nextOffset);
        }
        // Kafka的SUBMITTED回执早于异步执行；提交计数归零后才读取业务库，避免假通过。
        assertThat(dispatcher.submittedCount()).isZero();
      });
    }
  }

  private void assertFirstDispatchCommitted(Scenario scenario) throws Exception {
    String topic = BatchTopics.directDispatchTopic(
        BatchTopics.TASK_DISPATCH_ATOMIC, scenario.workerCode("first"));
    TopicPartition partition = new TopicPartition(topic, 0);
    try (AdminClient admin = adminClient()) {
      var offsets = admin
          .listConsumerGroupOffsets(scenario.tenant + "-consumer")
          .partitionsToOffsetAndMetadata()
          .get(10, TimeUnit.SECONDS);
      OffsetAndMetadata committed = offsets.get(partition);
      assertThat(committed)
          .as("first message is committed before recovery publication")
          .isNotNull();
      assertThat(committed.offset()).isGreaterThanOrEqualTo(1L);
    }
  }

  private AdminClient adminClient() {
    Map<String, Object> properties =
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    return AdminClient.create(properties);
  }

  private final class BusinessEffectHandler implements SdkTaskHandler {
    private final Scenario scenario;
    private final boolean safe;
    private final boolean crash;

    private BusinessEffectHandler(Scenario scenario, boolean safe, boolean crash) {
      this.scenario = scenario;
      this.safe = safe;
      this.crash = crash;
    }

    @Override
    public String taskType() {
      return "ATOMIC";
    }

    @Override
    public boolean isSafeToReplay(SdkTaskContext context) {
      return safe;
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext context) {
      Object marker = context.parameters().get("recoveryMarker");
      if (!scenario.marker.equals(marker)) {
        throw new IllegalStateException("CLAIM did not preserve the frozen business marker");
      }
      String invocation = (String) context.runtimeAttributes().get("partitionInvocationId");
      scenario.invocations.add(invocation);
      int execution = scenario.executions.incrementAndGet();
      String customerNo = safe ? "stable-business-key" : "unsafe-effect-" + execution;
      String insertEffectSql = """
          insert into biz.customer_account
              (tenant_id, customer_no, customer_name, customer_type, status, source_batch_no)
          values (?, ?, ?, 'PERSONAL', 'ACTIVE', ?)
          """;
      String idempotentInsertSql =
          insertEffectSql + " on conflict (tenant_id, customer_no) do nothing";
      String writeSql = safe ? idempotentInsertSql : insertEffectSql;
      scenario.businessJdbc.update(
          writeSql, scenario.tenant, customerNo, scenario.marker, scenario.marker);
      String selectEffectSql = """
          select customer_name, source_batch_no from biz.customer_account
           where tenant_id = ? and customer_no = ?
          """;
      Map<String, Object> effect =
          scenario.businessJdbc.queryForMap(selectEffectSql, scenario.tenant, customerNo);
      if (!scenario.marker.equals(effect.get("customer_name"))
          || !scenario.marker.equals(effect.get("source_batch_no"))) {
        throw new IllegalStateException("existing business key has different frozen effect data");
      }
      if (crash) {
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
          scenario.uncaught.set(error);
          scenario.crashExited.countDown();
        });
        throw scenario.crash;
      }
      return SdkTaskResult.ok("committed frozen effect", Map.of("marker", scenario.marker));
    }
  }

  private final class Scenario implements AutoCloseable {
    private final String marker = UUID.randomUUID().toString();
    private final String tenant = "sdk193-" + marker.substring(0, 12);
    private final List<BatchPlatformClient> clients = new ArrayList<>();
    private final List<String> createdTopics = new ArrayList<>();
    private boolean consumerOffsetsSeeded;
    private final AtomicInteger executions = new AtomicInteger();
    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private final CountDownLatch crashExited = new CountDownLatch(1);
    private final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    private final Error crash =
        new AssertionError("SDK execution terminated after business commit");
    private final JdbcTemplate businessJdbc;
    private LaunchSeed seed;

    private Scenario() {
      DriverManagerDataSource businessDataSource = new DriverManagerDataSource(
          businessJdbcUrl(), businessJdbcUsername(), businessJdbcPassword());
      businessJdbc = new JdbcTemplate(businessDataSource);
    }

    private String workerCode(String suffix) {
      return tenant + "-" + suffix;
    }

    private void prepare() throws Exception {
      String insertTenantSql = """
          insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
          """;
      platformJdbc.update(insertTenantSql, tenant, tenant);
      LaunchPreparationSpec preparation = new LaunchPreparationSpec(
          platformJdbc, tenant, "ATOMIC", "sdk-self-hosted", TriggerType.API);
      preparation.bizDate(BIZ_DATE).retryPolicy("FIXED").retryMaxCount(2);
      seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(preparation);
      try (AdminClient admin = adminClient()) {
        String firstTopic =
            BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_ATOMIC, workerCode("first"));
        String replacementTopic = BatchTopics.directDispatchTopic(
            BatchTopics.TASK_DISPATCH_ATOMIC, workerCode("replacement"));
        List<String> topics =
            List.of(BatchTopics.TASK_DISPATCH_ATOMIC, firstTopic, replacementTopic);
        for (String topic : topics) {
          try {
            NewTopic newTopic = new NewTopic(topic, 1, (short) 1);
            admin.createTopics(List.of(newTopic)).all().get(10, TimeUnit.SECONDS);
            if (!BatchTopics.TASK_DISPATCH_ATOMIC.equals(topic)) createdTopics.add(topic);
          } catch (ExecutionException exists) {
            if (!(exists.getCause() instanceof TopicExistsException)) throw exists;
          }
        }
        // 固定空消费组的起点，避免 SDK latest 在异步分配后才取日志末尾而漏掉首次派单。
        Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
        for (String topic : topics) latest.put(new TopicPartition(topic, 0), OffsetSpec.latest());
        await().atMost(WAIT).untilAsserted(() -> {
          try {
            var ends = admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> initialOffsets = new LinkedHashMap<>();
            ends.forEach((partition, end) ->
                initialOffsets.put(partition, new OffsetAndMetadata(end.offset())));
            admin
                .alterConsumerGroupOffsets(tenant + "-consumer", initialOffsets)
                .all()
                .get(10, TimeUnit.SECONDS);
            consumerOffsetsSeeded = true;
          } catch (ExecutionException propagation) {
            if (propagation.getCause() instanceof UnknownTopicOrPartitionException) {
              throw new AssertionError("waiting for newly created topic metadata", propagation);
            }
            throw propagation;
          }
        });
      }
    }

    @Override
    public void close() {
      RuntimeException cleanupFailure = null;
      for (BatchPlatformClient client : clients) {
        try {
          client.stop(Duration.ofSeconds(5));
        } catch (RuntimeException failure) {
          if (cleanupFailure == null) cleanupFailure = failure;
          else cleanupFailure.addSuppressed(failure);
        }
      }
      try {
        cleanupOwnedData(this);
      } catch (RuntimeException failure) {
        if (cleanupFailure == null) cleanupFailure = failure;
        else cleanupFailure.addSuppressed(failure);
      }
      try {
        cleanupKafka();
      } catch (Exception failure) {
        if (cleanupFailure == null)
          cleanupFailure = new IllegalStateException("owned Kafka cleanup failed", failure);
        else cleanupFailure.addSuppressed(failure);
      }
      if (cleanupFailure != null) throw cleanupFailure;
    }

    private void cleanupKafka() throws Exception {
      try (AdminClient admin = adminClient()) {
        if (consumerOffsetsSeeded) {
          // SIGKILL 无法发送 LeaveGroup；仅移除本场景自有组中已停止的进程成员。
          var group = admin
              .describeConsumerGroups(List.of(tenant + "-consumer"))
              .all()
              .get(10, TimeUnit.SECONDS)
              .get(tenant + "-consumer");
          if (!group.members().isEmpty()) {
            admin
                .removeMembersFromConsumerGroup(
                    tenant + "-consumer", new RemoveMembersFromConsumerGroupOptions())
                .all()
                .get(10, TimeUnit.SECONDS);
          }
          admin.deleteConsumerGroups(List.of(tenant + "-consumer")).all().get(10, TimeUnit.SECONDS);
        }
        if (!createdTopics.isEmpty()) {
          admin.deleteTopics(createdTopics).all().get(10, TimeUnit.SECONDS);
        }
      }
    }
  }

  private void cleanupOwnedData(Scenario scenario) {
    String deleteRetriesSql = "delete from batch.retry_schedule where tenant_id = ?";
    String deleteDeadLettersSql = "delete from batch.dead_letter_task where tenant_id = ?";
    String deleteWorkersSql = "delete from batch.worker_registry where tenant_id = ?";
    String deleteCalendarsSql = "delete from batch.business_calendar where tenant_id = ?";
    String deleteCatchupControlsSql =
        "delete from batch.batch_day_catchup_control where tenant_id = ?";
    String deleteEffectsSql = "delete from biz.customer_account where tenant_id = ?";
    platformJdbc.update(deleteRetriesSql, scenario.tenant);
    platformJdbc.update(deleteDeadLettersSql, scenario.tenant);
    cleanupService.cleanupByExactTenantIds(scenario.tenant);
    platformJdbc.update(deleteWorkersSql, scenario.tenant);
    platformJdbc.update(deleteCatchupControlsSql, scenario.tenant);
    platformJdbc.update(deleteCalendarsSql, scenario.tenant);
    scenario.businessJdbc.update(deleteEffectsSql, scenario.tenant);
    String selectRemainingOwnedSql = """
        select
          (select count(*) from batch.job_task where tenant_id = ?) +
          (select count(*) from batch.job_instance where tenant_id = ?) +
          (select count(*) from batch.workflow_run where tenant_id = ?) +
          (select count(*) from batch.trigger_request where tenant_id = ?) +
          (select count(*) from batch.outbox_event where tenant_id = ?) +
          (select count(*) from batch.worker_registry where tenant_id = ?) +
          (select count(*) from batch.business_calendar where tenant_id = ?) +
          (select count(*) from batch.retry_schedule where tenant_id = ?) +
          (select count(*) from batch.dead_letter_task where tenant_id = ?)
        """;
    Long remaining = platformJdbc.queryForObject(
        selectRemainingOwnedSql,
        Long.class,
        scenario.tenant,
        scenario.tenant,
        scenario.tenant,
        scenario.tenant,
        scenario.tenant,
        scenario.tenant,
        scenario.tenant,
        scenario.tenant,
        scenario.tenant);
    assertThat(remaining).as("owned platform fixture cleanup").isZero();
    assertEffectCount(scenario, 0L);
  }
}
