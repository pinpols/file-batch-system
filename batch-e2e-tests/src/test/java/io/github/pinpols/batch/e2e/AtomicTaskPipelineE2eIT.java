package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eAtomicApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 端到端测试:专用原子任务 worker 四类原子任务主链路成功闭环(ADR-029)。
 *
 * <p>链路路径(以 sql 为例):
 *
 * <pre>
 * launch(job_type=ATOMIC, params={taskType:&lt;type&gt;, ...})
 *   → orchestrator 建 task/outbox(task_type=ATOMIC,payload 带子协议)
 *   → Kafka 派发到 batch.task.dispatch.atomic
 *   → 原子任务 worker(只扫 worker.atomic,无 pipeline adapter)claim
 *   → DefaultStepExecutionAdapter 按 payload.taskType 路由到对应执行器执行
 *   → report → orchestrator 终态 SUCCESS
 * </pre>
 *
 * <p>四个执行器(sql/shell/stored-proc/http)各跑一条真实全链,验证派发拓扑(原子任务专属 topic)+ 子执行器路由(payload.taskType)+ 参数透传
 * + 终态闭环。
 */
@E2eSpringBootTest(
    classes = E2eAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.retry.poll-interval-millis=600000",
      "batch.outbox.poll-interval-millis=600000"
    })
