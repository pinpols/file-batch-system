package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.e2e.apps.E2eAtomicApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@E2eSpringBootTest(
    classes = E2eAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.retry.poll-interval-millis=600000",
      "batch.outbox.poll-interval-millis=600000",
      "batch.worker.executors.http.max-retries=1",
      "batch.worker.executors.http.retry-backoff=10ms"
    })
@E2eBusinessSchema
@DisplayName("Atomic HTTP 两层重试与实际外呼次数")
class AtomicHttpRetryLayersE2eIT extends AbstractIntegrationTest {
  private static final String TENANT = "t1";

  private final JdbcTemplate jdbc;
  private final WorkflowRunLaunchService launchService;
  private final RetryGovernanceService retryService;
  private final E2eOutboxPublishSupport outbox;

  @Autowired
  AtomicHttpRetryLayersE2eIT(
      JdbcTemplate jdbc,
      WorkflowRunLaunchService launchService,
      RetryGovernanceService retryService,
      E2eOutboxPublishSupport outbox) {
    this.jdbc = jdbc;
    this.launchService = launchService;
    this.retryService = retryService;
    this.outbox = outbox;
  }

  @Test
  @DisplayName("幂等 GET 两层各重试一次，下游持续 503 时共外呼四次并失败")
  void shouldCountHttpAndJobRetriesWhenServerContinuesToFail() throws Exception {
    String updateDefinitionSql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ?
        where tenant_id = ? and job_code = ?
        """;
    String selectTaskIdSql = """
        select task.id from batch.job_task task
        join batch.job_instance instance on instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    String selectPartitionIdSql = """
        select partition.id from batch.job_partition partition
        join batch.job_instance instance on instance.tenant_id = partition.tenant_id
          and instance.id = partition.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    String selectTaskSql = """
        select task_status || '|' || coalesce(error_code, '') from batch.job_task
        where tenant_id = ? and id = ?
        """;
    String selectWaitingSql = """
        select count(*)::int from batch.retry_schedule
        where tenant_id = ? and related_type = 'JOB_PARTITION'
          and related_id = ? and retry_status = 'WAITING'
        """;
    String makeRetryDueSql = """
        update batch.retry_schedule set next_retry_at = now() - interval '1 second'
        where tenant_id = ? and related_type = 'JOB_PARTITION'
          and related_id = ? and retry_status = 'WAITING'
        """;
    String selectRunSql = """
        select run.run_status from batch.workflow_run run
        join batch.trigger_request request on request.tenant_id = run.tenant_id
          and request.id = run.trigger_request_id
        where request.tenant_id = ? and request.request_id = ?
        """;
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/unavailable", exchange -> {
      requests.incrementAndGet();
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
    });
    server.start();
    try {
      LocalDate bizDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "ATOMIC", "atomic", TriggerType.API, bizDate);
      jdbc.update(updateDefinitionSql, "FIXED", 1, TENANT, seed.jobCode());
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/unavailable";
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          bizDate,
          TriggerType.API,
          seed.requestId(),
          "e2e-http-retry-layers",
          Map.of("taskType", "http", "url", url, "method", "GET")));
      Long taskId = jdbc.queryForObject(selectTaskIdSql, Long.class, TENANT, seed.dedupKey());
      Long partitionId =
          jdbc.queryForObject(selectPartitionIdSql, Long.class, TENANT, seed.dedupKey());
      outbox.publishAllPending(TENANT);
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            assertThat(requests).hasValue(2);
            String taskStatus = jdbc.queryForObject(selectTaskSql, String.class, TENANT, taskId);
            Integer waiting =
                jdbc.queryForObject(selectWaitingSql, Integer.class, TENANT, partitionId);
            assertThat(taskStatus).isEqualTo("FAILED|EXECUTION_FAILED");
            assertThat(waiting).isEqualTo(1);
          });
      int updatedRows = jdbc.update(makeRetryDueSql, TENANT, partitionId);
      assertThat(updatedRows).isEqualTo(1);
      retryService.dispatchDueRetries();
      outbox.publishAllPending(TENANT);
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            assertThat(requests).hasValue(4);
            String taskStatus = jdbc.queryForObject(selectTaskSql, String.class, TENANT, taskId);
            String runStatus =
                jdbc.queryForObject(selectRunSql, String.class, TENANT, seed.requestId());
            Integer waiting =
                jdbc.queryForObject(selectWaitingSql, Integer.class, TENANT, partitionId);
            assertThat(taskStatus).isEqualTo("FAILED|EXECUTION_FAILED");
            assertThat(runStatus).isEqualTo("FAILED");
            assertThat(waiting).isZero();
          });
    } finally {
      server.stop(0);
    }
  }
}
