package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.observability.BatchMetricsNames;
import io.github.pinpols.batch.e2e.apps.E2eAtomicApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.infrastructure.HttpTaskExecutionClient;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxCoordinator;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** 验证 Worker 真实微批 HTTP 与控制面逐项事务的连接。 */
@E2eSpringBootTest(
    classes = E2eAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.task-client.report-batch-enabled=true",
      "batch.worker.task-client.report-batch-wait-millis=100",
      "batch.worker.task-client.report-max-attempts=2",
      "batch.worker.report-outbox.enabled=false",
      "batch.retry.poll-interval-millis=600000",
      "batch.outbox.poll-interval-millis=600000"
    })
@E2eBusinessSchema
@DisplayName("Worker 批量回报经真实 HTTP 逐项落库")
class WorkerReportBatchHttpE2eIT extends AbstractIntegrationTest {
  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);

  private final WorkflowRunLaunchService launchService;
  private final JdbcTemplate jdbcTemplate;
  private final HttpTaskExecutionClient taskClient;
  private final KafkaListenerEndpointRegistry listenerRegistry;
  private final MeterRegistry meterRegistry;
  private final ObjectProvider<WorkerReportOutboxCoordinator> reportOutbox;
  private final Environment environment;

  @Autowired
  WorkerReportBatchHttpE2eIT(
      WorkflowRunLaunchService launchService,
      JdbcTemplate jdbcTemplate,
      HttpTaskExecutionClient taskClient,
      KafkaListenerEndpointRegistry listenerRegistry,
      MeterRegistry meterRegistry,
      ObjectProvider<WorkerReportOutboxCoordinator> reportOutbox,
      Environment environment) {
    this.launchService = launchService;
    this.jdbcTemplate = jdbcTemplate;
    this.taskClient = taskClient;
    this.listenerRegistry = listenerRegistry;
    this.meterRegistry = meterRegistry;
    this.reportOutbox = reportOutbox;
    this.environment = environment;
  }

  @Test
  @DisplayName("批次包含成功、业务失败及身份失效回报时分别处理")
  void mixedReports_shouldBatchOverHttpAndCommitIndividually() throws Exception {
    assertThat(environment.getProperty("batch.worker.report-outbox.enabled")).isEqualTo("false");
    assertThat(reportOutbox.getIfAvailable()).isNull();
    final String selectTaskSql = """
        select task.id, task.assigned_worker_code, task.job_partition_id
        from batch.job_task task
        join batch.job_instance instance on instance.tenant_id = task.tenant_id
            and instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    final String selectInvocationSql = """
        select current_invocation_id from batch.job_partition where tenant_id = ? and id = ?
        """;
    final String selectOutcomeSql = """
        select task.task_status, instance.instance_status
        from batch.job_task task
        join batch.job_instance instance on instance.tenant_id = task.tenant_id
            and instance.id = task.job_instance_id
        where task.tenant_id = ? and task.id = ?
        """;
    listenerRegistry.stop();
    List<TaskExecutionReport> reports = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "ATOMIC", "atomic", TriggerType.API, BIZ_DATE);
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "report-batch-http-" + i,
          Map.of("taskType", "sql", "sql", "select 1")));
      Map<String, Object> task = jdbcTemplate.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      Long taskId = ((Number) task.get("id")).longValue();
      Long partitionId = ((Number) task.get("job_partition_id")).longValue();
      String workerId = (String) task.get("assigned_worker_code");
      assertThat(taskClient.claim(TENANT, taskId, workerId)).isPresent();
      String invocationId =
          jdbcTemplate.queryForObject(selectInvocationSql, String.class, TENANT, partitionId);
      assertThat(invocationId).isNotBlank();
      TaskExecutionReport report = new TaskExecutionReport();
      report.setTenantId(TENANT);
      report.setTaskId(taskId);
      report.setWorkerId(workerId);
      report.setPartitionInvocationId(i == 2 ? UUID.randomUUID().toString() : invocationId);
      report.setSuccess(i != 1);
      report.setCode(i != 1 ? "SUCCESS" : "BATCH_TEST_FAILURE");
      reports.add(report);
    }
    DistributionSummary before =
        meterRegistry.find(BatchMetricsNames.BATCH_REPORT_SIZE).summary();
    long countBefore = before == null ? 0 : before.count();
    double totalBefore = before == null ? 0 : before.totalAmount();
    double rejectedBefore = meterRegistry
        .counter(
            BatchMetricsNames.BATCH_REPORT_ITEMS_TOTAL, BatchMetricsNames.TAG_OUTCOME, "failed")
        .count();
    double unconfirmedBefore = meterRegistry
        .counter("worker.report.failed.total", "reason", "BATCH_UNCONFIRMED")
        .count();
    double conflictBefore = meterRegistry
        .counter("worker.report.failed.total", "reason", "STATE_CONFLICT")
        .count();
    double droppedBefore = meterRegistry
        .counter("worker.report.dropped.total", "reason", "outbox_disabled")
        .count();
    CountDownLatch ready = new CountDownLatch(3);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (TaskExecutionReport report : reports) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待并发回报超时");
          }
          taskClient.report(report);
          return null;
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
    DistributionSummary after =
        meterRegistry.find(BatchMetricsNames.BATCH_REPORT_SIZE).summary();
    assertThat(after).isNotNull();
    assertThat(after.count() - countBefore).isEqualTo(1);
    assertThat(after.totalAmount() - totalBefore).isEqualTo(3);
    assertThat(meterRegistry
                .counter(
                    BatchMetricsNames.BATCH_REPORT_ITEMS_TOTAL,
                    BatchMetricsNames.TAG_OUTCOME,
                    "failed")
                .count()
            - rejectedBefore)
        .isEqualTo(1);
    assertThat(meterRegistry
                .counter("worker.report.failed.total", "reason", "BATCH_UNCONFIRMED")
                .count()
            - unconfirmedBefore)
        .isEqualTo(1);
    assertThat(meterRegistry
                .counter("worker.report.failed.total", "reason", "STATE_CONFLICT")
                .count()
            - conflictBefore)
        .isEqualTo(1);
    assertThat(meterRegistry
                .counter("worker.report.dropped.total", "reason", "outbox_disabled")
                .count()
            - droppedBefore)
        .isEqualTo(1);
    Map<String, Object> successful =
        jdbcTemplate.queryForMap(selectOutcomeSql, TENANT, reports.get(0).getTaskId());
    Map<String, Object> failed =
        jdbcTemplate.queryForMap(selectOutcomeSql, TENANT, reports.get(1).getTaskId());
    Map<String, Object> rejected =
        jdbcTemplate.queryForMap(selectOutcomeSql, TENANT, reports.get(2).getTaskId());
    assertThat(successful)
        .containsEntry("task_status", "SUCCESS")
        .containsEntry("instance_status", "SUCCESS");
    assertThat(failed)
        .containsEntry("task_status", "FAILED")
        .containsEntry("instance_status", "FAILED");
    assertThat(rejected)
        .containsEntry("task_status", "RUNNING")
        .containsEntry("instance_status", "RUNNING");
  }
}