@E2eBusinessSchema
@Tag("critical")
@DisplayName("原子任务主链路端到端: 触发后建任务并写入事件, 经 Kafka 专属主题派发, 子执行器按任务协议路由执行并回执, 任务终态落库为成功")
class AtomicTaskPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private RetryGovernanceService retryGovernanceService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  @MockitoSpyBean
  private TaskControllerApplicationService taskControllerApplicationService;

  @Test
  @DisplayName("sql 原子任务全链路: 触发后经专属主题派发并路由到对应执行器, 任务终态落库为成功")
  void shouldRunSqlAtomicTask_whenDispatchedThroughKafka() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "sql");
    params.put("sql", "SELECT 1");

    launchAndAwaitSuccess(params);
  }

  @Test
  @DisplayName("shell 原子任务全链路: 触发后经专属主题派发并路由到对应执行器执行无害命令, 任务终态落库为成功")
  void shouldRunShellAtomicTask_whenDispatchedThroughKafka() {
    // shell 走 adapter 的 payload.taskType 路由 + RCE 最敏感执行器,真链路只跑无害的 /bin/echo。
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "shell");
    params.put("command", "/bin/echo");
    params.put("args", List.of("hello-from-spi-e2e"));

    launchAndAwaitSuccess(params);
  }

  @Test
  @DisplayName("存储过程原子任务全链路: 先建真实过程, 触发后经专属主题派发并按过程定义调用, 任务终态落库为成功")
  void shouldRunStoredProcAtomicTask_whenDispatchedThroughKafka() {
    final String createProcedureSql = """
        CREATE OR REPLACE PROCEDURE batch.e2e_spi_proc() LANGUAGE plpgsql AS $$ BEGIN END; $$
    """;

    // 真 PROCEDURE:executor 经 pg_proc.prokind 判定后发原生 CALL(PG 默认 {call}→SELECT 调真过程会报错)。
    jdbcTemplate.execute(
        createProcedureSql);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "stored_proc");
    params.put("procedureName", "batch.e2e_spi_proc");

    launchAndAwaitSuccess(params);
  }

  @Test
  @DisplayName("http 原子任务全链路: 触发后经专属主题派发并请求本地服务, 任务终态落库为成功")
  void shouldRunHttpAtomicTask_whenDispatchedThroughKafka() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ok", exchange -> {
      byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.start();
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("taskType", "http");
      params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/ok");
      params.put("method", "GET");

      launchAndAwaitSuccess(params);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("HTTP 原子任务 dry-run 成功收口但不会向目标服务发送请求")
  void shouldSkipHttpRequest_whenAtomicHttpDryRunCompletes() throws IOException {
    final String selectRunStatusSql = """
                select wr.run_status, ji.instance_status, task.task_status
                from batch.trigger_request tr
                join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
                    and wr.trigger_request_id = tr.id
                join batch.workflow_node_run node_run on node_run.workflow_run_id = wr.id
                    and node_run.node_type = 'JOB'
                join batch.job_instance ji on ji.tenant_id = wr.tenant_id
                    and ji.id = node_run.job_instance_id
                join batch.job_task task on task.tenant_id = ji.tenant_id
                    and task.job_instance_id = ji.id
                where tr.tenant_id = ? and tr.request_id = ?
                """;

    AtomicInteger receivedRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/dry-run", exchange -> {
      receivedRequests.incrementAndGet();
      byte[] body = "unexpected request".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    try {
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          new E2eScenarioFixture.LaunchPreparationSpec(
                  jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API)
              .dryRun(true));
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("taskType", "http");
      params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/dry-run");
      params.put("method", "GET");

      launchService.launch(LaunchRequest.builder()
          .tenantId(TENANT)
          .workflowDefinitionId(seed.workflowDefinitionId())
          .bizDate(LocalDate.of(2026, 1, 15))
          .triggerType(TriggerType.API)
          .requestId(seed.requestId())
          .traceId("e2e-tr-atomic-http-dry-run")
          .params(params)
          .dryRun(true)
          .build());
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            Map<String, Object> run = jdbcTemplate.queryForMap(selectRunStatusSql, TENANT, seed.requestId());
            assertThat(run)
                .containsEntry("run_status", "SUCCESS_DRY_RUN")
                .containsEntry("instance_status", "SUCCESS_DRY_RUN")
                .containsEntry("task_status", "SUCCESS");
          });
      assertThat(receivedRequests).hasValue(0);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("ATOMIC HTTP 消息重复投递时,目标服务只收到一次请求")
  void shouldSendHttpRequestOnce_whenAtomicDispatchMessageIsRedelivered() throws Exception {
    final String selectIdSql = """
          select task.id
          from batch.job_task task
          join batch.job_instance ji on ji.id = task.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;
    final String selectPayloadJsonSql = """
          select payload_json::text
          from batch.outbox_event
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
          order by id
          limit 1
          """;
    final String selectTaskStatusSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectRunStatusSql = """
          select wr.run_status
          from batch.trigger_request tr
          join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
              and wr.trigger_request_id = tr.id
          where tr.tenant_id = ? and tr.request_id = ?
          """;

    AtomicInteger receivedRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/redelivery", exchange -> {
      receivedRequests.incrementAndGet();
      byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    try {
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API);
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("taskType", "http");
      params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/redelivery");
      params.put("method", "GET");
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-tr-atomic-redelivery",
          params));

      Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      String payload = jdbcTemplate.queryForObject(selectPayloadJsonSql, String.class, TENANT, taskId);
      TaskDispatchMessage message = JsonUtils.fromJson(payload, TaskDispatchMessage.class);
      assertThat(message).isNotNull();
      assertThat(message.selectedWorkerId()).isNotBlank();

      AtomicReference<Long> observedTaskId = new AtomicReference<>(taskId);
      AtomicInteger claimAttempts = new AtomicInteger();
      doAnswer(invocation -> {
            if (observedTaskId.get().equals(invocation.getArgument(0))) {
              claimAttempts.incrementAndGet();
            }
            return invocation.callRealMethod();
          })
          .when(taskControllerApplicationService)
          .claim(any(Long.class), any(TaskClaimCommand.class));

      e2eOutboxPublishSupport.publishAllPending(TENANT);
      String dispatchTopic = BatchTopics.directDispatchTopic(
          BatchTopics.TASK_DISPATCH_ATOMIC, message.selectedWorkerId());
      String kafkaKey = TENANT + ":" + message.jobCode() + ":" + message.instanceNo() + ":task:"
          + message.taskId();
      kafkaTemplate.send(dispatchTopic, kafkaKey, payload).get();
      kafkaTemplate.send(dispatchTopic, kafkaKey, payload).get();

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            assertThat(claimAttempts.get()).isEqualTo(3);
            String selectedTaskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(selectedTaskStatus).isEqualTo("SUCCESS");
            assertThat(receivedRequests).hasValue(1);
          });
      String selectedRunStatus =
          jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, seed.requestId());
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("ATOMIC HTTP POST 结果不确定时不自动重试已提交的外部副作用")
  void shouldNotAutomaticallyRetry_whenAtomicHttpPostOutcomeIsUnknown() throws Exception {
    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ? where tenant_id = ?\s\
        and job_code = ?\
        """;
    final String selectIdSql = """
          select task.id
          from batch.job_task task
          join batch.job_instance ji on ji.id = task.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;
    final String selectIdSql2 = """
          select partition.id
          from batch.job_partition partition
          join batch.job_instance ji on ji.tenant_id = partition.tenant_id
              and ji.id = partition.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;
    final String selectIdSql3 = """
          select wr.id
          from batch.trigger_request tr
          join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
              and wr.trigger_request_id = tr.id
          where tr.tenant_id = ? and tr.request_id = ?
          """;
    final String selectTaskStatusSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
                select count(*) from batch.outbox_event
                where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'
                    and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'
                    and payload_json->>'runStatus' = 'FAILED'
                """;
    final String selectCountSql2 = """
          select count(*)::int from batch.retry_schedule
          where tenant_id = ? and related_type = 'JOB_PARTITION'
              and related_id = ? and retry_status = 'WAITING'
          """;
    final String selectCountSql3 = """
          select count(*)::int from batch.dead_letter_task
          where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
          """;
    final String selectErrorCodeSql = """
        select error_code from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectFailureClassSql = """
        select failure_class from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectErrorClassSql = """
          select error_class from batch.dead_letter_task
          where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
          """;
    final String selectMaxReplayCountSql = """
          select max_replay_count from batch.dead_letter_task
          where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
          """;

    AtomicInteger committedEffects = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/commit-then-fail", exchange -> {
      committedEffects.incrementAndGet();
      byte[] body = "upstream response lost after commit".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(503, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    try {
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API);
      jdbcTemplate.update(
          updateJobDefinitionSql,
          "FIXED",
          1,
          TENANT,
          seed.jobCode());
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("taskType", "http");
      params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/commit-then-fail");
      params.put("method", "POST");
      params.put("body", "commit-once");
      params.put("expectStatus", List.of(200));
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-tr-atomic-post-outcome-unknown",
          params));

      Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      Long partitionId = jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, seed.dedupKey());
      Long workflowRunId = jdbcTemplate.queryForObject(selectIdSql3, Long.class, TENANT, seed.requestId());
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            String selectedTaskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(selectedTaskStatus).isEqualTo("FAILED");
            String selectedRunStatus =
                jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
            assertThat(selectedRunStatus).isEqualTo("FAILED");
            Integer selectedOutboxEventCount =
                jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, workflowRunId);
            assertThat(selectedOutboxEventCount).isEqualTo(1);
          });

      assertThat(committedEffects).hasValue(1);
      Integer selectedCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, partitionId);
      assertThat(selectedCount).isZero();
      Integer selectedCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, partitionId);
      assertThat(selectedCountRead2).isEqualTo(1);
      String selectedErrorCode =
          jdbcTemplate.queryForObject(selectErrorCodeSql, String.class, TENANT, taskId);
      assertThat(selectedErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
      String selectedFailureClass =
          jdbcTemplate.queryForObject(selectFailureClassSql, String.class, TENANT, taskId);
      assertThat(selectedFailureClass).isEqualTo("UNKNOWN");
      String selectedErrorClass =
          jdbcTemplate.queryForObject(selectErrorClassSql, String.class, TENANT, partitionId);
      assertThat(selectedErrorClass).isEqualTo("SYSTEM");
      Integer selectedMaxReplayCount =
          jdbcTemplate.queryForObject(selectMaxReplayCountSql, Integer.class, TENANT, partitionId);
      assertThat(selectedMaxReplayCount).isZero();
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("ATOMIC HTTP 失败后 retry 成功,再投递过期 retry 消息不重复请求目标服务")
  void shouldNotRepeatHttpSideEffect_whenCompletedAtomicRetryMessageIsRedelivered()
      throws Exception {
        final String updateJobDefinitionSql = """
            update batch.job_definition set retry_policy = ?, retry_max_count = ? where tenant_id = ?\s\
            and job_code = ?\
            """;
        final String selectIdSql = """
          select task.id
          from batch.job_task task
          join batch.job_instance ji on ji.id = task.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;
        final String selectIdSql2 = """
          select partition.id
          from batch.job_partition partition
          join batch.job_instance ji on ji.tenant_id = partition.tenant_id
              and ji.id = partition.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;
        final String selectPayloadJsonSql = """
          select payload_json::text
          from batch.outbox_event
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
          order by id
          limit 1
          """;
        final String selectTaskStatusSql = """
            select task_status from batch.job_task where tenant_id = ? and id = ?
        """;
        final String selectCountSql = """
                select count(*)::int from batch.retry_schedule
                where tenant_id = ? and related_type = 'JOB_PARTITION'
                    and related_id = ? and retry_status = 'WAITING'
                """;
        final String updateRetryScheduleSql = """
          update batch.retry_schedule set next_retry_at = now() - interval '1 second'
          where tenant_id = ? and related_type = 'JOB_PARTITION'
              and related_id = ? and retry_status = 'WAITING'
          """;
        final String selectPayloadJsonSql2 = """
          select payload_json::text
          from batch.outbox_event
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
          order by id desc
          limit 1
          """;
    final String selectRunStatusSql = """
          select wr.run_status
          from batch.trigger_request tr
          join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
              and wr.trigger_request_id = tr.id
          where tr.tenant_id = ? and tr.request_id = ?
          """;

    AtomicInteger receivedRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/retry", exchange -> {
      int requestNo = receivedRequests.incrementAndGet();
      int status = requestNo == 1 ? 503 : 200;
      byte[] body = status == 200
          ? "ok".getBytes(StandardCharsets.UTF_8)
          : "retry".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    try {
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API);
      jdbcTemplate.update(
          updateJobDefinitionSql,
          "FIXED",
          1,
          TENANT,
          seed.jobCode());
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("taskType", "http");
      params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/retry");
      params.put("method", "GET");
      params.put("expectStatus", List.of(200));
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-tr-atomic-retry-redelivery",
          params));

      Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      Long partitionId = jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, seed.dedupKey());
      String initialPayload = jdbcTemplate.queryForObject(selectPayloadJsonSql, String.class, TENANT, taskId);
      TaskDispatchMessage initialDispatch =
          JsonUtils.fromJson(initialPayload, TaskDispatchMessage.class);
      assertThat(initialDispatch)
          .isNotNull()
          .extracting(TaskDispatchMessage::taskId)
          .isEqualTo(taskId);

      AtomicReference<Long> observedTaskId = new AtomicReference<>(taskId);
      AtomicInteger claimAttempts = new AtomicInteger();
      AtomicInteger successfulClaims = new AtomicInteger();
      doAnswer(invocation -> {
            if (observedTaskId.get().equals(invocation.getArgument(0))) {
              claimAttempts.incrementAndGet();
            }
            Object result = invocation.callRealMethod();
            if (observedTaskId.get().equals(invocation.getArgument(0))) {
              successfulClaims.incrementAndGet();
            }
            return result;
          })
          .when(taskControllerApplicationService)
          .claim(any(Long.class), any(TaskClaimCommand.class));
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            assertThat(receivedRequests).hasValue(1);
            assertThat(claimAttempts).hasValue(1);
            String selectedTaskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(selectedTaskStatus).isEqualTo("FAILED");
            Integer selectedCount =
                jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, partitionId);
            assertThat(selectedCount).isEqualTo(1);
          });

      int affectedRetryScheduleRows =
          jdbcTemplate.update(updateRetryScheduleSql, TENANT, partitionId);
      assertThat(affectedRetryScheduleRows).isEqualTo(1);
      retryGovernanceService.dispatchDueRetries();
      String retryPayload = jdbcTemplate.queryForObject(selectPayloadJsonSql2, String.class, TENANT, taskId);
      TaskDispatchMessage retryDispatch =
          JsonUtils.fromJson(retryPayload, TaskDispatchMessage.class);
      assertThat(retryDispatch).isNotNull();
      observedTaskId.set(taskId);
      e2eOutboxPublishSupport.publishAllPending(TENANT);
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            assertThat(receivedRequests).hasValue(2);
            assertThat(successfulClaims).hasValue(2);
            assertThat(claimAttempts.get()).isGreaterThanOrEqualTo(2);
            String selectedTaskStatusRead2 =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(selectedTaskStatusRead2).isEqualTo("SUCCESS");
          });
      int attemptsBeforeExplicitRedelivery = claimAttempts.get();

      String retryTopic = BatchTopics.directDispatchTopic(
          BatchTopics.TASK_DISPATCH_ATOMIC, retryDispatch.selectedWorkerId());
      String retryKey = TENANT + ":" + retryDispatch.jobCode() + ":" + retryDispatch.instanceNo()
          + ":task:" + retryDispatch.taskId();
      kafkaTemplate.send(retryTopic, retryKey, retryPayload).get();
      kafkaTemplate.send(retryTopic, retryKey, retryPayload).get();
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> assertThat(claimAttempts).hasValue(attemptsBeforeExplicitRedelivery + 2));
      assertThat(successfulClaims).hasValue(2);
      assertThat(receivedRequests).hasValue(2);
      String selectedRunStatus =
          jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, seed.requestId());
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    } finally {
      server.stop(0);
    }
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  /** 起一个 job_type=ATOMIC 的任务,把 params 作为 payload 透传,等终态 SUCCESS。 */
  private void launchAndAwaitSuccess(Map<String, Object> params) {
    final String selectTaskStatusSql = """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API);

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-atomic",
        params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status = jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });
  }
}
