package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eReportRecoveryOrchestratorApplication;
import io.github.pinpols.batch.e2e.apps.E2eSettlementProcessConfiguration;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.e2e.support.ProcessE2eFixture;
import io.github.pinpols.batch.e2e.support.SmtpAcceptThenDropServer;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskExecutionReportCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.TestContainerLabels;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 永久关联到已经过期的租约。
 * 验证 Worker 重复 REPORT 的恢复：业务写入和 Orchestrator 状态
 */
@E2eSpringBootTest(
    classes = E2eReportRecoveryOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.application.name=r14-report-recovery-e2e",
      "batch.worker.task-client.report-max-attempts=1",
      "batch.outbox.poll-interval-millis=600000"
    })
@Import({
  WorkerReportLostAfterBusinessCommitE2eIT.TestDataSourceConfiguration.class,
  E2eSettlementProcessConfiguration.class
})
@E2eBusinessSchema
@Sql(scripts = {E2eTestSql.IMPORT_TEMPLATE_SEED, E2eTestSql.EXPORT_TEMPLATE_SEED})
@DisplayName("业务记录和任务状态提交后 REPORT 确认丢失，经持久化回报 outbox 幂等恢复")
class WorkerReportLostAfterBusinessCommitE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2099, 1, 15);
  private static final String JAVA_BIN =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();
  private static final long WORKER_BUILD_TIMEOUT_MINUTES = 10L;
  private static final HttpClient REPORT_HTTP_CLIENT = HttpClient.newHttpClient();

  private final WorkflowRunLaunchService launchService;

  private final E2eOutboxPublishSupport outboxPublishSupport;

  private final JdbcTemplate platformJdbc;

  private final KafkaTemplate<String, String> kafkaTemplate;

  private final JobPartitionMapper jobPartitionMapper;

  private final PartitionReclaimUnit partitionReclaimUnit;

  private final ObjectMapper objectMapper;

  private final DataSource businessDataSource;

  @Value("${batch.security.internal-secret}")
  private String internalSecret;

  @LocalServerPort
  private int localServerPort;

  private Path dispatchExecJar;
  private Path importExecJar;
  private Path processExecJar;
  private Path exportExecJar;
  private Path atomicExecJar;

  @DynamicPropertySource
  static void registerBusinessDatabaseProperties(DynamicPropertyRegistry registry) {
    registry.add("r14.test.business.url", () -> businessJdbcUrl());
    registry.add("r14.test.business.username", () -> businessJdbcUsername());
    registry.add("r14.test.business.password", () -> businessJdbcPassword());
  }

  @MockitoSpyBean
  private TaskControllerApplicationService taskControllerApplicationService;

  @Autowired
  WorkerReportLostAfterBusinessCommitE2eIT(
      WorkflowRunLaunchService launchService,
      E2eOutboxPublishSupport outboxPublishSupport,
      JdbcTemplate platformJdbc,
      KafkaTemplate<String, String> kafkaTemplate,
      JobPartitionMapper jobPartitionMapper,
      PartitionReclaimUnit partitionReclaimUnit,
      ObjectMapper objectMapper,
      @Qualifier("e2eBusinessDataSource") DataSource businessDataSource) {
    this.launchService = launchService;
    this.outboxPublishSupport = outboxPublishSupport;
    this.platformJdbc = platformJdbc;
    this.kafkaTemplate = kafkaTemplate;
    this.jobPartitionMapper = jobPartitionMapper;
    this.partitionReclaimUnit = partitionReclaimUnit;
    this.objectMapper = objectMapper;
    this.businessDataSource = businessDataSource;
  }

  @Test
  @DisplayName("已提交 REPORT 的并发重复与确认丢失在 Worker 重启后收敛且业务记录唯一")
  void shouldRecoverWhenReportAcknowledgementIsLostAfterCommitAndWorkerRestarts() throws Exception {
    final String selectCountSql = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?
    """;
    final String selectCountSql2 = """
        select count(*)::int from batch.worker_report_outbox where tenant_id = ? and task_id = (select t.id\s\
        from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id where ji.tenant_id = ?\s\
        and ji.dedup_key = ?)\
        """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "IMPORT", "IMPORT", TriggerType.API, BIZ_DATE);
    String customerNo = "R14-" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    String customerContent = """
        [{"customerNo":"%s","customerName":"R14 recovery","customerType":"PERSONAL",\
        "certificateNo":"ID-%s","status":"ACTIVE"}]\
        """.formatted(customerNo, customerNo);
    params.put("content", customerContent);

    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> firstCommittedReport = new AtomicReference<>();
    CountDownLatch firstReportCommitted = new CountDownLatch(1);
    CountDownLatch releaseFirstReportResponse = new CountDownLatch(1);
    doAnswer(invocation -> {
          Object result = invocation.callRealMethod();
          if (reportAttempts.getAndIncrement() == 0) {
            firstCommittedReport.set(invocation.getArgument(1));
            firstReportCommitted.countDown();
            if (!releaseFirstReportResponse.await(60, TimeUnit.SECONDS)) {
              throw new IllegalStateException(
                  "R14 timed out waiting to release first REPORT response");
            }
            throw new IllegalStateException("R14 injected report transport loss");
          }
          return result;
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));

    String workerCode = "r14-import-" + Long.toUnsignedString(System.nanoTime());
    String consumerGroupId = "batch-worker-import-r14-" + Long.toUnsignedString(System.nanoTime());
    Path worker1Log = createWorkerLog("r14-import-worker-1-");
    Path worker2Log = createWorkerLog("r14-import-worker-2-");
    Process worker1 = null;
    Process worker2 = null;
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    try {
      worker1 = startImportWorker(workerCode, consumerGroupId, worker1Log, 600_000);
      awaitWorkerStarted(worker1, worker1Log);
      awaitWorkerOnline(workerCode);

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r14-trace-" + customerNo,
          params));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportCommitted.await(180, TimeUnit.SECONDS))
          .as("first Worker REPORT commits before its HTTP response is released")
          .isTrue();
      assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
      Integer selectedCount =
          businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, customerNo);
      assertThat(selectedCount).isEqualTo(1);

      HttpResponse<String> concurrentDuplicate = postReport(firstCommittedReport.get());
      assertThat(concurrentDuplicate.statusCode()).isEqualTo(200);
      assertThat(reportAttempts.get()).isEqualTo(2);
      releaseFirstReportResponse.countDown();

      await().atMost(Duration.ofSeconds(180)).untilAsserted(() -> {
        Integer selectedCountRead2 =
            businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, customerNo);
        assertThat(selectedCountRead2).isEqualTo(1);
        Integer selectedCountRead3 = platformJdbc.queryForObject(
            selectCountSql2, Integer.class, TENANT, TENANT, seed.dedupKey());
        assertThat(selectedCountRead3).isEqualTo(1);
        assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
      });

      assertThat(reportOutboxStatus(seed)).isEqualTo("NEW");
      assertThat(reportAttempts.get())
          .as("first REPORT plus the concurrent duplicate REPORT")
          .isEqualTo(2);
      stopProcess(worker1);
      worker1 = null;

      worker2 = startImportWorker(workerCode, consumerGroupId, worker2Log, 100);
      awaitWorkerStarted(worker2, worker2Log);
      awaitWorkerOnline(workerCode);

      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
            assertThat(reportOutboxCount(seed)).isZero();
            assertThat(reportAttempts.get()).isEqualTo(3);
            Integer selectedCountRead4 =
                businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, customerNo);
            assertThat(selectedCountRead4).isEqualTo(1);
          });
    } finally {
      releaseFirstReportResponse.countDown();
      stopProcess(worker1);
      stopProcess(worker2);
    }
  }

  @Test
  @DisplayName("Import Worker 中断后租约回收复用指纹位点续跑")
  void shouldResumeImportCheckpointAfterWorkerProcessIsKilled() throws Exception {
    final String updateTemplateSql = """
        update batch.file_template_config set chunk_size = 1
        where tenant_id = ? and template_code = 'IMP-CUSTOMER-JSON-ARRAY'
        """;
    final String insertCustomerSql = """
        insert into biz.customer_account (tenant_id, customer_no, customer_name, customer_type, status)
        values (?, ?, 'before', 'PERSONAL', 'ACTIVE')
        """;
    final String lockCustomerSql = """
        select id from biz.customer_account where tenant_id = ? and customer_no = ? for update
        """;
    final String backendPidSql = "select pg_backend_pid()";
    final String blockedCountSql =
        "select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))";
    final String selectPartitionSql = """
        select p.id from batch.job_partition p join batch.job_instance ji
          on ji.tenant_id = p.tenant_id and ji.id = p.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectCheckpointSql = """
        select pp.position_marker from batch.pipeline_progress pp
        join batch.pipeline_instance pi on pi.tenant_id = pp.tenant_id and pi.id = pp.pipeline_instance_id
        join batch.job_instance ji on ji.tenant_id = pi.tenant_id and ji.id = pi.related_job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ? and pp.stage = 'LOAD'
        """;
    final String expireLeaseSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'
        where tenant_id = ? and id = ?
        """;
    final String selectFinalCheckpointSql = """
        select pp.position_marker, pp.processed_count, pp.completed from batch.pipeline_progress pp
        join batch.pipeline_instance pi on pi.tenant_id = pp.tenant_id and pi.id = pp.pipeline_instance_id
        join batch.job_instance ji on ji.tenant_id = pi.tenant_id and ji.id = pi.related_job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ? and pp.stage = 'LOAD'
        """;
    final String selectCustomersSql = """
        select customer_no, customer_name from biz.customer_account
        where tenant_id = ? and customer_no in (?, ?) order by customer_no
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String firstCustomer = "CKPTA" + suffix;
    String secondCustomer = "CKPTB" + suffix;
    String workerPoolCode = "ckpt-import-" + suffix;
    String consumerGroupId = "batch-import-ckpt-" + suffix;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "IMPORT", "IMPORT", TriggerType.API, BIZ_DATE);
    platformJdbc.update(updateTemplateSql, TENANT);
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    businessJdbc.update(insertCustomerSql, TENANT, secondCustomer);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        objectMapper.writeValueAsString(List.of(
            Map.of(
                "customerNo",
                firstCustomer,
                "customerName",
                "First",
                "customerType",
                "PERSONAL",
                "certificateNo",
                firstCustomer,
                "status",
                "ACTIVE"),
            Map.of(
                "customerNo",
                secondCustomer,
                "customerName",
                "Second",
                "customerType",
                "PERSONAL",
                "certificateNo",
                secondCustomer,
                "status",
                "ACTIVE"))));
    Path firstLog = createWorkerLog("checkpoint-reclaim-first-");
    Path secondLog = createWorkerLog("checkpoint-reclaim-second-");
    Process firstWorker = null;
    Process replacementWorker = null;
    try (Connection lock = businessDataSource.getConnection()) {
      lock.setAutoCommit(false);
      int blockerPid;
      try (PreparedStatement pidStatement = lock.prepareStatement(backendPidSql);
          ResultSet result = pidStatement.executeQuery()) {
        result.next();
        blockerPid = result.getInt(1);
      }
      try (PreparedStatement statement = lock.prepareStatement(lockCustomerSql)) {
        statement.setString(1, TENANT);
        statement.setString(2, secondCustomer);
        try (ResultSet result = statement.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }
      try {
        firstWorker =
            startImportWorker(workerPoolCode, consumerGroupId, firstLog, 600_000, "first");
        awaitWorkerStarted(firstWorker, firstLog);
        awaitWorkerPoolOnline(workerPoolCode);
        launchService.launch(new LaunchRequest(
            TENANT,
            seed.workflowDefinitionId(),
            BIZ_DATE,
            TriggerType.API,
            seed.requestId(),
            "checkpoint-process-reclaim",
            params));
        outboxPublishSupport.publishAllPending(TENANT);
        await()
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
              assertThat(platformJdbc.queryForList(
                      selectCheckpointSql, String.class, TENANT, seed.dedupKey()))
                  .singleElement()
                  .asString()
                  .matches("1:[0-9a-f]{64}");
              assertThat(businessJdbc.queryForObject(blockedCountSql, Integer.class, blockerPid))
                  .isPositive();
            });
        String firstMarker =
            platformJdbc.queryForObject(selectCheckpointSql, String.class, TENANT, seed.dedupKey());
        stopProcess(firstWorker);
        firstWorker = null;
        lock.rollback();
        Long partitionId =
            platformJdbc.queryForObject(selectPartitionSql, Long.class, TENANT, seed.dedupKey());
        platformJdbc.update(expireLeaseSql, TENANT, partitionId);
        JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
        partitionReclaimUnit.reclaim(partition);
        outboxPublishSupport.publishAllPending(TENANT);
        replacementWorker =
            startImportWorker(workerPoolCode, consumerGroupId, secondLog, 600_000, "second");
        awaitWorkerStarted(replacementWorker, secondLog);
        await()
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
              assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
            });
        Map<String, Object> finalCheckpoint =
            platformJdbc.queryForMap(selectFinalCheckpointSql, TENANT, seed.dedupKey());
        assertThat(finalCheckpoint)
            .containsEntry("position_marker", "2:" + firstMarker.substring(2))
            .containsEntry("processed_count", 2L)
            .containsEntry("completed", true);
        assertThat(businessJdbc.queryForList(
                selectCustomersSql, TENANT, firstCustomer, secondCustomer))
            .anySatisfy(row -> assertThat(row)
                .containsEntry("customer_no", firstCustomer)
                .containsEntry("customer_name", "First"))
            .anySatisfy(row -> assertThat(row)
                .containsEntry("customer_no", secondCustomer)
                .containsEntry("customer_name", "Second"))
            .hasSize(2);
      } finally {
        stopProcess(firstWorker);
        stopProcess(replacementWorker);
        lock.rollback();
      }
    }
  }

  @Test
  @DisplayName("真实 Import Worker 业务写入后旧实例失联,回收任务由新实例完成且只产生一条业务记录")
  void shouldCompleteReclaimedAttemptOnReplacementWorker_withoutDuplicateBusinessEffect()
      throws Exception {
    final String selectCountSql = """
            select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?
        """;
    final String selectIdSql = """
            select t.id from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id\s\
            where ji.tenant_id = ? and ji.dedup_key = ?\
            """;
    final String selectIdSql2 = """
            select p.id from batch.job_partition p join batch.job_instance ji on ji.id = p.job_instance_id\s\
            where ji.tenant_id = ? and ji.dedup_key = ?\
            """;
    final String updateJobPartitionSql = """
            update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
            where tenant_id = ? and id = ?\
            """;
    final String selectPayloadJsonSql = """
            select payload_json ->> 'selectedWorkerId' from batch.outbox_event where tenant_id = ?\s\
            and event_key = ?\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.job_task where tenant_id = ? and job_instance_id = (select id\s\
            from batch.job_instance where tenant_id = ? and dedup_key = ?)\
            """;
    final String selectCountSql3 = """
            select count(*) from batch.outbox_event where tenant_id = ? and event_key = ?
        """;
    final String selectIdSql3 = """
            select id from batch.job_instance where tenant_id = ? and dedup_key = ?
        """;
    final String selectWorkflowRunIdSql = """
            select nr.workflow_run_id from batch.workflow_node_run nr\s\
            join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
            and nr.job_instance_id = ? and nr.node_code = 'JOB'\
            """;
    final String selectRunStatusSql = """
            select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectCountSql4 = """
            select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
            and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL' and payload_json->>'runStatus' = 'SUCCESS'\
            """;
    final String selectCountSql5 = """
            select count(*) from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectCountSql6 = """
            select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB'\s\
            and node_status = 'SUCCESS' and job_instance_id = ?\
            """;
    final String selectCountSql7 = """
            select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
            and node_status = 'SUCCESS'\
            """;
    final String selectInstanceStatusSql = """
            select instance_status from batch.job_instance where tenant_id = ? and id = ?
        """;
    final String selectSuccessPartitionCountSql = """
            select success_partition_count from batch.job_instance where tenant_id = ? and id = ?
        """;
    final String selectFailedPartitionCountSql = """
            select failed_partition_count + cancelled_partition_count from batch.job_instance\s\
            where tenant_id = ? and id = ?\
            """;
    final String selectPartitionStatusSql = """
            select partition_status from batch.job_partition where tenant_id = ? and id = ?
        """;
    final String selectRetryCountSql = """
            select retry_count from batch.job_partition where tenant_id = ? and id = ?
        """;
    final String selectCurrentInvocationIdSql = """
            select current_invocation_id from batch.job_partition where tenant_id = ? and id = ?
        """;
    final String selectCountSql8 = """
            select count(*) from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'\s\
            and related_id = ?\
            """;
    final String selectCountSql9 = """
            select count(*) from batch.dead_letter_task where tenant_id = ? and source_type = 'JOB_PARTITION'\s\
            and source_id = ?\
            """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "IMPORT", "IMPORT", TriggerType.API, BIZ_DATE);
    String customerNo = "R15-" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    String customerContent = """
        [{"customerNo":"%s","customerName":"R15 reclaim","customerType":"PERSONAL",\
        "certificateNo":"ID-%s","status":"ACTIVE"}]\
        """.formatted(customerNo, customerNo);
    params.put("content", customerContent);

    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> firstInvocationReport = new AtomicReference<>();
    AtomicReference<TaskExecutionReportCommand> replacementReport = new AtomicReference<>();
    AtomicReference<BizException> staleReportFailure = new AtomicReference<>();
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch replacementReportStarted = new CountDownLatch(1);
    CountDownLatch releaseReplacementReport = new CountDownLatch(1);
    CountDownLatch staleReportRejected = new CountDownLatch(1);
    doAnswer(invocation -> {
          TaskExecutionReportCommand report = invocation.getArgument(1);
          int attempt = reportAttempts.incrementAndGet();
          if (attempt == 1) {
            firstInvocationReport.set(report);
            firstReportStarted.countDown();
            if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException("R15 timed out waiting to release stale I1 REPORT");
            }
            try {
              return invocation.callRealMethod();
            } catch (BizException staleReport) {
              staleReportFailure.set(staleReport);
              staleReportRejected.countDown();
              throw staleReport;
            }
          }
          if (attempt == 2) {
            replacementReport.set(report);
            replacementReportStarted.countDown();
            if (!releaseReplacementReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException("R15 timed out waiting to release current I2 REPORT");
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));

    String workerPoolCode = "r15-import-" + Long.toUnsignedString(System.nanoTime());
    String consumerGroupId = "batch-worker-import-r15-" + Long.toUnsignedString(System.nanoTime());
    Path worker1Log = createWorkerLog("r15-import-worker-1-");
    Path worker2Log = createWorkerLog("r15-import-worker-2-");
    Process worker1 = null;
    Process worker2 = null;
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    try {
      worker1 =
          startImportWorker(workerPoolCode, consumerGroupId, worker1Log, 600_000, "attempt-1");
      awaitWorkerStarted(worker1, worker1Log);
      awaitWorkerPoolOnline(workerPoolCode);

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r15-trace-" + customerNo,
          params));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportStarted.await(300, TimeUnit.SECONDS))
          .as("I1 has committed the business write and reached the REPORT endpoint")
          .isTrue();
      TaskExecutionReportCommand i1Report = firstInvocationReport.get();
      assertThat(i1Report).isNotNull();
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");
      Integer selectedCount =
          businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, customerNo);
      assertThat(selectedCount).isEqualTo(1);

      stopProcess(worker1);
      worker1 = null;
      Long taskId = platformJdbc.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      Long partitionId =
          platformJdbc.queryForObject(selectIdSql2, Long.class, TENANT, seed.dedupKey());
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(updateJobPartitionSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      assertThat(reportStatus(seed)).isEqualTo("READY");
      String selectedPayloadJson = platformJdbc.queryForObject(
          selectPayloadJsonSql,
          String.class,
          TENANT,
          TENANT + ":reclaim:" + partitionId + ":v" + partition.getVersion());
      assertThat(selectedPayloadJson).isNull();
      outboxPublishSupport.publishAllPending(TENANT);

      worker2 =
          startImportWorker(workerPoolCode, consumerGroupId, worker2Log, 600_000, "attempt-2");
      awaitWorkerStarted(worker2, worker2Log);
      awaitWorkerPoolOnline(workerPoolCode);
      assertThat(replacementReportStarted.await(300, TimeUnit.SECONDS))
          .as("a replacement Worker instance claims the unpinned reclaim dispatch")
          .isTrue();
      TaskExecutionReportCommand i2Report = replacementReport.get();
      assertThat(i2Report).isNotNull();
      assertThat(i2Report.partitionInvocationId()).isNotEqualTo(i1Report.partitionInvocationId());
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");

      releaseFirstReport.countDown();
      assertThat(staleReportRejected.await(30, TimeUnit.SECONDS))
          .as("the late I1 REPORT is rejected while I2 still owns the task")
          .isTrue();
      assertThat(staleReportFailure.get().getCode()).isEqualTo(ResultCode.FORBIDDEN);
      assertThat(staleReportFailure.get().getMessageKey()).isEqualTo("error.worker.not_owner");
      releaseReplacementReport.countDown();

      await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
        assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
        assertThat(reportOutboxCount(seed)).isZero();
        Integer selectedCountRead2 =
            businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, customerNo);
        assertThat(selectedCountRead2).isEqualTo(1);
      });
      Integer selectedJobTaskCount = platformJdbc.queryForObject(
          selectCountSql2, Integer.class, TENANT, TENANT, seed.dedupKey());
      assertThat(selectedJobTaskCount).isEqualTo(1);

      Integer selectedOutboxEventCount = platformJdbc.queryForObject(
          selectCountSql3,
          Integer.class,
          TENANT,
          TENANT + ":reclaim:" + partitionId + ":v" + partition.getVersion());
      assertThat(selectedOutboxEventCount).isEqualTo(1);
      Long jobInstanceId =
          platformJdbc.queryForObject(selectIdSql3, Long.class, TENANT, seed.dedupKey());
      Long workflowRunId =
          platformJdbc.queryForObject(selectWorkflowRunIdSql, Long.class, TENANT, jobInstanceId);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        String selectedRunStatus =
            platformJdbc.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
        assertThat(selectedRunStatus).isEqualTo("SUCCESS");
        Integer selectedOutboxEventCountRead2 =
            platformJdbc.queryForObject(selectCountSql4, Integer.class, TENANT, workflowRunId);
        assertThat(selectedOutboxEventCountRead2).isEqualTo(1);
      });
      Integer selectedWorkflowRunCount =
          platformJdbc.queryForObject(selectCountSql5, Integer.class, TENANT, workflowRunId);
      assertThat(selectedWorkflowRunCount).isEqualTo(1);
      Integer selectedWorkflowNodeRunCount =
          platformJdbc.queryForObject(selectCountSql6, Integer.class, workflowRunId, jobInstanceId);
      assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
      Integer selectedWorkflowNodeRunCountRead2 =
          platformJdbc.queryForObject(selectCountSql7, Integer.class, workflowRunId);
      assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1);
      String selectedInstanceStatus =
          platformJdbc.queryForObject(selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
      assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
      Integer selectedSuccessPartitionCount = platformJdbc.queryForObject(
          selectSuccessPartitionCountSql, Integer.class, TENANT, jobInstanceId);
      assertThat(selectedSuccessPartitionCount).isEqualTo(1);
      Integer selectedFailedPartitionCount = platformJdbc.queryForObject(
          selectFailedPartitionCountSql, Integer.class, TENANT, jobInstanceId);
      assertThat(selectedFailedPartitionCount).isZero();
      String selectedPartitionStatus =
          platformJdbc.queryForObject(selectPartitionStatusSql, String.class, TENANT, partitionId);
      assertThat(selectedPartitionStatus).isEqualTo("SUCCESS");
      Integer selectedRetryCount =
          platformJdbc.queryForObject(selectRetryCountSql, Integer.class, TENANT, partitionId);
      assertThat(selectedRetryCount).isZero();
      String selectedCurrentInvocationId = platformJdbc.queryForObject(
          selectCurrentInvocationIdSql, String.class, TENANT, partitionId);
      assertThat(selectedCurrentInvocationId)
          .as("terminal partition releases the current invocation after I2 completes")
          .isNull();
      Integer selectedRetryScheduleCount =
          platformJdbc.queryForObject(selectCountSql8, Integer.class, TENANT, partitionId);
      assertThat(selectedRetryScheduleCount).isZero();
      Integer selectedDeadLetterTaskCount =
          platformJdbc.queryForObject(selectCountSql9, Integer.class, TENANT, partitionId);
      assertThat(selectedDeadLetterTaskCount).isZero();
    } finally {
      releaseFirstReport.countDown();
      releaseReplacementReport.countDown();
      stopProcess(worker1);
      stopProcess(worker2);
    }
  }

  @Test
  @DisplayName("PROCESS COMMIT 后 Worker 失联并 reclaim 时,新 invocation 不重复发布业务结果")
  void shouldReuseProcessCommitAfterReclaim_withoutDuplicateBusinessEffect() throws Exception {
    final String selectCountSql = """
              select count(*)::int from biz.process_account_summary
              where tenant_id = ? and biz_date = ?
              """;
    final String selectIdSql = """
        select p.id from batch.job_partition p join batch.job_instance ji on ji.id = p.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectCountSql2 = """
                select count(*)::int from biz.process_account_summary
                where tenant_id = ? and biz_date = ?
                """;
    final String selectCountSql3 = """
        select count(*)::int from batch.process_staging where tenant_id = ?
    """;
    final String selectTotalAmountSql = """
              select total_amount::text from biz.process_account_summary
              where tenant_id = ? and account_id = 'A' and biz_date = ?
              """;
    final String selectTotalAmountSql2 = """
              select total_amount::text from biz.process_account_summary
              where tenant_id = ? and account_id = 'B' and biz_date = ?
              """;
    final String selectCountSql4 = """
        select count(*)::int from batch.job_task where tenant_id = ? and job_instance_id = (select id\s\
        from batch.job_instance where tenant_id = ? and dedup_key = ?)\
        """;
    final String selectCountSql5 = """
              select count(*)::int from batch.pipeline_step_run step
              join batch.pipeline_instance pipeline on pipeline.id = step.pipeline_instance_id
              join batch.job_instance instance on instance.id = pipeline.related_job_instance_id
              where instance.tenant_id = ? and instance.dedup_key = ?
                and step.stage_code = 'COMMIT' and step.step_status = 'SUCCESS'
              """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "PROCESS", "process", TriggerType.API, BIZ_DATE);
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT, BIZ_DATE);
    ProcessE2eFixture.configureSqlTransformJobParameters(
        platformJdbc, objectMapper, TENANT, seed.jobCode(), List.of());

    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> firstReport = new AtomicReference<>();
    AtomicReference<TaskExecutionReportCommand> replacementReport = new AtomicReference<>();
    AtomicReference<BizException> staleReportFailure = new AtomicReference<>();
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch replacementReportStarted = new CountDownLatch(1);
    CountDownLatch releaseReplacementReport = new CountDownLatch(1);
    CountDownLatch staleReportRejected = new CountDownLatch(1);
    doAnswer(invocation -> {
          TaskExecutionReportCommand report = invocation.getArgument(1);
          int attempt = reportAttempts.incrementAndGet();
          if (attempt == 1) {
            firstReport.set(report);
            firstReportStarted.countDown();
            if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException("timed out waiting to release stale PROCESS REPORT");
            }
            try {
              return invocation.callRealMethod();
            } catch (BizException staleReport) {
              staleReportFailure.set(staleReport);
              staleReportRejected.countDown();
              throw staleReport;
            }
          }
          if (attempt == 2) {
            replacementReport.set(report);
            replacementReportStarted.countDown();
            if (!releaseReplacementReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException(
                  "timed out waiting to release replacement PROCESS REPORT");
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));

    String workerPoolCode = "r16-process-" + Long.toUnsignedString(System.nanoTime());
    String consumerGroupId = "batch-worker-process-r16-" + Long.toUnsignedString(System.nanoTime());
    Path worker1Log = createWorkerLog("r16-process-worker-1-");
    Path worker2Log = createWorkerLog("r16-process-worker-2-");
    Process worker1 = null;
    Process worker2 = null;
    try {
      worker1 = startProcessWorker(workerPoolCode, consumerGroupId, worker1Log, "attempt-1");
      awaitProcessWorkerStarted(worker1, worker1Log);
      awaitWorkerPoolOnline(workerPoolCode, "PROCESS");

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r16-process-reclaim-" + seed.dedupKey(),
          Map.of("bizDate", BIZ_DATE.toString())));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportStarted.await(300, TimeUnit.SECONDS))
          .as("PROCESS COMMIT completed before the first invocation reaches REPORT")
          .isTrue();
      TaskExecutionReportCommand i1Report = firstReport.get();
      assertThat(i1Report).isNotNull();
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");
      Integer firstCommitSummaryCount =
          businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, BIZ_DATE);
      assertThat(firstCommitSummaryCount).isEqualTo(2);

      stopProcess(worker1);
      worker1 = null;
      Long partitionId =
          platformJdbc.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(updateJobPartitionSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      assertThat(reportStatus(seed)).isEqualTo("READY");
      outboxPublishSupport.publishAllPending(TENANT);

      worker2 = startProcessWorker(workerPoolCode, consumerGroupId, worker2Log, "attempt-2");
      awaitProcessWorkerStarted(worker2, worker2Log);
      awaitWorkerPoolOnline(workerPoolCode, "PROCESS");
      assertThat(replacementReportStarted.await(300, TimeUnit.SECONDS))
          .as("replacement PROCESS invocation claims the reclaimed task and reports")
          .isTrue();
      TaskExecutionReportCommand i2Report = replacementReport.get();
      assertThat(i2Report).isNotNull();
      assertThat(i2Report.partitionInvocationId()).isNotEqualTo(i1Report.partitionInvocationId());

      releaseFirstReport.countDown();
      assertThat(staleReportRejected.await(30, TimeUnit.SECONDS))
          .as("the old PROCESS REPORT is fenced after the replacement invocation claims")
          .isTrue();
      assertThat(staleReportFailure.get().getCode()).isEqualTo(ResultCode.FORBIDDEN);
      assertThat(staleReportFailure.get().getMessageKey()).isEqualTo("error.worker.not_owner");
      releaseReplacementReport.countDown();

      await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
        assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
        assertThat(reportOutboxCount(seed)).isZero();
        Integer finalSummaryCount =
            businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, BIZ_DATE);
        assertThat(finalSummaryCount).isEqualTo(2);
      });
      Integer selectedCount = businessJdbc.queryForObject(selectCountSql3, Integer.class, TENANT);
      assertThat(selectedCount).isZero();
      String accountATotal =
          businessJdbc.queryForObject(selectTotalAmountSql, String.class, TENANT, BIZ_DATE);
      String accountBTotal =
          businessJdbc.queryForObject(selectTotalAmountSql2, String.class, TENANT, BIZ_DATE);
      assertThat(accountATotal).isEqualTo("30.00");
      assertThat(accountBTotal).isEqualTo("7.00");
      Integer selectedCountRead2 = platformJdbc.queryForObject(
          selectCountSql4, Integer.class, TENANT, TENANT, seed.dedupKey());
      assertThat(selectedCountRead2).isEqualTo(1);
      Integer selectedCountRead3 =
          platformJdbc.queryForObject(selectCountSql5, Integer.class, TENANT, seed.dedupKey());
      assertThat(selectedCountRead3).isEqualTo(2);
    } finally {
      releaseFirstReport.countDown();
      releaseReplacementReport.countDown();
      stopProcess(worker1);
      stopProcess(worker2);
    }
  }

  @Test
  @DisplayName("PROCESS 数据库 COMMIT 已落库但确认包丢失,新 invocation 重放仍只保留一套业务结果")
  void shouldReplayProcessPublish_whenCommitResponseIsLostAfterDatabaseCommit() throws Exception {
    final String selectCountSql = """
              select count(*)::int from pg_stat_activity
              where application_name = ? and state = 'active' and wait_event = 'PgSleep'
              """;
    final String selectCountSql2 = """
              select count(*)::int from biz.process_account_summary
              where tenant_id = ? and biz_date = ?
              """;
    final String selectIdSql = """
        select p.id from batch.job_partition p join batch.job_instance ji on ji.id = p.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectCountSql3 = """
                select count(*)::int from biz.process_account_summary
                where tenant_id = ? and biz_date = ?
                """;
    final String selectTotalAmountSql = """
                select total_amount::text from biz.process_account_summary
                where tenant_id = ? and biz_date = ? and account_id = 'A'
                """;
    final String selectTotalAmountSql2 = """
                select total_amount::text from biz.process_account_summary
                where tenant_id = ? and biz_date = ? and account_id = 'B'
                """;
    final String selectCountSql4 = """
                select count(*)::int from batch.process_staging
                where tenant_id = ?
                """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    String suffix = Long.toUnsignedString(System.nanoTime());
    String triggerName = "e2e_process_commit_ack_" + suffix;
    String functionName = "delay_process_commit_ack_" + suffix;
    String applicationName = "r16-process-commit-ack-" + suffix;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "PROCESS", "process", TriggerType.API, BIZ_DATE);
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT, BIZ_DATE);
    ProcessE2eFixture.configureSqlTransformJobParameters(
        platformJdbc, objectMapper, TENANT, seed.jobCode(), List.of());
    final String createFunctionSql = """
        create function biz.%s() returns trigger language plpgsql as $$
        begin
          perform pg_sleep(3);
          return new;
        end
        $$
        """.formatted(functionName);
    businessJdbc.execute(createFunctionSql);
    final String createConstraintSql = """
        create constraint trigger %s after insert on biz.process_account_summary
        deferrable initially deferred for each row execute function biz.%s()
        """.formatted(triggerName, functionName);
    businessJdbc.execute(createConstraintSql);

    int businessPort = URI.create(businessJdbcUrl().substring("jdbc:".length())).getPort();
    Testcontainers.exposeHostPorts(businessPort);
    GenericContainer<?> toxiproxy = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
        .withExposedPorts(8474, 8666)
        .waitingFor(Wait.forHttp("/version").forPort(8474));
    Path worker1Log = createWorkerLog("r16-process-commit-ack-worker-1-");
    Path worker2Log = createWorkerLog("r16-process-commit-ack-worker-2-");
    Process worker1 = null;
    Process worker2 = null;
    Proxy businessProxy = null;
    try {
      toxiproxy.start();
      ToxiproxyClient toxiproxyClient =
          new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getMappedPort(8474));
      businessProxy = toxiproxyClient.createProxy(
          "business", "0.0.0.0:8666", "host.testcontainers.internal:" + businessPort);
      String proxiedBusinessUrl = proxiedJdbcUrl(
          businessJdbcUrl(), toxiproxy.getHost(), toxiproxy.getMappedPort(8666), applicationName);
      String workerPoolCode = "r16-process-commit-ack-" + suffix;
      String consumerGroupId = "batch-worker-process-r16-commit-ack-" + suffix;
      worker1 = startProcessWorker(
          workerPoolCode, consumerGroupId, worker1Log, "attempt-1", proxiedBusinessUrl);
      awaitProcessWorkerStarted(worker1, worker1Log);
      awaitWorkerPoolOnline(workerPoolCode, "PROCESS");

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r16-process-commit-ack-" + suffix,
          Map.of("bizDate", BIZ_DATE.toString())));
      outboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() -> {
            Integer activeCommitCount =
                businessJdbc.queryForObject(selectCountSql, Integer.class, applicationName);
            assertThat(activeCommitCount)
                .as("the deferred trigger is holding the server inside COMMIT")
                .isGreaterThan(0);
          });
      businessProxy.toxics().latency("r16_commit_ack_delay", ToxicDirection.DOWNSTREAM, 30_000);

      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() -> {
            Integer committedSummaryCount =
                businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, BIZ_DATE);
            assertThat(committedSummaryCount)
                .as("PostgreSQL committed the publish while its COMMIT response is held")
                .isEqualTo(2);
          });
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");
      assertThat(worker1.isAlive()).isTrue();
      stopProcess(worker1);
      worker1 = null;
      businessProxy.toxics().get("r16_commit_ack_delay").remove();

      Long partitionId =
          platformJdbc.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(updateJobPartitionSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      assertThat(reportStatus(seed)).isEqualTo("READY");
      outboxPublishSupport.publishAllPending(TENANT);

      worker2 = startProcessWorker(
          workerPoolCode, consumerGroupId, worker2Log, "attempt-2", proxiedBusinessUrl);
      awaitProcessWorkerStarted(worker2, worker2Log);
      awaitWorkerPoolOnline(workerPoolCode, "PROCESS");

      await()
          .atMost(Duration.ofSeconds(180))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
            assertThat(reportOutboxCount(seed)).isZero();
            Integer finalSummaryCount =
                businessJdbc.queryForObject(selectCountSql3, Integer.class, TENANT, BIZ_DATE);
            String accountATotal =
                businessJdbc.queryForObject(selectTotalAmountSql, String.class, TENANT, BIZ_DATE);
            String accountBTotal =
                businessJdbc.queryForObject(selectTotalAmountSql2, String.class, TENANT, BIZ_DATE);
            assertThat(finalSummaryCount).isEqualTo(2);
            assertThat(accountATotal).isEqualTo("30.00");
            assertThat(accountBTotal).isEqualTo("7.00");
            Integer remainingStagingCount =
                businessJdbc.queryForObject(selectCountSql4, Integer.class, TENANT);
            assertThat(remainingStagingCount).isZero();
          });
    } finally {
      if (businessProxy != null) {
        try {
          for (var toxic : businessProxy.toxics().getAll()) {
            toxic.remove();
          }
        } catch (IOException ignored) {
          // 事务都已提交，但 HTTP 确认丢失。
        }
      }
      stopProcess(worker1);
      stopProcess(worker2);
      toxiproxy.stop();
    }
  }

  @Test
  @DisplayName("DISPATCH 已记录 ACKED 后 Worker 失联并 reclaim,新 Worker 不再调用非幂等接收端")
  void shouldNotDuplicateDispatchSideEffect_whenWorkerIsReclaimedAfterDelivery() throws Exception {
    final String insertFileRecordSql = """
        insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
            storage_path, source_type, file_status, biz_date, trace_id
        ) values (
            ?, ?, 'OUTPUT', 'OUTPUT', ?, ?, 'json', 'JSON', 'UTF-8', 'application/json',
            32, 'NONE', 'LOCAL', ?, 'SYSTEM', 'GENERATED', date '2026-01-15', ?
        ) returning id
        """;
    final String insertFileChannelConfigSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (
            ?, ?, 'R16 HTTP dispatch', 'API', ?, 'NONE',
            jsonb_build_object('channel_type', 'API', 'channel_code', ?), 'NONE', 10, true
        )
        """;
    final String insertResourceQueueSql = """
        insert into batch.resource_queue (
            tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
            max_running_partitions, max_qps, worker_group, resource_tag,
            priority_policy, fair_share_weight, enabled, quota_reset_policy,
            group_shared_max_running_jobs, burst_limit
        ) values (?, ?, 'R16 DISPATCH reclaim', 'DISPATCH', 0, 0, 0, 'DISPATCH', null,
                  'FIFO', 1, true, 'NONE', 0, 0)
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set queue_code = ? where tenant_id = ? and job_code = ?
    """;
    final String selectDispatchStatusSql = """
        select dispatch_status from batch.file_dispatch_record where tenant_id = ? and file_id = ?\s\
        and channel_code = ?\
        """;
    final String selectIdSql = """
        select p.id from batch.job_partition p join batch.job_instance ji on ji.id = p.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*)::int from batch.file_dispatch_record where tenant_id = ? and file_id = ?\s\
        and channel_code = ?\
        """;
    final String selectCountSql2 = """
        select count(*)::int from batch.file_audit_log where tenant_id = ? and file_id = ?\s\
        and operation_type = 'DISPATCH_COMPLETE'\
        """;

    AtomicInteger requestCount = new AtomicInteger();
    AtomicInteger receiverEffects = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/dispatch", exchange -> {
      if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        return;
      }
      requestCount.incrementAndGet();
      String response = "accepted-" + receiverEffects.incrementAndGet();
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      try {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
          output.write(body);
        }
      } catch (IOException clientClosedAfterAcceptance) {
        // 容器关闭时也会清除仅供测试使用的代理状态。
      } finally {
        exchange.close();
      }
    });
    server.start();

    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "r16-http-dispatch-" + suffix;
    String externalRequestId = "r16-dispatch-request-" + suffix;
    Long fileId = platformJdbc.queryForObject(
        insertFileRecordSql,
        Long.class,
        TENANT,
        "r16-dispatch-file-" + suffix,
        "r16-" + suffix + ".json",
        "r16-" + suffix + ".json",
        "/tmp/r16-" + suffix + ".json",
        "r16-trace-" + suffix);
    platformJdbc.update(
        insertFileChannelConfigSql,
        TENANT,
        channelCode,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/dispatch",
        channelCode);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "DISPATCH", "dispatch", TriggerType.API, BIZ_DATE);
    String queueCode = "r16-dispatch-q-" + suffix;
    platformJdbc.update(insertResourceQueueSql, TENANT, queueCode);
    platformJdbc.update(updateJobDefinitionSql, queueCode, TENANT, seed.jobCode());
    Map<String, Object> params = Map.of(
        "fileId",
        String.valueOf(fileId),
        "channelCode",
        channelCode,
        "dispatchTarget",
        "/tmp",
        "externalRequestId",
        externalRequestId,
        "receiptCode",
        "R16-" + suffix,
        "ackRequired",
        false,
        "forceRetry",
        false);

    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> firstReport = new AtomicReference<>();
    AtomicReference<TaskExecutionReportCommand> replacementReport = new AtomicReference<>();
    AtomicReference<BizException> staleReportFailure = new AtomicReference<>();
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch replacementReportStarted = new CountDownLatch(1);
    CountDownLatch releaseReplacementReport = new CountDownLatch(1);
    CountDownLatch staleReportRejected = new CountDownLatch(1);
    doAnswer(invocation -> {
          TaskExecutionReportCommand report = invocation.getArgument(1);
          int attempt = reportAttempts.incrementAndGet();
          if (attempt == 1) {
            firstReport.set(report);
            firstReportStarted.countDown();
            if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException("timed out waiting to release stale DISPATCH REPORT");
            }
            try {
              return invocation.callRealMethod();
            } catch (BizException staleReport) {
              staleReportFailure.set(staleReport);
              staleReportRejected.countDown();
              throw staleReport;
            }
          }
          if (attempt == 2) {
            replacementReport.set(report);
            replacementReportStarted.countDown();
            if (!releaseReplacementReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException(
                  "timed out waiting to release replacement DISPATCH REPORT");
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));

    String workerPoolCode = "r16-dispatch-" + suffix;
    String consumerGroupId = "batch-worker-dispatch-r16-" + suffix;
    ensureKafkaTopicsExist(List.of(
        BatchTopics.TASK_DISPATCH_DISPATCH,
        BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_DISPATCH, workerPoolCode)));
    Path worker1Log = createWorkerLog("r16-dispatch-worker-1-");
    Path worker2Log = createWorkerLog("r16-dispatch-worker-2-");
    Process worker1 = null;
    Process worker2 = null;
    try {
      worker1 = startDispatchWorker(workerPoolCode, consumerGroupId, worker1Log, "attempt-1");
      awaitDispatchWorkerStarted(worker1, worker1Log);
      awaitWorkerPoolOnline(workerPoolCode, "DISPATCH");

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r16-dispatch-reclaim-" + suffix,
          params));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportStarted.await(300, TimeUnit.SECONDS))
          .as("HTTP receiver accepted the dispatch before the first Worker reaches REPORT")
          .isTrue();
      TaskExecutionReportCommand i1Report = firstReport.get();
      assertThat(i1Report).isNotNull();
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");
      assertThat(requestCount.get()).isEqualTo(1);
      assertThat(receiverEffects.get()).isEqualTo(1);
      String selectedDispatchStatus = platformJdbc.queryForObject(
          selectDispatchStatusSql, String.class, TENANT, fileId, channelCode);
      assertThat(selectedDispatchStatus).isEqualTo("ACKED");

      stopProcess(worker1);
      worker1 = null;
      Long partitionId =
          platformJdbc.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(updateJobPartitionSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      assertThat(reportStatus(seed)).isEqualTo("READY");
      outboxPublishSupport.publishAllPending(TENANT);

      worker2 = startDispatchWorker(workerPoolCode, consumerGroupId, worker2Log, "attempt-2");
      awaitDispatchWorkerStarted(worker2, worker2Log);
      awaitWorkerPoolOnline(workerPoolCode, "DISPATCH");
      assertThat(replacementReportStarted.await(300, TimeUnit.SECONDS))
          .as("replacement DISPATCH invocation completes from the persisted ACKED record")
          .isTrue();
      TaskExecutionReportCommand i2Report = replacementReport.get();
      assertThat(i2Report).isNotNull();
      assertThat(i2Report.partitionInvocationId()).isNotEqualTo(i1Report.partitionInvocationId());
      assertThat(requestCount.get()).isEqualTo(1);
      assertThat(receiverEffects.get()).isEqualTo(1);
      String selectedDispatchStatusRead2 = platformJdbc.queryForObject(
          selectDispatchStatusSql, String.class, TENANT, fileId, channelCode);
      assertThat(selectedDispatchStatusRead2).isEqualTo("ACKED");

      releaseFirstReport.countDown();
      assertThat(staleReportRejected.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(staleReportFailure.get().getCode()).isEqualTo(ResultCode.FORBIDDEN);
      assertThat(staleReportFailure.get().getMessageKey()).isEqualTo("error.worker.not_owner");
      releaseReplacementReport.countDown();

      await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
        assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
        assertThat(reportOutboxCount(seed)).isZero();
        String selectedFileStatus =
            platformJdbc.queryForObject(selectFileStatusSql, String.class, TENANT, fileId);
        assertThat(selectedFileStatus).isEqualTo("DISPATCHED");
        Integer selectedCount =
            platformJdbc.queryForObject(selectCountSql, Integer.class, TENANT, fileId, channelCode);
        assertThat(selectedCount).isEqualTo(1);
        Integer selectedCountRead2 =
            platformJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, fileId);
        assertThat(selectedCountRead2).isEqualTo(1);
      });
    } finally {
      releaseFirstReport.countDown();
      releaseReplacementReport.countDown();
      stopProcess(worker1);
      stopProcess(worker2);
      server.stop(0);
    }
  }

  @Test
  @DisplayName("API_PUSH 接收落盘后独立 Worker 被强杀,替代进程重领保持 UNKNOWN 且不重复外发")
  void shouldNotRedeliverAfterWorkerCrash_whenApiPushAcceptedBeforeOutcomePersisted()
      throws Exception {
    Path sourceFile = Files.createTempFile("r25-api-push-crash-source-", ".json");
    Path receiverLedger = Files.createTempFile("r25-api-push-crash-ledger-", ".jsonl");
    byte[] content = "{\"dispatch\":\"once-before-crash\"}".getBytes(StandardCharsets.UTF_8);
    Files.write(sourceFile, content);
    String apiKey = "test-r25-api-push-key";
    String authorization = "Bearer test-r25-api-push-token";
    AtomicInteger receivedRequests = new AtomicInteger();
    AtomicInteger committedSideEffects = new AtomicInteger();
    AtomicReference<String> acceptedApiKey = new AtomicReference<>();
    AtomicReference<String> acceptedAuthorization = new AtomicReference<>();
    AtomicReference<String> acceptedIdempotencyKey = new AtomicReference<>();
    AtomicReference<String> acceptedPayload = new AtomicReference<>();
    CountDownLatch acceptedBeforeResponse = new CountDownLatch(1);
    CountDownLatch releaseResponse = new CountDownLatch(1);
    HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService serverExecutor = Executors.newCachedThreadPool();
    receiver.setExecutor(serverExecutor);
    receiver.createContext("/dispatch", exchange -> {
      if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        return;
      }
      receivedRequests.incrementAndGet();
      try {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String payloadJson = new String(body, StandardCharsets.UTF_8);
        acceptedApiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
        acceptedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        acceptedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        acceptedPayload.set(payloadJson);
        Files.writeString(
            receiverLedger, payloadJson + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        committedSideEffects.incrementAndGet();
        acceptedBeforeResponse.countDown();
        try {
          releaseResponse.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        try {
          exchange.sendResponseHeaders(200, -1);
        } catch (IOException killedWorkerConnection) {
          // 独立 Worker 已在接收端落盘后被强杀，此处不撤销已提交台账。
        }
      } finally {
        exchange.close();
      }
    });
    Process firstWorker = null;
    Process replacementWorker = null;
    receiver.start();
    try {
      String endpoint = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/dispatch";
      Map<String, Object> channelConfig =
          Map.of("api_push_api_key", apiKey, "authorization", authorization);
      DispatchCrashScenario scenario =
          prepareDispatchCrashScenario(sourceFile, "API_PUSH", endpoint, channelConfig);
      Map<String, String> workerOverrides = Map.of(
          "batch.worker.dispatch.http-channel.call-timeout-millis", "120000",
          "batch.worker.dispatch.health.enabled", "false");
      Path firstWorkerLog = createDispatchCrashLog("r25-api-push-crash-worker-1-");
      Path replacementWorkerLog = createDispatchCrashLog("r25-api-push-crash-worker-2-");
      firstWorker = startDispatchWorker(
          scenario.workerPoolCode(),
          scenario.consumerGroupId(),
          firstWorkerLog,
          "api-push-crash-first",
          workerOverrides);
      awaitDispatchWorkerStarted(firstWorker, firstWorkerLog);
      awaitWorkerPoolOnline(scenario.workerPoolCode(), "DISPATCH");
      launchDispatchCrashScenario(scenario);

      boolean accepted = acceptedBeforeResponse.await(180, TimeUnit.SECONDS);
      assertThat(accepted).as("真实 API_PUSH 接收端在响应返回前已追加业务台账").isTrue();
      assertDispatchCrashWindow(scenario, firstWorker);
      stopProcess(firstWorker);
      firstWorker = null;
      releaseResponse.countDown();
      reclaimDispatchCrashScenario(scenario);

      replacementWorker = startDispatchWorker(
          scenario.workerPoolCode(),
          scenario.consumerGroupId(),
          replacementWorkerLog,
          "api-push-crash-replacement",
          workerOverrides);
      awaitDispatchWorkerStarted(replacementWorker, replacementWorkerLog);
      awaitWorkerPoolOnline(scenario.workerPoolCode(), "DISPATCH");
      assertUnknownDispatchCrashOutcome(scenario);

      assertThat(receivedRequests).hasValue(1);
      assertThat(committedSideEffects).hasValue(1);
      String receivedApiKey = acceptedApiKey.get();
      String receivedAuthorization = acceptedAuthorization.get();
      String receivedIdempotencyKey = acceptedIdempotencyKey.get();
      String receivedPayload = acceptedPayload.get();
      assertThat(receivedApiKey).isEqualTo(apiKey);
      assertThat(receivedAuthorization).isEqualTo(authorization);
      assertThat(receivedIdempotencyKey).isNotBlank();
      List<String> committedReceipts = Files.readAllLines(receiverLedger, StandardCharsets.UTF_8);
      assertThat(committedReceipts).containsExactly(receivedPayload);
      Map<?, ?> decodedPayload = objectMapper.readValue(receivedPayload, Map.class);
      Object receivedTenant = decodedPayload.get("tenantId");
      Object receivedExternalRequestId = decodedPayload.get("externalRequestId");
      assertThat(receivedTenant).isEqualTo(TENANT);
      assertThat(receivedExternalRequestId).isEqualTo(scenario.externalRequestId());
    } finally {
      releaseResponse.countDown();
      stopProcess(firstWorker);
      stopProcess(replacementWorker);
      receiver.stop(0);
      serverExecutor.shutdownNow();
      boolean receiverStopped = serverExecutor.awaitTermination(10, TimeUnit.SECONDS);
      assertThat(receiverStopped).as("API_PUSH 本用例接收端线程已经停止").isTrue();
      Files.deleteIfExists(sourceFile);
      Files.deleteIfExists(receiverLedger);
    }
  }

  @Test
  @DisplayName("OSS 主对象与清单提交并 ACKED 后丢失 REPORT,替代独立 Worker 成功恢复且两个对象版本不变")
  void shouldReuseOssObjectsAfterReclaim_whenReportWasLostAfterManifestCommit() throws Exception {
    final String selectTaskOutcomeSql = """
        select task.id, task.job_partition_id, task.task_status, instance.instance_status
        from batch.job_task task
        join batch.job_instance instance on instance.tenant_id=task.tenant_id
            and instance.id=task.job_instance_id
        where instance.tenant_id=? and instance.dedup_key=?
        """;
    final String selectDispatchSql = """
        select count(*)::int as dispatch_count, max(dispatch_attempt)::int as dispatch_attempt,
               min(dispatch_status) as dispatch_status
        from batch.file_dispatch_record where tenant_id=? and file_id=? and channel_code=?
        """;
    final String selectRetryCountSql = """
        select count(*)::int from batch.retry_schedule
        where tenant_id=? and related_type='JOB_PARTITION' and related_id=?
        """;
    final String selectDeadLetterCountSql = """
        select count(*)::int from batch.dead_letter_task
        where tenant_id=? and source_type='JOB_PARTITION' and source_id=?
        """;
    final String selectWorkflowStatusSql = """
        select run.run_status from batch.trigger_request request
        join batch.workflow_run run on run.tenant_id=request.tenant_id
            and run.trigger_request_id=request.id
        where request.tenant_id=? and request.request_id=?
        """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where tenant_id=? and id=?
        """;
    assertThat(s3BackendActive()).as("此场景需要真实 S3 兼容测试后端").isTrue();
    String suffix = Long.toUnsignedString(System.nanoTime());
    String bucket = "r25-oss-report-loss-" + suffix;
    String objectKey = "dispatch/payload.txt";
    String manifestKey = objectKey + ".chk";
    byte[] content = ("r25-oss-complete-before-report-" + suffix).getBytes(StandardCharsets.UTF_8);
    Path sourceFile = Files.createTempFile("r25-oss-report-loss-source-", ".txt");
    Files.write(sourceFile, content);
    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> firstReport = new AtomicReference<>();
    AtomicReference<TaskExecutionReportCommand> replacementReport = new AtomicReference<>();
    AtomicReference<BizException> staleReportFailure = new AtomicReference<>();
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch replacementReportStarted = new CountDownLatch(1);
    CountDownLatch releaseReplacementReport = new CountDownLatch(1);
    CountDownLatch staleReportRejected = new CountDownLatch(1);
    Process firstWorker = null;
    Process replacementWorker = null;
    boolean bucketCreated = false;
    try (S3Client storageClient = OBJECT_STORE.client()) {
      try {
        storageClient.createBucket(request -> request.bucket(bucket));
        bucketCreated = true;
        storageClient.putBucketVersioning(request -> request
            .bucket(bucket)
            .versioningConfiguration(
                configuration -> configuration.status(BucketVersioningStatus.ENABLED)));
        String endpoint = "oss://" + bucket + "/dispatch";
        Map<String, Object> channelConfig = Map.of(
            "oss_bucket",
            bucket,
            "oss_object_prefix",
            "dispatch",
            "oss_object_name",
            "payload.txt",
            "dispatch_manifest_enabled",
            true);
        DispatchCrashScenario scenario =
            prepareDispatchCrashScenario(sourceFile, "OSS", endpoint, channelConfig);
        doAnswer(invocation -> {
              TaskExecutionReportCommand report = invocation.getArgument(1);
              int attempt = reportAttempts.incrementAndGet();
              if (attempt == 1) {
                firstReport.set(report);
                firstReportStarted.countDown();
                if (!releaseFirstReport.await(240, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("timed out waiting to release stale OSS REPORT");
                }
                try {
                  return invocation.callRealMethod();
                } catch (BizException staleReport) {
                  staleReportFailure.set(staleReport);
                  staleReportRejected.countDown();
                  throw staleReport;
                }
              }
              if (attempt == 2) {
                replacementReport.set(report);
                replacementReportStarted.countDown();
                if (!releaseReplacementReport.await(180, TimeUnit.SECONDS)) {
                  throw new IllegalStateException(
                      "timed out waiting to release replacement OSS REPORT");
                }
              }
              return invocation.callRealMethod();
            })
            .when(taskControllerApplicationService)
            .report(any(Long.class), any(TaskExecutionReportCommand.class));
        Map<String, String> workerOverrides = Map.of(
            "batch.storage.s3.access-key",
            s3AccessKey(),
            "batch.storage.s3.secret-key",
            s3SecretKey(),
            "batch.storage.s3.auto-create-bucket",
            "false",
            "batch.worker.dispatch.health.enabled",
            "false");
        Path firstWorkerLog = createDispatchCrashLog("r25-oss-complete-report-worker-1-");
        Path replacementWorkerLog = createDispatchCrashLog("r25-oss-complete-report-worker-2-");
        firstWorker = startDispatchWorker(
            scenario.workerPoolCode(),
            scenario.consumerGroupId(),
            firstWorkerLog,
            "oss-complete-report-first",
            workerOverrides);
        awaitDispatchWorkerStarted(firstWorker, firstWorkerLog);
        awaitWorkerPoolOnline(scenario.workerPoolCode(), "DISPATCH");
        launchDispatchCrashScenario(scenario);

        boolean firstReportArrived = firstReportStarted.await(300, TimeUnit.SECONDS);
        assertThat(firstReportArrived)
            .as("原独立 OSS Worker 在主对象和清单提交后发送真实 REPORT")
            .isTrue();
        TaskExecutionReportCommand first = firstReport.get();
        assertThat(first).isNotNull();
        boolean firstSucceeded = first.success();
        String firstInvocationId = first.partitionInvocationId();
        String firstWorkerId = first.workerId();
        assertThat(firstSucceeded).isTrue();
        assertThat(firstInvocationId).isNotBlank();
        Map<String, Object> beforeCrash = platformJdbc.queryForMap(
            selectTaskOutcomeSql, TENANT, scenario.seed().dedupKey());
        Object firstTaskStatus = beforeCrash.get("task_status");
        Object firstJobStatus = beforeCrash.get("instance_status");
        assertThat(firstTaskStatus).isEqualTo("RUNNING");
        assertThat(firstJobStatus).isEqualTo("RUNNING");
        Long partitionId = ((Number) beforeCrash.get("job_partition_id")).longValue();
        Map<String, Object> firstDispatch = platformJdbc.queryForMap(
            selectDispatchSql, TENANT, scenario.fileId(), scenario.channelCode());
        assertThat(firstDispatch)
            .containsEntry("dispatch_count", 1)
            .containsEntry("dispatch_attempt", 1)
            .containsEntry("dispatch_status", "ACKED");
        Map<String, String> committedVersions =
            assertCommittedOssReportArtifacts(storageClient, scenario, bucket, content);
        byte[] committedManifest = readObject(bucket, manifestKey);
        boolean firstWorkerAlive = firstWorker.isAlive();
        assertThat(firstWorkerAlive).isTrue();

        stopProcess(firstWorker);
        boolean firstWorkerStillAlive = firstWorker.isAlive();
        assertThat(firstWorkerStillAlive).isFalse();
        firstWorker = null;
        reclaimDispatchCrashScenario(scenario);
        replacementWorker = startDispatchWorker(
            scenario.workerPoolCode(),
            scenario.consumerGroupId(),
            replacementWorkerLog,
            "oss-complete-report-replacement",
            workerOverrides);
        awaitDispatchWorkerStarted(replacementWorker, replacementWorkerLog);
        awaitWorkerPoolOnline(scenario.workerPoolCode(), "DISPATCH");
        boolean replacementReportArrived = replacementReportStarted.await(300, TimeUnit.SECONDS);
        assertThat(replacementReportArrived)
            .as("替代独立 OSS Worker 实际消费并从已持久 ACKED 状态完成 REPORT")
            .isTrue();
        TaskExecutionReportCommand replacement = replacementReport.get();
        assertThat(replacement).isNotNull();
        boolean replacementSucceeded = replacement.success();
        String replacementInvocationId = replacement.partitionInvocationId();
        String replacementWorkerId = replacement.workerId();
        assertThat(replacementSucceeded).isTrue();
        assertThat(replacementInvocationId).isNotBlank().isNotEqualTo(firstInvocationId);
        assertThat(replacementWorkerId).isNotEqualTo(firstWorkerId);
        Map<String, Object> replacementDispatch = platformJdbc.queryForMap(
            selectDispatchSql, TENANT, scenario.fileId(), scenario.channelCode());
        assertThat(replacementDispatch)
            .containsEntry("dispatch_count", 1)
            .containsEntry("dispatch_attempt", 1)
            .containsEntry("dispatch_status", "ACKED");

        releaseFirstReport.countDown();
        boolean rejectedStaleReport = staleReportRejected.await(30, TimeUnit.SECONDS);
        assertThat(rejectedStaleReport).as("旧 Worker 成功 REPORT 在替代调用领取后被拒绝").isTrue();
        BizException staleFailure = staleReportFailure.get();
        assertThat(staleFailure).isNotNull();
        ResultCode rejectionCode = staleFailure.getCode();
        String rejectionKey = staleFailure.getMessageKey();
        assertThat(rejectionCode).isEqualTo(ResultCode.FORBIDDEN);
        assertThat(rejectionKey).isEqualTo("error.worker.not_owner");
        releaseReplacementReport.countDown();

        await()
            .atMost(Duration.ofSeconds(120))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
              Map<String, Object> outcome = platformJdbc.queryForMap(
                  selectTaskOutcomeSql, TENANT, scenario.seed().dedupKey());
              assertThat(outcome)
                  .containsEntry("task_status", "SUCCESS")
                  .containsEntry("instance_status", "SUCCESS");
              String workflowStatus = platformJdbc.queryForObject(
                  selectWorkflowStatusSql, String.class, TENANT, scenario.seed().requestId());
              String fileStatus = platformJdbc.queryForObject(
                  selectFileStatusSql, String.class, TENANT, scenario.fileId());
              Integer retryCount = platformJdbc.queryForObject(
                  selectRetryCountSql, Integer.class, TENANT, partitionId);
              Integer deadLetterCount = platformJdbc.queryForObject(
                  selectDeadLetterCountSql, Integer.class, TENANT, partitionId);
              int pendingReports = reportOutboxCount(scenario.seed());
              assertThat(workflowStatus).isEqualTo("SUCCESS");
              assertThat(fileStatus).isEqualTo("DISPATCHED");
              assertThat(retryCount).isZero();
              assertThat(deadLetterCount).isZero();
              assertThat(pendingReports).isZero();
            });
        Map<String, Object> finalDispatch = platformJdbc.queryForMap(
            selectDispatchSql, TENANT, scenario.fileId(), scenario.channelCode());
        assertThat(finalDispatch)
            .containsEntry("dispatch_count", 1)
            .containsEntry("dispatch_attempt", 1)
            .containsEntry("dispatch_status", "ACKED");
        Map<String, String> retainedVersions =
            assertCommittedOssReportArtifacts(storageClient, scenario, bucket, content);
        byte[] retainedManifest = readObject(bucket, manifestKey);
        assertThat(retainedVersions).isEqualTo(committedVersions);
        assertThat(retainedManifest).isEqualTo(committedManifest);
      } finally {
        releaseFirstReport.countDown();
        releaseReplacementReport.countDown();
        stopProcess(firstWorker);
        stopProcess(replacementWorker);
        if (bucketCreated) {
          deleteDispatchCrashBucket(storageClient, bucket);
        }
      }
    } finally {
      Files.deleteIfExists(sourceFile);
      boolean sourceRemains = Files.exists(sourceFile);
      assertThat(sourceRemains).as("仅此场景拥有的源文件已删除").isFalse();
    }
  }

  private Map<String, String> assertCommittedOssReportArtifacts(
      S3Client storageClient, DispatchCrashScenario scenario, String bucket, byte[] expectedContent)
      throws Exception {
    final String selectFileMetadataSql = """
        select metadata_json::text from batch.file_record where tenant_id=? and id=?
        """;
    String objectKey = "dispatch/payload.txt";
    String manifestKey = objectKey + ".chk";
    byte[] actualContent = readObject(bucket, objectKey);
    byte[] manifestBytes = readObject(bucket, manifestKey);
    assertThat(actualContent).isEqualTo(expectedContent);
    JsonNode manifest = objectMapper.readTree(manifestBytes);
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    byte[] digest = sha256.digest(expectedContent);
    String expectedChecksum = HexFormat.of().formatHex(digest);
    String schemaVersion = manifest.path("schemaVersion").asText();
    String tenantId = manifest.path("tenantId").asText();
    String traceId = manifest.path("traceId").asText();
    long fileId = manifest.path("fileId").asLong();
    String channelCode = manifest.path("channelCode").asText();
    String externalRequestId = manifest.path("externalRequestId").asText();
    String bizDate = manifest.path("bizDate").asText();
    String fileName = manifest.path("fileName").asText();
    String targetRef = manifest.path("targetRef").asText();
    String manifestRef = manifest.path("manifestRef").asText();
    long sizeBytes = manifest.path("sizeBytes").asLong();
    String checksumType = manifest.path("checksumType").asText();
    String checksumValue = manifest.path("checksumValue").asText();
    assertThat(schemaVersion).isEqualTo("dispatch-sidecar-manifest-v1");
    assertThat(tenantId).isEqualTo(TENANT);
    assertThat(traceId).isEqualTo("r25-dispatch-crash-" + scenario.channelCode());
    assertThat(fileId).isEqualTo(scenario.fileId());
    assertThat(channelCode).isEqualTo(scenario.channelCode());
    assertThat(externalRequestId).isEqualTo(scenario.externalRequestId());
    assertThat(bizDate).isEqualTo(BIZ_DATE.toString());
    assertThat(fileName).isEqualTo("payload.txt");
    assertThat(targetRef).isEqualTo("oss://" + bucket + "/" + objectKey);
    assertThat(manifestRef).isEqualTo("oss://" + bucket + "/" + manifestKey);
    assertThat(sizeBytes).isEqualTo(expectedContent.length);
    assertThat(checksumType).isEqualTo("SHA-256");
    assertThat(checksumValue).isEqualTo(expectedChecksum);
    String fileMetadataJson =
        platformJdbc.queryForObject(selectFileMetadataSql, String.class, TENANT, scenario.fileId());
    JsonNode fileMetadata = objectMapper.readTree(fileMetadataJson);
    String persistedManifestRef = fileMetadata.path("dispatchManifestRef").asText();
    String persistedManifestChecksum =
        fileMetadata.path("dispatchManifestChecksum").asText();
    long persistedManifestSize = fileMetadata.path("dispatchManifestSizeBytes").asLong();
    byte[] manifestDigest = sha256.digest(manifestBytes);
    String expectedManifestChecksum = HexFormat.of().formatHex(manifestDigest);
    assertThat(persistedManifestRef).isEqualTo(manifestRef);
    assertThat(persistedManifestChecksum).isEqualTo(expectedManifestChecksum);
    assertThat(persistedManifestSize).isEqualTo(manifestBytes.length);
    ListObjectVersionsResponse stored =
        storageClient.listObjectVersions(request -> request.bucket(bucket));
    List<ObjectVersion> versions = stored.versions();
    assertThat(versions).hasSize(2);
    Map<String, String> versionIds = new LinkedHashMap<>();
    for (ObjectVersion version : versions) {
      String key = version.key();
      String versionId = version.versionId();
      assertThat(versionId).isNotBlank().isNotEqualTo("null");
      String priorVersion = versionIds.put(key, versionId);
      assertThat(priorVersion).as("主对象及旁挂清单均只有一个真实存储版本").isNull();
    }
    assertThat(versionIds).containsOnlyKeys(objectKey, manifestKey);
    boolean hasDeleteMarkers = !stored.deleteMarkers().isEmpty();
    assertThat(hasDeleteMarkers).isFalse();
    return versionIds;
  }

  @Test
  @DisplayName("OSS 主对象实际提交且确认未返回时独立 Worker 被强杀,重领保留 UNKNOWN 且没有覆盖版本")
  void shouldNotUploadAfterWorkerCrash_whenOssObjectCommittedBeforeAcknowledgement()
      throws Exception {
    assertThat(s3BackendActive()).as("此场景需要真实 S3 兼容测试后端").isTrue();
    URI storageEndpoint = URI.create(s3Endpoint());
    String storageHost = storageEndpoint.getHost();
    assertThat(storageHost).as("Toxiproxy 只连接本轮本地测试对象存储").isIn("localhost", "127.0.0.1");
    int storagePort = storageEndpoint.getPort();
    Testcontainers.exposeHostPorts(storagePort);
    GenericContainer<?> toxiproxy = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
        .withLabel(TestContainerLabels.OWNER, TestContainerLabels.OWNER_VALUE)
        .withExposedPorts(8474, 8666)
        .waitingFor(Wait.forHttp("/version").forPort(8474));
    String suffix = Long.toUnsignedString(System.nanoTime());
    String bucket = "r25-oss-crash-" + suffix;
    String objectKey = "dispatch/payload.txt";
    String manifestKey = objectKey + ".chk";
    byte[] content = ("r25-oss-before-ack-" + suffix).getBytes(StandardCharsets.UTF_8);
    Path sourceFile = Files.createTempFile("r25-oss-crash-source-", ".txt");
    Files.write(sourceFile, content);
    Process firstWorker = null;
    Process replacementWorker = null;
    Proxy storageProxy = null;
    boolean bucketCreated = false;
    try (S3Client storageClient = OBJECT_STORE.client()) {
      try {
        storageClient.createBucket(request -> request.bucket(bucket));
        bucketCreated = true;
        storageClient.putBucketVersioning(request -> request
            .bucket(bucket)
            .versioningConfiguration(
                configuration -> configuration.status(BucketVersioningStatus.ENABLED)));
        toxiproxy.start();
        ToxiproxyClient proxyClient =
            new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getMappedPort(8474));
        storageProxy = proxyClient.createProxy(
            "r25-oss-crash", "0.0.0.0:8666", "host.testcontainers.internal:" + storagePort);
        String proxiedEndpoint =
            "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        String dispatchTarget = "oss://" + bucket + "/dispatch";
        Map<String, Object> channelConfig = Map.of(
            "oss_bucket",
            bucket,
            "oss_object_prefix",
            "dispatch",
            "oss_object_name",
            "payload.txt",
            "dispatch_manifest_enabled",
            true);
        DispatchCrashScenario scenario =
            prepareDispatchCrashScenario(sourceFile, "OSS", dispatchTarget, channelConfig);
        Map<String, String> workerOverrides = Map.of(
            "batch.storage.s3.endpoint", proxiedEndpoint,
            "batch.storage.s3.access-key", s3AccessKey(),
            "batch.storage.s3.secret-key", s3SecretKey(),
            "batch.storage.s3.read-timeout-ms", "180000",
            "batch.storage.s3.max-attempts", "1",
            "batch.storage.s3.auto-create-bucket", "false",
            "batch.worker.dispatch.health.enabled", "false");
        Path firstWorkerLog = createDispatchCrashLog("r25-oss-crash-worker-1-");
        Path replacementWorkerLog = createDispatchCrashLog("r25-oss-crash-worker-2-");
        firstWorker = startDispatchWorker(
            scenario.workerPoolCode(),
            scenario.consumerGroupId(),
            firstWorkerLog,
            "oss-crash-first",
            workerOverrides);
        awaitDispatchWorkerStarted(firstWorker, firstWorkerLog);
        awaitWorkerPoolOnline(scenario.workerPoolCode(), "DISPATCH");
        // 延迟真实 MinIO 的 PUT 确认；主对象已提交时 Worker 仍无法继续写旁挂清单。
        storageProxy.toxics().latency("hold_oss_put_ack", ToxicDirection.DOWNSTREAM, 60_000);
        launchDispatchCrashScenario(scenario);
        await()
            .atMost(Duration.ofSeconds(45))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
              ListObjectVersionsResponse stored =
                  storageClient.listObjectVersions(request -> request.bucket(bucket));
              List<String> storedKeys =
                  stored.versions().stream().map(ObjectVersion::key).toList();
              assertThat(storedKeys).containsExactly(objectKey);
            });
        ListObjectVersionsResponse committed =
            storageClient.listObjectVersions(request -> request.bucket(bucket));
        List<ObjectVersion> committedVersions = committed.versions();
        assertThat(committedVersions).hasSize(1);
        String committedVersionId = committedVersions.getFirst().versionId();
        assertThat(committedVersionId).isNotBlank().isNotEqualTo("null");
        byte[] committedContent = readObject(bucket, objectKey);
        assertThat(committedContent).isEqualTo(content);
        List<String> committedKeys =
            committedVersions.stream().map(ObjectVersion::key).toList();
        assertThat(committedKeys).doesNotContain(manifestKey);
        assertDispatchCrashWindow(scenario, firstWorker);

        stopProcess(firstWorker);
        firstWorker = null;
        storageProxy.toxics().get("hold_oss_put_ack").remove();
        reclaimDispatchCrashScenario(scenario);
        replacementWorker = startDispatchWorker(
            scenario.workerPoolCode(),
            scenario.consumerGroupId(),
            replacementWorkerLog,
            "oss-crash-replacement",
            workerOverrides);
        awaitDispatchWorkerStarted(replacementWorker, replacementWorkerLog);
        awaitWorkerPoolOnline(scenario.workerPoolCode(), "DISPATCH");
        assertUnknownDispatchCrashOutcome(scenario);

        byte[] finalContent = readObject(bucket, objectKey);
        assertThat(finalContent).isEqualTo(content);
        ListObjectVersionsResponse retained =
            storageClient.listObjectVersions(request -> request.bucket(bucket));
        List<ObjectVersion> retainedVersions = retained.versions();
        assertThat(retainedVersions).hasSize(1);
        ObjectVersion retainedObject = retainedVersions.getFirst();
        String retainedKey = retainedObject.key();
        String retainedVersionId = retainedObject.versionId();
        boolean hasDeleteMarkers = !retained.deleteMarkers().isEmpty();
        assertThat(retainedKey).isEqualTo(objectKey);
        assertThat(retainedVersionId).isEqualTo(committedVersionId);
        assertThat(hasDeleteMarkers).isFalse();
      } finally {
        stopProcess(firstWorker);
        stopProcess(replacementWorker);
        try {
          if (storageProxy != null) {
            storageProxy.delete();
          }
        } finally {
          toxiproxy.stop();
          if (bucketCreated) {
            deleteDispatchCrashBucket(storageClient, bucket);
          }
        }
      }
    } finally {
      Files.deleteIfExists(sourceFile);
    }
  }

  private Path createDispatchCrashLog(String prefix) throws IOException {
    Path evidenceDirectory = projectRoot().resolve("logs/verification/workflow");
    Files.createDirectories(evidenceDirectory);
    return Files.createTempFile(evidenceDirectory, prefix, ".log");
  }

  private record DispatchCrashScenario(
      LaunchSeed seed,
      Long fileId,
      String channelCode,
      String externalRequestId,
      String workerPoolCode,
      String consumerGroupId,
      String endpoint) {}

  private DispatchCrashScenario prepareDispatchCrashScenario(
      Path sourceFile, String channelType, String endpoint, Map<String, Object> channelConfig)
      throws Exception {
    final String insertFileSql = """
        insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
            storage_path, source_type, file_status, biz_date, trace_id
        ) values (?, ?, 'OUTPUT', 'OUTPUT', ?, ?, 'txt', 'BINARY', 'UTF-8', 'text/plain',
                  ?, 'NONE', 'LOCAL', ?, 'SYSTEM', 'GENERATED', ?, ?) returning id
        """;
    final String insertChannelSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (?, ?, '独立进程外发崩溃恢复', ?, ?, 'NONE', ?::jsonb, 'SYNC', 180, true)
        """;
    final String updateJobRetrySql = """
        update batch.job_definition set retry_policy='FIXED', retry_max_count=3
        where tenant_id=? and job_code=?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "r25-dispatch-crash-" + suffix;
    String fileName = sourceFile.getFileName().toString();
    long fileSize = Files.size(sourceFile);
    Long fileId = platformJdbc.queryForObject(
        insertFileSql,
        Long.class,
        TENANT,
        "r25-crash-file-" + suffix,
        fileName,
        fileName,
        fileSize,
        sourceFile.toString(),
        BIZ_DATE,
        "r25-crash-file-trace-" + suffix);
    String configJson = objectMapper.writeValueAsString(channelConfig);
    platformJdbc.update(insertChannelSql, TENANT, channelCode, channelType, endpoint, configJson);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "DISPATCH", "dispatch", TriggerType.API, BIZ_DATE);
    platformJdbc.update(updateJobRetrySql, TENANT, seed.jobCode());
    String workerPoolCode = "r25-dispatch-crash-" + suffix;
    ensureKafkaTopicsExist(List.of(
        BatchTopics.TASK_DISPATCH_DISPATCH,
        BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_DISPATCH, workerPoolCode)));
    return new DispatchCrashScenario(
        seed,
        fileId,
        channelCode,
        "r25-crash-request-" + suffix,
        workerPoolCode,
        "r25-dispatch-crash-group-" + suffix,
        endpoint);
  }

  private void launchDispatchCrashScenario(DispatchCrashScenario scenario) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", scenario.fileId().toString());
    params.put("channelCode", scenario.channelCode());
    params.put("dispatchTarget", scenario.endpoint());
    params.put("externalRequestId", scenario.externalRequestId());
    params.put("ackRequired", false);
    params.put("forceRetry", false);
    LaunchRequest launchRequest = new LaunchRequest(
        TENANT,
        scenario.seed().workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        scenario.seed().requestId(),
        "r25-dispatch-crash-" + scenario.channelCode(),
        params);
    launchService.launch(launchRequest);
    outboxPublishSupport.publishAllPending(TENANT);
  }

  private void assertDispatchCrashWindow(DispatchCrashScenario scenario, Process worker) {
    final String selectDispatchStatusSql = """
        select dispatch_status from batch.file_dispatch_record
        where tenant_id=? and file_id=? and channel_code=?
        """;
    String dispatchStatus = platformJdbc.queryForObject(
        selectDispatchStatusSql, String.class, TENANT, scenario.fileId(), scenario.channelCode());
    String jobStatus = reportStatus(scenario.seed());
    boolean workerAlive = worker.isAlive();
    assertThat(dispatchStatus).as("外部副作用存在时，平台外发台账尚未确认").isEqualTo("CREATED");
    assertThat(jobStatus).isEqualTo("RUNNING");
    assertThat(workerAlive).isTrue();
  }

  private void reclaimDispatchCrashScenario(DispatchCrashScenario scenario) {
    final String selectPartitionIdSql = """
        select partition.id from batch.job_partition partition
        join batch.job_instance instance on instance.tenant_id=partition.tenant_id
            and instance.id=partition.job_instance_id
        where instance.tenant_id=? and instance.dedup_key=?
        """;
    final String expireLeaseSql = """
        update batch.job_partition set lease_expire_at=current_timestamp-interval '1 minute'
        where tenant_id=? and id=?
        """;
    Long partitionId = platformJdbc.queryForObject(
        selectPartitionIdSql, Long.class, TENANT, scenario.seed().dedupKey());
    JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
    platformJdbc.update(expireLeaseSql, TENANT, partitionId);
    partitionReclaimUnit.reclaim(partition);
    String jobStatus = reportStatus(scenario.seed());
    assertThat(jobStatus).isEqualTo("READY");
    outboxPublishSupport.publishAllPending(TENANT);
  }

  private void assertUnknownDispatchCrashOutcome(DispatchCrashScenario scenario) {
    final String selectTaskOutcomeSql = """
        select task.task_status, task.error_code, task.failure_class, task.job_partition_id,
               instance.instance_status
        from batch.job_task task
        join batch.job_instance instance on instance.tenant_id=task.tenant_id
            and instance.id=task.job_instance_id
        where instance.tenant_id=? and instance.dedup_key=?
        """;
    final String selectRetryCountSql = """
        select count(*) from batch.retry_schedule
        where tenant_id=? and related_type='JOB_PARTITION' and related_id=?
        """;
    final String selectDeadLetterSql = """
        select count(*) as dead_letter_count, coalesce(max(max_replay_count), -1) as replay_budget
        from batch.dead_letter_task
        where tenant_id=? and source_type='JOB_PARTITION' and source_id=?
        """;
    final String selectDispatchSql = """
        select dispatch_status, dispatch_attempt, error_code
        from batch.file_dispatch_record where tenant_id=? and file_id=? and channel_code=?
        """;
    final String selectWorkflowStatusSql = """
        select run.run_status from batch.trigger_request request
        join batch.workflow_run run on run.tenant_id=request.tenant_id
            and run.trigger_request_id=request.id
        where request.tenant_id=? and request.request_id=?
        """;
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> outcome = platformJdbc.queryForMap(
              selectTaskOutcomeSql, TENANT, scenario.seed().dedupKey());
          Object taskStatus = outcome.get("task_status");
          Object taskErrorCode = outcome.get("error_code");
          Object failureClass = outcome.get("failure_class");
          Object instanceStatus = outcome.get("instance_status");
          assertThat(taskStatus).isEqualTo("FAILED");
          assertThat(taskErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
          assertThat(failureClass).isEqualTo("UNKNOWN");
          assertThat(instanceStatus).isEqualTo("FAILED");
          Long partitionId = ((Number) outcome.get("job_partition_id")).longValue();
          Long retryCount =
              platformJdbc.queryForObject(selectRetryCountSql, Long.class, TENANT, partitionId);
          assertThat(retryCount).isZero();
          Map<String, Object> deadLetter =
              platformJdbc.queryForMap(selectDeadLetterSql, TENANT, partitionId);
          Object deadLetterCount = deadLetter.get("dead_letter_count");
          Object replayBudget = deadLetter.get("replay_budget");
          assertThat(deadLetterCount).isEqualTo(1L);
          assertThat(replayBudget).isEqualTo(0);
          Map<String, Object> dispatch = platformJdbc.queryForMap(
              selectDispatchSql, TENANT, scenario.fileId(), scenario.channelCode());
          Object dispatchStatus = dispatch.get("dispatch_status");
          Object dispatchAttempt = dispatch.get("dispatch_attempt");
          Object dispatchErrorCode = dispatch.get("error_code");
          assertThat(dispatchStatus).isEqualTo("FAILED");
          assertThat(dispatchAttempt).isEqualTo(1);
          assertThat(dispatchErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
          String workflowStatus = platformJdbc.queryForObject(
              selectWorkflowStatusSql, String.class, TENANT, scenario.seed().requestId());
          assertThat(workflowStatus).isEqualTo("FAILED");
          Integer pendingReports = reportOutboxCount(scenario.seed());
          assertThat(pendingReports).isZero();
        });
  }

  private void deleteDispatchCrashBucket(S3Client storageClient, String bucket) {
    for (ListObjectVersionsResponse page :
        storageClient.listObjectVersionsPaginator(request -> request.bucket(bucket))) {
      for (ObjectVersion version : page.versions()) {
        storageClient.deleteObject(
            request -> request.bucket(bucket).key(version.key()).versionId(version.versionId()));
      }
      for (var marker : page.deleteMarkers()) {
        storageClient.deleteObject(
            request -> request.bucket(bucket).key(marker.key()).versionId(marker.versionId()));
      }
    }
    ListObjectVersionsResponse remaining =
        storageClient.listObjectVersions(request -> request.bucket(bucket));
    assertThat(remaining.versions()).isEmpty();
    assertThat(remaining.deleteMarkers()).isEmpty();
    storageClient.deleteBucket(request -> request.bucket(bucket));
    boolean bucketMissing = false;
    try {
      storageClient.headBucket(request -> request.bucket(bucket));
    } catch (S3Exception deletedBucket) {
      assertThat(deletedBucket.statusCode()).isEqualTo(404);
      bucketMissing = true;
    }
    assertThat(bucketMissing).as("仅本用例的独享版本桶已删除并回读确认").isTrue();
  }

  @Test
  @DisplayName("SMTP 收件后 DISPATCH Worker 被强杀,reclaim 后不重复外发")
  void shouldNotRedeliverAfterWorkerCrash_whenSmtpAcceptedMessageBeforeOutcomePersisted()
      throws Exception {
    final String insertFileSql = """
        insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
            storage_path, source_type, file_status, biz_date, trace_id
        ) values (
            ?, ?, 'OUTPUT', 'OUTPUT', ?, ?, 'txt', 'BINARY', 'UTF-8', 'text/plain',
            ?, 'NONE', 'LOCAL', ?, 'SYSTEM', 'GENERATED', ?, ?
        ) returning id
        """;
    final String insertEmailChannelSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (
            ?, ?, 'R25 SMTP crash recovery', 'EMAIL', ?, 'NONE',
            jsonb_build_object(
                'channel_type', 'EMAIL',
                'smtp_host', '127.0.0.1',
                'smtp_port', ?,
                'smtp_username', 'sender@example.test',
                'smtp_password', 'test-secret',
                'smtp_starttls', false,
                'mail_from', 'sender@example.test',
                'mail_to', 'receiver@example.test'
            ),
            'SYNC', 10, true
        )
        """;
    final String updateJobRetrySql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ?
        where tenant_id = ? and job_code = ?
        """;
    final String selectTaskIdSql = """
        select task.id
        from batch.job_task task
        join batch.job_instance ji on ji.tenant_id = task.tenant_id
            and ji.id = task.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectPartitionIdSql = """
        select partition.id
        from batch.job_partition partition
        join batch.job_instance ji on ji.tenant_id = partition.tenant_id
            and ji.id = partition.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectDispatchStatusSql = """
        select dispatch_status from batch.file_dispatch_record
        where tenant_id = ? and file_id = ? and channel_code = ?
        """;
    final String selectDispatchErrorCodeSql = """
        select error_code from batch.file_dispatch_record
        where tenant_id = ? and file_id = ? and channel_code = ?
        """;
    final String selectDispatchCountSql = """
        select count(*)::int from batch.file_dispatch_record
        where tenant_id = ? and file_id = ? and channel_code = ?
        """;
    final String selectWaitingRetryCountSql = """
        select count(*)::int from batch.retry_schedule
        where tenant_id = ? and related_type = 'JOB_PARTITION'
            and related_id = ? and retry_status = 'WAITING'
        """;
    final String selectTaskStatusSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
        """;
    final String selectTaskErrorCodeSql = """
        select error_code from batch.job_task where tenant_id = ? and id = ?
        """;
    final String updatePartitionLeaseSql = """
        update batch.job_partition
        set lease_expire_at = current_timestamp - interval '1 minute'
        where tenant_id = ? and id = ?
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "r25-smtp-crash-" + suffix;
    String consumerGroupId = "batch-worker-dispatch-r25-" + suffix;
    Path sourceFile = Files.createTempFile("r25-smtp-crash-", ".txt");
    byte[] content = ("r25-smtp-crash-content-" + suffix).getBytes(StandardCharsets.UTF_8);
    Files.write(sourceFile, content);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "DISPATCH", "dispatch", TriggerType.API, BIZ_DATE);
    String workerPoolCode = "r25-dispatch-crash-" + suffix;
    Long fileId;
    try (SmtpAcceptThenDropServer smtpServer = new SmtpAcceptThenDropServer(true)) {
      String smtpEndpoint = "smtp://127.0.0.1:" + smtpServer.port();
      fileId = platformJdbc.queryForObject(
          insertFileSql,
          Long.class,
          TENANT,
          "r25-smtp-file-" + suffix,
          "r25-smtp-" + suffix + ".txt",
          "r25-smtp-" + suffix + ".txt",
          content.length,
          sourceFile.toString(),
          BIZ_DATE,
          "r25-smtp-trace-" + suffix);
      platformJdbc.update(
          insertEmailChannelSql, TENANT, channelCode, smtpEndpoint, smtpServer.port());
      platformJdbc.update(updateJobRetrySql, "FIXED", 3, TENANT, seed.jobCode());
      ensureKafkaTopicsExist(List.of(
          BatchTopics.TASK_DISPATCH_DISPATCH,
          BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_DISPATCH, workerPoolCode)));

      Map<String, Object> params = new LinkedHashMap<>();
      params.put("fileId", String.valueOf(fileId));
      params.put("channelCode", channelCode);
      params.put("dispatchTarget", smtpEndpoint);
      params.put("externalRequestId", "r25-smtp-request-" + suffix);
      params.put("receiptCode", "R25-SMTP-" + suffix);
      params.put("ackRequired", false);
      params.put("forceRetry", false);

      Path firstWorkerLog = createWorkerLog("r25-smtp-dispatch-worker-1-");
      Path replacementWorkerLog = createWorkerLog("r25-smtp-dispatch-worker-2-");
      Process firstWorker = null;
      Process replacementWorker = null;
      try {
        firstWorker = startDispatchWorker(
            workerPoolCode, consumerGroupId, firstWorkerLog, "r25-attempt-1-" + suffix);
        awaitDispatchWorkerStarted(firstWorker, firstWorkerLog);
        awaitWorkerPoolOnline(workerPoolCode, "DISPATCH");

        launchService.launch(new LaunchRequest(
            TENANT,
            seed.workflowDefinitionId(),
            BIZ_DATE,
            TriggerType.API,
            seed.requestId(),
            "r25-smtp-crash-trace-" + suffix,
            params));
        outboxPublishSupport.publishAllPending(TENANT);

        final boolean acceptedMessage = smtpServer.awaitAcceptedMessage(Duration.ofSeconds(90));
        final List<String> smtpProtocolEvents = smtpServer.protocolEvents();
        assertThat(acceptedMessage)
            .as(
                "SMTP accepts DATA while dispatch remains CREATED; protocol events: %s",
                smtpProtocolEvents)
            .isTrue();
        final int acceptedMessageCount = smtpServer.acceptedMessageCount();
        assertThat(acceptedMessageCount).isEqualTo(1);
        final String dispatchStatusBeforeCrash = platformJdbc.queryForObject(
            selectDispatchStatusSql, String.class, TENANT, fileId, channelCode);
        assertThat(dispatchStatusBeforeCrash).isEqualTo("CREATED");

        stopProcess(firstWorker);
        firstWorker = null;
        smtpServer.releaseAfterAcceptance();
        Long taskId =
            platformJdbc.queryForObject(selectTaskIdSql, Long.class, TENANT, seed.dedupKey());
        Long partitionId =
            platformJdbc.queryForObject(selectPartitionIdSql, Long.class, TENANT, seed.dedupKey());
        JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
        platformJdbc.update(updatePartitionLeaseSql, TENANT, partitionId);
        partitionReclaimUnit.reclaim(partition);
        final String reportStatusAfterReclaim = reportStatus(seed);
        assertThat(reportStatusAfterReclaim).isEqualTo("READY");
        outboxPublishSupport.publishAllPending(TENANT);

        replacementWorker = startDispatchWorker(
            workerPoolCode, consumerGroupId, replacementWorkerLog, "r25-attempt-2-" + suffix);
        awaitDispatchWorkerStarted(replacementWorker, replacementWorkerLog);
        awaitWorkerPoolOnline(workerPoolCode, "DISPATCH");
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
          final String taskStatus =
              platformJdbc.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
          final String taskErrorCode =
              platformJdbc.queryForObject(selectTaskErrorCodeSql, String.class, TENANT, taskId);
          assertThat(taskStatus).isEqualTo("FAILED");
          assertThat(taskErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
        });

        final int finalAcceptedMessageCount = smtpServer.acceptedMessageCount();
        final Integer dispatchCount = platformJdbc.queryForObject(
            selectDispatchCountSql, Integer.class, TENANT, fileId, channelCode);
        final String dispatchStatus = platformJdbc.queryForObject(
            selectDispatchStatusSql, String.class, TENANT, fileId, channelCode);
        final String dispatchErrorCode = platformJdbc.queryForObject(
            selectDispatchErrorCodeSql, String.class, TENANT, fileId, channelCode);
        final Integer waitingRetryCount = platformJdbc.queryForObject(
            selectWaitingRetryCountSql, Integer.class, TENANT, partitionId);
        final String finalReportStatus = reportStatus(seed);
        assertThat(finalAcceptedMessageCount).isEqualTo(1);
        assertThat(dispatchCount).isEqualTo(1);
        assertThat(dispatchStatus).isEqualTo("FAILED");
        assertThat(dispatchErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
        assertThat(waitingRetryCount).isZero();
        assertThat(finalReportStatus).isEqualTo("FAILED");
      } finally {
        smtpServer.releaseAfterAcceptance();
        stopProcess(firstWorker);
        stopProcess(replacementWorker);
      }
    } finally {
      Files.deleteIfExists(sourceFile);
    }
  }

  @Test
  @DisplayName("ATOMIC HTTP 副作用已接受后 Worker 被杀,新进程重放由接收端幂等且旧报告被拒")
  void shouldDeduplicateAtomicHttpSideEffect_whenWorkerIsReclaimedAfterDelivery() throws Exception {
    final String insertResourceQueueSql = """
        insert into batch.resource_queue (
            tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
            max_running_partitions, max_qps, worker_group, resource_tag,
            priority_policy, fair_share_weight, enabled, quota_reset_policy,
            group_shared_max_running_jobs, burst_limit
        ) values (?, ?, 'R16 ATOMIC reclaim', 'MIXED', 0, 0, 0, 'ATOMIC', null,
                  'FIFO', 1, true, 'NONE', 0, 0)
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set queue_code = ? where tenant_id = ? and job_code = ?
    """;
    final String selectIdSql = """
        select p.id from batch.job_partition p join batch.job_instance ji on ji.id = p.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectCountSql = """
        select count(*)::int from batch.job_task where tenant_id = ? and job_instance_id = (select id\s\
        from batch.job_instance where tenant_id = ? and dedup_key = ?)\
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String receiverKey = "r16-atomic-" + suffix;
    AtomicInteger receivedRequests = new AtomicInteger();
    AtomicInteger receiverEffects = new AtomicInteger();
    Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/atomic", exchange -> {
      try {
        receivedRequests.incrementAndGet();
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (receiverKey.equals(idempotencyKey) && idempotencyKeys.add(idempotencyKey)) {
          receiverEffects.incrementAndGet();
        }
        byte[] body = "accepted".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
          output.write(body);
        }
      } finally {
        exchange.close();
      }
    });
    server.start();

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "ATOMIC", "atomic", TriggerType.API, BIZ_DATE);
    String workerPoolCode = "r16-atomic-" + suffix;
    String consumerGroupId = "batch-worker-atomic-r16-" + suffix;
    String queueCode = "r16-atomic-q-" + suffix;
    platformJdbc.update(insertResourceQueueSql, TENANT, queueCode);
    platformJdbc.update(updateJobDefinitionSql, queueCode, TENANT, seed.jobCode());
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "http");
    params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/atomic");
    params.put("method", "POST");
    params.put("headers", Map.of("Idempotency-Key", receiverKey));
    params.put("body", "r16-atomic-side-effect-" + suffix);
    params.put("expectStatus", 200);

    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> firstReport = new AtomicReference<>();
    AtomicReference<TaskExecutionReportCommand> replacementReport = new AtomicReference<>();
    AtomicReference<BizException> staleReportFailure = new AtomicReference<>();
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch replacementReportStarted = new CountDownLatch(1);
    CountDownLatch releaseReplacementReport = new CountDownLatch(1);
    CountDownLatch staleReportRejected = new CountDownLatch(1);
    doAnswer(invocation -> {
          TaskExecutionReportCommand report = invocation.getArgument(1);
          int attempt = reportAttempts.incrementAndGet();
          if (attempt == 1) {
            firstReport.set(report);
            firstReportStarted.countDown();
            if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException("timed out waiting to release stale ATOMIC REPORT");
            }
            try {
              return invocation.callRealMethod();
            } catch (BizException staleReport) {
              staleReportFailure.set(staleReport);
              staleReportRejected.countDown();
              throw staleReport;
            }
          }
          if (attempt == 2) {
            replacementReport.set(report);
            replacementReportStarted.countDown();
            if (!releaseReplacementReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException(
                  "timed out waiting to release replacement ATOMIC REPORT");
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));

    ensureKafkaTopicsExist(List.of(
        BatchTopics.TASK_DISPATCH_ATOMIC,
        BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_ATOMIC, workerPoolCode)));
    Path worker1Log = createWorkerLog("r16-atomic-worker-1-");
    Path worker2Log = createWorkerLog("r16-atomic-worker-2-");
    Process worker1 = null;
    Process worker2 = null;
    try {
      worker1 = startAtomicWorker(workerPoolCode, consumerGroupId, worker1Log, "attempt-1");
      awaitAtomicWorkerStarted(worker1, worker1Log);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r16-atomic-reclaim-" + suffix,
          params));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportStarted.await(300, TimeUnit.SECONDS))
          .as("ATOMIC HTTP receiver accepts the request before the first Worker reaches REPORT")
          .isTrue();
      TaskExecutionReportCommand i1Report = firstReport.get();
      assertThat(i1Report).isNotNull();
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");
      assertThat(receivedRequests).hasValue(1);
      assertThat(receiverEffects).hasValue(1);

      stopProcess(worker1);
      worker1 = null;
      Long partitionId =
          platformJdbc.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(updateJobPartitionSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      assertThat(reportStatus(seed)).isEqualTo("READY");
      outboxPublishSupport.publishAllPending(TENANT);

      worker2 = startAtomicWorker(workerPoolCode, consumerGroupId, worker2Log, "attempt-2");
      awaitAtomicWorkerStarted(worker2, worker2Log);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");
      assertThat(replacementReportStarted.await(300, TimeUnit.SECONDS))
          .as("replacement ATOMIC invocation repeats the same receiver request")
          .isTrue();
      TaskExecutionReportCommand i2Report = replacementReport.get();
      assertThat(i2Report).isNotNull();
      assertThat(i2Report.partitionInvocationId()).isNotEqualTo(i1Report.partitionInvocationId());
      assertThat(receivedRequests).hasValue(2);
      assertThat(receiverEffects).hasValue(1);
      assertThat(idempotencyKeys).containsExactly(receiverKey);

      releaseFirstReport.countDown();
      assertThat(staleReportRejected.await(30, TimeUnit.SECONDS))
          .as("the late I1 REPORT is rejected after reclaim")
          .isTrue();
      assertThat(staleReportFailure.get().getCode()).isEqualTo(ResultCode.FORBIDDEN);
      assertThat(staleReportFailure.get().getMessageKey()).isEqualTo("error.worker.not_owner");
      releaseReplacementReport.countDown();

      await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
        assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
        assertThat(reportOutboxCount(seed)).isZero();
        Integer selectedCount = platformJdbc.queryForObject(
            selectCountSql, Integer.class, TENANT, TENANT, seed.dedupKey());
        assertThat(selectedCount).isEqualTo(1);
      });
    } finally {
      releaseFirstReport.countDown();
      releaseReplacementReport.countDown();
      stopProcess(worker1);
      stopProcess(worker2);
      server.stop(0);
    }
  }

  @Test
  @DisplayName("ATOMIC 非幂等 HTTP 副作用后 Worker 失联,过期租约不得自动重放")
  void shouldStopAtomicReplayAfterNonIdempotentSideEffect_whenWorkerIsReclaimed() throws Exception {
    final String insertResourceQueueSql = """
        insert into batch.resource_queue (
            tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
            max_running_partitions, max_qps, worker_group, resource_tag,
            priority_policy, fair_share_weight, enabled, quota_reset_policy,
            group_shared_max_running_jobs, burst_limit
        ) values (?, ?, 'R25 ATOMIC unknown outcome', 'MIXED', 0, 0, 0, 'ATOMIC', null,
                  'FIFO', 1, true, 'NONE', 0, 0)
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set queue_code = ? where tenant_id = ? and job_code = ?
    """;
    final String selectNonIdempotentStartedAtSql = """
        select t.non_idempotent_started_at is not null from batch.job_task t\s\
        join batch.job_instance ji on ji.id = t.job_instance_id where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String selectIdSql = """
        select p.id from batch.job_partition p join batch.job_instance ji on ji.id = p.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectErrorCodeSql = """
        select error_code from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String selectFailureClassSql = """
        select t.failure_class from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String selectCountSql = """
        select count(*)::int from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'\s\
        and related_id = ?\
        """;
    final String selectDeadLetterOutcomeSql = """
        select count(*)::int as dead_letter_count,
               coalesce(max(max_replay_count), -1)::int as replay_budget,
               count(*) filter (where next_replay_at is not null)::int as scheduled_replay_count
        from batch.dead_letter_task
        where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    AtomicInteger receivedRequests = new AtomicInteger();
    AtomicInteger receiverEffects = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/atomic-unknown", exchange -> {
      try {
        receivedRequests.incrementAndGet();
        receiverEffects.incrementAndGet();
        byte[] body = "accepted".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
          output.write(body);
        }
      } finally {
        exchange.close();
      }
    });
    server.start();

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "ATOMIC", "atomic", TriggerType.API, BIZ_DATE);
    String workerPoolCode = "r25-atomic-unknown-" + suffix;
    String consumerGroupId = "batch-worker-atomic-r25-unknown-" + suffix;
    String queueCode = "r25-atomic-unknown-q-" + suffix;
    platformJdbc.update(insertResourceQueueSql, TENANT, queueCode);
    platformJdbc.update(updateJobDefinitionSql, queueCode, TENANT, seed.jobCode());
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("taskType", "http");
    params.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/atomic-unknown");
    params.put("method", "POST");
    params.put("body", "r25-atomic-unknown-side-effect-" + suffix);
    params.put("expectStatus", 200);

    AtomicInteger reportAttempts = new AtomicInteger();
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch firstReportCompleted = new CountDownLatch(1);
    doAnswer(invocation -> {
          if (reportAttempts.incrementAndGet() == 1) {
            firstReportStarted.countDown();
            try {
              if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release stale ATOMIC REPORT");
              }
              return invocation.callRealMethod();
            } finally {
              firstReportCompleted.countDown();
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));

    ensureKafkaTopicsExist(List.of(
        BatchTopics.TASK_DISPATCH_ATOMIC,
        BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_ATOMIC, workerPoolCode)));
    Path workerLog = createWorkerLog("r25-atomic-unknown-worker-");
    Process worker = null;
    try {
      worker = startAtomicWorker(workerPoolCode, consumerGroupId, workerLog, "attempt-1");
      awaitAtomicWorkerStarted(worker, workerLog);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r25-atomic-unknown-reclaim-" + suffix,
          params));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportStarted.await(300, TimeUnit.SECONDS))
          .as("the non-idempotent HTTP side effect completed before the Worker reached REPORT")
          .isTrue();
      assertThat(receivedRequests).hasValue(1);
      assertThat(receiverEffects).hasValue(1);
      Boolean selectedNonIdempotentStartedAt = platformJdbc.queryForObject(
          selectNonIdempotentStartedAtSql, Boolean.class, TENANT, seed.dedupKey());
      assertThat(selectedNonIdempotentStartedAt)
          .as("the Worker must durably mark non-idempotent execution before the HTTP side effect")
          .isTrue();
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");

      stopProcess(worker);
      worker = null;
      Long partitionId =
          platformJdbc.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(updateJobPartitionSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);

      assertThat(reportStatus(seed)).isEqualTo("FAILED");
      String selectedErrorCode =
          platformJdbc.queryForObject(selectErrorCodeSql, String.class, TENANT, seed.dedupKey());
      assertThat(selectedErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
      String selectedFailureClass =
          platformJdbc.queryForObject(selectFailureClassSql, String.class, TENANT, seed.dedupKey());
      assertThat(selectedFailureClass).isEqualTo("UNKNOWN");
      Integer selectedCount =
          platformJdbc.queryForObject(selectCountSql, Integer.class, TENANT, partitionId);
      assertThat(selectedCount).isZero();
      Map<String, Object> deadLetterOutcome =
          platformJdbc.queryForMap(selectDeadLetterOutcomeSql, TENANT, partitionId);
      assertThat(deadLetterOutcome)
          .containsEntry("dead_letter_count", 1)
          .containsEntry("replay_budget", 0)
          .containsEntry("scheduled_replay_count", 0);
      assertThat(receivedRequests).hasValue(1);
      assertThat(receiverEffects).hasValue(1);

      releaseFirstReport.countDown();
      assertThat(firstReportCompleted.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(reportStatus(seed)).isEqualTo("FAILED");
      assertThat(receivedRequests).hasValue(1);
    } finally {
      releaseFirstReport.countDown();
      stopProcess(worker);
      server.stop(0);
    }
  }

  @Test
  @DisplayName("ATOMIC Shell 真实脚本追加台账后丢失 REPORT，强杀回收与重复派发不重复副作用")
  void shouldStopAtomicShellReplay_whenWorkerCrashesAfterFilesystemSideEffect() throws Exception {
    assertAtomicFilesystemEffectIsNotReplayed("shell");
  }

  @Test
  @DisplayName("ATOMIC Spark 真实本地作业提交输出后丢失 REPORT，强杀回收与重复派发保留 UNKNOWN")
  void shouldStopAtomicSparkReplay_whenWorkerCrashesAfterSparkOutputCommit() throws Exception {
    assertAtomicFilesystemEffectIsNotReplayed("spark_submit");
  }

  private void assertAtomicFilesystemEffectIsNotReplayed(String taskType) throws Exception {
    final String insertQueueSql = """
        insert into batch.resource_queue (
            tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
            max_running_partitions, max_qps, worker_group, resource_tag,
            priority_policy, fair_share_weight, enabled, quota_reset_policy,
            group_shared_max_running_jobs, burst_limit
        ) values (?, ?, 'R25 atomic filesystem commit', 'MIXED', 0, 0, 0, 'ATOMIC', null,
                  'FIFO', 1, true, 'NONE', 0, 0)
        """;
    final String updateJobSql = """
        update batch.job_definition
        set queue_code = ?, retry_policy = 'FIXED', retry_max_count = 2
        where tenant_id = ? and job_code = ?
        """;
    final String selectTaskSql = """
        select task.id, task.job_partition_id, task.task_status, task.error_code,
               task.failure_class, task.non_idempotent_started_at
        from batch.job_task task
        join batch.job_instance instance on instance.tenant_id = task.tenant_id
            and instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    final String expireLeaseSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'
        where tenant_id = ? and id = ?
        """;
    final String selectRetryCountSql = """
        select count(*)::int from batch.retry_schedule
        where tenant_id = ? and related_type = 'JOB_PARTITION' and related_id = ?
        """;
    final String selectDeadLetterSql = """
        select count(*)::int as dead_letter_count,
               coalesce(max(max_replay_count), -1)::int as replay_budget,
               count(*) filter (where next_replay_at is not null)::int as scheduled_replay_count
        from batch.dead_letter_task
        where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
        """;
    final String selectDispatchSql = """
        select payload_json::text from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        order by id limit 1
        """;
    final String selectWorkflowStatusSql = """
        select run.run_status from batch.trigger_request request
        join batch.workflow_run run on run.tenant_id = request.tenant_id
            and run.trigger_request_id = request.id
        where request.tenant_id = ? and request.request_id = ?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String workerPoolCode = "r25-atomic-fs-" + suffix;
    String consumerGroupId = "batch-worker-atomic-r25-fs-" + suffix;
    String queueCode = "r25-atomic-fs-q-" + suffix;
    boolean sparkTask = "spark_submit".equals(taskType);
    Path effectDirectory = Files.createTempDirectory("r25-atomic-" + taskType + "-effect-");
    Path application = effectDirectory.resolve("side_effect.py");
    Path workerLogDirectory = projectRoot().resolve("logs/verification/workflow");
    Files.createDirectories(workerLogDirectory);
    Path firstWorkerLog = Files.createTempFile(
        workerLogDirectory, "r25-atomic-" + taskType + "-effect-worker-1-", ".log");
    Path replacementWorkerLog = Files.createTempFile(
        workerLogDirectory, "r25-atomic-" + taskType + "-effect-worker-2-", ".log");
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch firstReportCompleted = new CountDownLatch(1);
    CountDownLatch duplicateClaimCompleted = new CountDownLatch(1);
    AtomicReference<TaskExecutionReportCommand> firstReport = new AtomicReference<>();
    AtomicReference<Long> observedTaskId = new AtomicReference<>();
    AtomicReference<BizException> duplicateClaimFailure = new AtomicReference<>();
    doAnswer(invocation -> {
          TaskExecutionReportCommand report = invocation.getArgument(1);
          if (firstReport.compareAndSet(null, report)) {
            firstReportStarted.countDown();
            try {
              if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release filesystem REPORT");
              }
              return invocation.callRealMethod();
            } finally {
              firstReportCompleted.countDown();
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));
    doAnswer(invocation -> {
          if (observedTaskId.get() != null
              && observedTaskId.get().equals(invocation.getArgument(0))) {
            try {
              return invocation.callRealMethod();
            } catch (BizException unavailableTask) {
              duplicateClaimFailure.set(unavailableTask);
              throw unavailableTask;
            } finally {
              duplicateClaimCompleted.countDown();
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));
    Process firstWorker = null;
    Process replacementWorker = null;
    try {
      Map<String, Object> parameters = new LinkedHashMap<>();
      parameters.put("taskType", taskType);
      List<String> executorOptions;
      if (sparkTask) {
        String sparkHomeSetting = System.getProperty("r25.spark.home");
        assertThat(sparkHomeSetting)
            .as("r25.spark.home must identify an installed real Apache Spark distribution")
            .isNotBlank();
        Path sparkHome = Path.of(sparkHomeSetting).toAbsolutePath().normalize();
        Path sparkSubmit = sparkHome.resolve("bin/spark-submit");
        boolean sparkSubmitExecutable = Files.isExecutable(sparkSubmit);
        assertThat(sparkSubmitExecutable)
            .as("the real spark-submit binary is executable")
            .isTrue();
        String sparkApplication = """
            from pyspark.sql import SparkSession

            spark = SparkSession.builder.appName("r25-report-loss-side-effect").getOrCreate()
            try:
                output_path = spark.sparkContext.getConf().get("spark.batch.outputPath")
                spark.range(1).coalesce(1).write.mode("append").json(output_path)
            finally:
                spark.stop()
            """;
        Files.writeString(application, sparkApplication, StandardCharsets.UTF_8);
        Path outputDirectory = effectDirectory.resolve("spark-output");
        parameters.put("appResource", application.toString());
        parameters.put("master", "local[1]");
        parameters.put("deployMode", "client");
        parameters.put("outputPath", outputDirectory.toString());
        parameters.put("timeoutSeconds", 120);
        String sparkPython = System.getProperty("r25.spark.python", "/usr/bin/python3");
        boolean sparkPythonExecutable = Files.isExecutable(Path.of(sparkPython));
        assertThat(sparkPythonExecutable)
            .as("the Spark application Python is executable")
            .isTrue();
        parameters.put(
            "sparkConf",
            Map.of(
                "spark.ui.enabled",
                "false",
                "spark.driver.host",
                "127.0.0.1",
                "spark.driver.bindAddress",
                "127.0.0.1",
                "spark.pyspark.python",
                sparkPython,
                "spark.pyspark.driver.python",
                sparkPython,
                "spark.local.dir",
                effectDirectory.resolve("spark-local").toString(),
                "spark.sql.warehouse.dir",
                effectDirectory.resolve("warehouse").toUri().toString()));
        executorOptions = List.of(
            "--batch.worker.executors.spark-submit.enabled=true",
            "--batch.worker.executors.spark-submit.spark-submit-bin=" + sparkSubmit,
            "--batch.worker.executors.spark-submit.app-resource-allowlist[0]=" + effectDirectory
                + "/",
            "--batch.worker.executors.spark-submit.allowed-master-prefixes[0]=local[1]",
            "--batch.worker.executors.spark-submit.allowed-conf-key-prefixes[0]=spark.",
            "--batch.worker.executors.spark-submit.verify-local-success-marker=true");
      } else {
        String shellApplication = """
            import os
            import sys

            with open(sys.argv[1], "a", encoding="utf-8") as ledger:
                ledger.write("shell-effect\\n")
                ledger.flush()
                os.fsync(ledger.fileno())
            """;
        Files.writeString(application, shellApplication, StandardCharsets.UTF_8);
        Path ledger = effectDirectory.resolve("shell-ledger.txt");
        parameters.put("command", "/usr/bin/python3");
        parameters.put("args", List.of(application.toString(), ledger.toString()));
        executorOptions = List.of(
            "--batch.worker.executors.shell.enabled=true",
            "--batch.worker.executors.shell.command-whitelist=/usr/bin/python3",
            "--batch.worker.executors.shell.workdir-base="
                + effectDirectory.resolve("shell-workdirs"));
      }
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          platformJdbc, TENANT, "ATOMIC", "atomic", TriggerType.API, BIZ_DATE);
      platformJdbc.update(insertQueueSql, TENANT, queueCode);
      platformJdbc.update(updateJobSql, queueCode, TENANT, seed.jobCode());
      String dispatchTopic =
          BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_ATOMIC, workerPoolCode);
      ensureKafkaTopicsExist(List.of(BatchTopics.TASK_DISPATCH_ATOMIC, dispatchTopic));
      firstWorker = startAtomicWorker(
          workerPoolCode, consumerGroupId, firstWorkerLog, "attempt-1", executorOptions);
      awaitAtomicWorkerStarted(firstWorker, firstWorkerLog);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");
      LaunchRequest request = new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r25-atomic-fs-effect-" + suffix,
          parameters);
      launchService.launch(request);
      outboxPublishSupport.publishAllPending(TENANT);

      boolean completedBeforeReport = firstReportStarted.await(300, TimeUnit.SECONDS);
      assertThat(completedBeforeReport)
          .as("the real process completes before REPORT is accepted")
          .isTrue();
      TaskExecutionReportCommand successfulReport = firstReport.get();
      Path capturedReport =
          firstWorkerLog.resolveSibling(firstWorkerLog.getFileName() + ".report.json");
      String capturedReportJson =
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(successfulReport);
      Files.writeString(capturedReport, capturedReportJson, StandardCharsets.UTF_8);
      assertThat(successfulReport.success())
          .as("the real executor reports a successful effect: %s", successfulReport)
          .isTrue();
      List<String> expectedEffects = List.of(sparkTask ? "{\"id\":0}" : "shell-effect");
      List<String> effectsBeforeCrash = readAtomicFilesystemEffects(effectDirectory, sparkTask);
      assertThat(effectsBeforeCrash).containsExactlyElementsOf(expectedEffects);
      Map<String, Object> beforeCrash =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      assertThat(beforeCrash).containsEntry("task_status", "RUNNING");
      assertThat(beforeCrash.get("non_idempotent_started_at")).isNotNull();
      Long taskId = ((Number) beforeCrash.get("id")).longValue();
      Long partitionId = ((Number) beforeCrash.get("job_partition_id")).longValue();
      String dispatchPayload =
          platformJdbc.queryForObject(selectDispatchSql, String.class, TENANT, taskId);
      TaskDispatchMessage dispatch = JsonUtils.fromJson(dispatchPayload, TaskDispatchMessage.class);
      assertThat(dispatch).isNotNull();
      Set<String> originalConsumerIds = atomicConsumerIds(consumerGroupId);

      stopProcess(firstWorker);
      boolean firstWorkerStillAlive = firstWorker.isAlive();
      assertThat(firstWorkerStillAlive).isFalse();
      firstWorker = null;
      platformJdbc.update(expireLeaseSql, TENANT, partitionId);
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      Map<String, Object> reclaimedTask =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      assertThat(reclaimedTask)
          .containsEntry("task_status", "FAILED")
          .containsEntry("error_code", "EXTERNAL_OUTCOME_UNKNOWN")
          .containsEntry("failure_class", "UNKNOWN");
      Integer retryCount =
          platformJdbc.queryForObject(selectRetryCountSql, Integer.class, TENANT, partitionId);
      Map<String, Object> deadLetter =
          platformJdbc.queryForMap(selectDeadLetterSql, TENANT, partitionId);
      assertThat(retryCount).isZero();
      assertThat(deadLetter)
          .containsEntry("dead_letter_count", 1)
          .containsEntry("replay_budget", 0)
          .containsEntry("scheduled_replay_count", 0);
      releaseFirstReport.countDown();
      assertThat(firstReportCompleted.await(30, TimeUnit.SECONDS)).isTrue();
      outboxPublishSupport.publishAllPending(TENANT);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        String workflowStatus = platformJdbc.queryForObject(
            selectWorkflowStatusSql, String.class, TENANT, seed.requestId());
        assertThat(workflowStatus).isEqualTo("FAILED");
      });

      replacementWorker = startAtomicWorker(
          workerPoolCode, consumerGroupId, replacementWorkerLog, "attempt-1", executorOptions);
      awaitAtomicWorkerStarted(replacementWorker, replacementWorkerLog);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");
      observedTaskId.set(taskId);
      Path consumerGroupLog = replacementWorkerLog.resolveSibling(
          replacementWorkerLog.getFileName() + ".consumer-group.log");
      awaitAtomicConsumerReplacement(
          consumerGroupId, originalConsumerIds, dispatchTopic, consumerGroupLog);
      String dispatchKey =
          TENANT + ":" + dispatch.jobCode() + ":" + dispatch.instanceNo() + ":task:" + taskId;
      kafkaTemplate.send(dispatchTopic, dispatchKey, dispatchPayload).get();
      boolean duplicateClaimFinished = duplicateClaimCompleted.await(30, TimeUnit.SECONDS);
      assertThat(duplicateClaimFinished)
          .as("the replacement Worker attempts the original CLAIM")
          .isTrue();
      BizException claimFailure = duplicateClaimFailure.get();
      assertThat(claimFailure).isNotNull();
      assertThat(claimFailure.getCode()).isEqualTo(ResultCode.NOT_FOUND);
      assertThat(claimFailure.getMessageKey()).isEqualTo("error.task.claim_not_available");
      Map<String, Object> afterRedelivery =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      List<String> finalEffects = readAtomicFilesystemEffects(effectDirectory, sparkTask);
      String finalJobStatus = reportStatus(seed);
      assertThat(afterRedelivery)
          .containsEntry("task_status", "FAILED")
          .containsEntry("error_code", "EXTERNAL_OUTCOME_UNKNOWN")
          .containsEntry("failure_class", "UNKNOWN");
      assertThat(finalEffects).containsExactlyElementsOf(expectedEffects);
      assertThat(finalJobStatus).isEqualTo("FAILED");
    } finally {
      releaseFirstReport.countDown();
      stopProcess(firstWorker);
      stopProcess(replacementWorker);
      try (var paths = Files.walk(effectDirectory)) {
        List<Path> cleanupPaths = paths.sorted(Comparator.reverseOrder()).toList();
        for (Path cleanupPath : cleanupPaths) {
          Files.deleteIfExists(cleanupPath);
        }
      }
      boolean effectDirectoryStillExists = Files.exists(effectDirectory);
      assertThat(effectDirectoryStillExists)
          .as("only this test's filesystem outputs are removed")
          .isFalse();
    }
  }

  private List<String> readAtomicFilesystemEffects(Path effectDirectory, boolean sparkTask)
      throws IOException {
    if (!sparkTask) {
      Path ledger = effectDirectory.resolve("shell-ledger.txt");
      return Files.readAllLines(ledger, StandardCharsets.UTF_8);
    }
    Path outputDirectory = effectDirectory.resolve("spark-output");
    Path successMarker = outputDirectory.resolve("_SUCCESS");
    boolean outputCommitted = Files.isRegularFile(successMarker);
    assertThat(outputCommitted)
        .as("real Spark commits the local output with _SUCCESS")
        .isTrue();
    List<String> effectRows = new ArrayList<>();
    try (var outputFiles = Files.list(outputDirectory)) {
      List<Path> partFiles = outputFiles
          .filter(path -> {
            String filename = path.getFileName().toString();
            return filename.startsWith("part-") && filename.endsWith(".json");
          })
          .sorted()
          .toList();
      assertThat(partFiles).isNotEmpty();
      for (Path partFile : partFiles) {
        effectRows.addAll(Files.readAllLines(partFile, StandardCharsets.UTF_8));
      }
    }
    return effectRows;
  }

  @Test
  @DisplayName("ATOMIC Spark 第二个真实 action 运行且已有输出时仅 Worker JVM 崩溃，UNKNOWN 阻止再次提交")
  void shouldStopAtomicSparkReplay_whenWorkerJvmCrashesDuringActiveSparkAction() throws Exception {
    assertActiveSparkActionIsNotResubmittedAfterWorkerJvmCrash();
  }

  private void assertActiveSparkActionIsNotResubmittedAfterWorkerJvmCrash() throws Exception {
    final String insertQueueSql = """
        insert into batch.resource_queue (
            tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
            max_running_partitions, max_qps, worker_group, resource_tag,
            priority_policy, fair_share_weight, enabled, quota_reset_policy,
            group_shared_max_running_jobs, burst_limit
        ) values (?, ?, 'R25 active Spark action', 'MIXED', 0, 0, 0, 'ATOMIC', null,
                  'FIFO', 1, true, 'NONE', 0, 0)
        """;
    final String updateJobSql = """
        update batch.job_definition
        set queue_code = ?, retry_policy = 'FIXED', retry_max_count = 2
        where tenant_id = ? and job_code = ?
        """;
    final String selectTaskSql = """
        select task.id, task.job_partition_id, task.task_status, task.error_code,
               task.failure_class, task.non_idempotent_started_at
        from batch.job_task task
        join batch.job_instance instance on instance.tenant_id = task.tenant_id
            and instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    final String expireLeaseSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'
        where tenant_id = ? and id = ?
        """;
    final String selectRetryCountSql = """
        select count(*)::int from batch.retry_schedule
        where tenant_id = ? and related_type = 'JOB_PARTITION' and related_id = ?
        """;
    final String selectDeadLetterSql = """
        select count(*)::int as dead_letter_count,
               coalesce(max(max_replay_count), -1)::int as replay_budget,
               count(*) filter (where next_replay_at is not null)::int as scheduled_replay_count
        from batch.dead_letter_task
        where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
        """;
    final String selectDispatchSql = """
        select payload_json::text from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        order by id limit 1
        """;
    final String selectWorkflowStatusSql = """
        select run.run_status from batch.trigger_request request
        join batch.workflow_run run on run.tenant_id = request.tenant_id
            and run.trigger_request_id = request.id
        where request.tenant_id = ? and request.request_id = ?
        """;
    String sparkHomeSetting = System.getProperty("r25.spark.home");
    String sparkPython = System.getProperty("r25.spark.python");
    assertThat(sparkHomeSetting)
        .as("r25.spark.home identifies the real Spark runtime")
        .isNotBlank();
    assertThat(sparkPython)
        .as("r25.spark.python identifies the compatible Python runtime")
        .isNotBlank();
    Path sparkSubmit =
        Path.of(sparkHomeSetting).toAbsolutePath().normalize().resolve("bin/spark-submit");
    boolean sparkSubmitExecutable = Files.isExecutable(sparkSubmit);
    boolean sparkPythonExecutable = Files.isExecutable(Path.of(sparkPython));
    assertThat(sparkSubmitExecutable).isTrue();
    assertThat(sparkPythonExecutable).isTrue();
    Path effectDirectory = Files.createTempDirectory("r25-spark-active-action-");
    Path application = effectDirectory.resolve("active_action.py");
    Path driverMarker = effectDirectory.resolve("driver-starts.jsonl");
    Path taskMarker = effectDirectory.resolve("active-tasks.jsonl");
    Path releaseGate = effectDirectory.resolve("release-active-action");
    Path evidenceDirectory = projectRoot().resolve("logs/verification/workflow");
    Files.createDirectories(evidenceDirectory);
    Path firstWorkerLog =
        Files.createTempFile(evidenceDirectory, "r25-spark-active-worker-1-", ".log");
    Path replacementWorkerLog =
        Files.createTempFile(evidenceDirectory, "r25-spark-active-worker-2-", ".log");
    Path processEvidence =
        firstWorkerLog.resolveSibling(firstWorkerLog.getFileName() + ".processes.jsonl");
    String suffix = Long.toUnsignedString(System.nanoTime());
    String workerPoolCode = "r25-spark-active-" + suffix;
    String consumerGroupId = "batch-worker-r25-spark-active-" + suffix;
    String queueCode = "r25-spark-active-q-" + suffix;
    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> unexpectedReport = new AtomicReference<>();
    AtomicReference<Long> observedTaskId = new AtomicReference<>();
    AtomicReference<BizException> duplicateClaimFailure = new AtomicReference<>();
    CountDownLatch duplicateClaimCompleted = new CountDownLatch(1);
    Map<Long, ProcessHandle> ownedProcesses = new LinkedHashMap<>();
    Map<Long, String> ownedStartTimes = new LinkedHashMap<>();
    Map<Long, String> processRoles = new LinkedHashMap<>();
    doAnswer(invocation -> {
          reportAttempts.incrementAndGet();
          unexpectedReport.set(invocation.getArgument(1));
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));
    doAnswer(invocation -> {
          if (observedTaskId.get() != null
              && observedTaskId.get().equals(invocation.getArgument(0))) {
            try {
              return invocation.callRealMethod();
            } catch (BizException unavailableTask) {
              duplicateClaimFailure.set(unavailableTask);
              throw unavailableTask;
            } finally {
              duplicateClaimCompleted.countDown();
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));
    Process firstWorker = null;
    Process replacementWorker = null;
    String applicationId = "not-started";
    try {
      String sparkApplication = """
          import json
          import os
          import sys
          import time
          from pathlib import Path
          from pyspark.sql import SparkSession

          effect_root = Path(sys.argv[1])
          gate_path = str(effect_root / "release-active-action")
          task_marker_path = str(effect_root / "active-tasks.jsonl")

          def append_marker(path, marker):
              with open(path, "a", encoding="utf-8") as output:
                  output.write(json.dumps(marker, sort_keys=True) + "\\n")
                  output.flush()
                  os.fsync(output.fileno())

          def hold_active_partition(rows):
              from pyspark import TaskContext
              values = list(rows)
              if len(values) != 1:
                  raise RuntimeError("expected exactly one row in the active Spark task")
              context = TaskContext.get()
              append_marker(task_marker_path, {
                  "python_executor_pid": os.getpid(),
                  "parent_pid": os.getppid(),
                  "stage_id": context.stageId(),
                  "partition_id": context.partitionId(),
                  "task_attempt_id": context.taskAttemptId(),
                  "attempt_number": context.attemptNumber()
              })
              while not os.path.exists(gate_path):
                  time.sleep(0.1)

          spark = SparkSession.builder.appName("r25-active-action-crash").getOrCreate()
          try:
              append_marker(effect_root / "driver-starts.jsonl", {
                  "python_driver_pid": os.getpid(),
                  "spark_driver_pid": os.getppid(),
                  "application_id": spark.sparkContext.applicationId
              })
              output_path = spark.sparkContext.getConf().get("spark.batch.outputPath")
              spark.range(1).coalesce(1).write.mode("append").json(output_path)
              spark.range(1).coalesce(1).rdd.foreachPartition(hold_active_partition)
          finally:
              spark.stop()
          """;
      Files.writeString(application, sparkApplication, StandardCharsets.UTF_8);
      Map<String, Object> parameters = new LinkedHashMap<>();
      parameters.put("taskType", "spark_submit");
      parameters.put("appResource", application.toString());
      parameters.put("appArgs", List.of(effectDirectory.toString()));
      parameters.put("master", "local[1]");
      parameters.put("deployMode", "client");
      parameters.put("outputPath", effectDirectory.resolve("spark-output").toString());
      parameters.put("timeoutSeconds", 300);
      parameters.put(
          "sparkConf",
          Map.of(
              "spark.ui.enabled",
              "false",
              "spark.driver.host",
              "127.0.0.1",
              "spark.driver.bindAddress",
              "127.0.0.1",
              "spark.pyspark.python",
              sparkPython,
              "spark.pyspark.driver.python",
              sparkPython,
              "spark.local.dir",
              effectDirectory.resolve("spark-local").toString(),
              "spark.sql.warehouse.dir",
              effectDirectory.resolve("warehouse").toUri().toString()));
      List<String> executorOptions = List.of(
          "--batch.worker.executors.spark-submit.enabled=true",
          "--batch.worker.executors.spark-submit.spark-submit-bin=" + sparkSubmit,
          "--batch.worker.executors.spark-submit.app-resource-allowlist[0]=" + effectDirectory
              + "/",
          "--batch.worker.executors.spark-submit.allowed-master-prefixes[0]=local[1]",
          "--batch.worker.executors.spark-submit.allowed-conf-key-prefixes[0]=spark.",
          "--batch.worker.executors.spark-submit.verify-local-success-marker=true");
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          platformJdbc, TENANT, "ATOMIC", "atomic", TriggerType.API, BIZ_DATE);
      platformJdbc.update(insertQueueSql, TENANT, queueCode);
      platformJdbc.update(updateJobSql, queueCode, TENANT, seed.jobCode());
      String dispatchTopic =
          BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_ATOMIC, workerPoolCode);
      ensureKafkaTopicsExist(List.of(BatchTopics.TASK_DISPATCH_ATOMIC, dispatchTopic));
      firstWorker = startAtomicWorker(
          workerPoolCode, consumerGroupId, firstWorkerLog, "attempt-1", executorOptions);
      awaitAtomicWorkerStarted(firstWorker, firstWorkerLog);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");
      ProcessHandle workerHandle = firstWorker.toHandle();
      captureActiveSparkOwnedProcesses(workerHandle, ownedProcesses, ownedStartTimes);
      LaunchRequest request = new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r25-spark-active-action-" + suffix,
          parameters);
      launchService.launch(request);
      outboxPublishSupport.publishAllPending(TENANT);
      await()
          .atMost(Duration.ofSeconds(180))
          .pollInterval(Duration.ofMillis(100))
          .until(() -> {
            captureActiveSparkOwnedProcesses(workerHandle, ownedProcesses, ownedStartTimes);
            boolean activeMarkersComplete =
                activeSparkMarkerComplete(driverMarker) && activeSparkMarkerComplete(taskMarker);
            return activeMarkersComplete || reportAttempts.get() > 0;
          });
      Path capturedReport =
          firstWorkerLog.resolveSibling(firstWorkerLog.getFileName() + ".report.json");
      String capturedReportJson = objectMapper.writeValueAsString(unexpectedReport.get());
      Files.writeString(capturedReport, capturedReportJson, StandardCharsets.UTF_8);
      assertThat(reportAttempts)
          .as("the second real Spark action has not returned to REPORT")
          .hasValue(0);
      Map<String, Object> driver = readActiveSparkMarker(driverMarker);
      Map<String, Object> activeTask = readActiveSparkMarker(taskMarker);
      applicationId = String.valueOf(driver.get("application_id"));
      assertThat(applicationId).isNotBlank().isNotEqualTo("null");
      Long sparkDriverPid = ((Number) driver.get("spark_driver_pid")).longValue();
      Long pythonDriverPid = ((Number) driver.get("python_driver_pid")).longValue();
      Long pythonExecutorPid = ((Number) activeTask.get("python_executor_pid")).longValue();
      captureActiveSparkOwnedProcesses(workerHandle, ownedProcesses, ownedStartTimes);
      processRoles.put(workerHandle.pid(), "atomic-worker-jvm");
      processRoles.put(sparkDriverPid, "spark-driver-jvm");
      processRoles.put(pythonDriverPid, "spark-python-driver");
      processRoles.put(pythonExecutorPid, "spark-python-executor");
      assertThat(ownedProcesses.keySet())
          .contains(sparkDriverPid, pythonDriverPid, pythonExecutorPid);
      assertThat(sparkDriverPid).isNotEqualTo(pythonDriverPid).isNotEqualTo(pythonExecutorPid);
      assertThat(pythonDriverPid).isNotEqualTo(pythonExecutorPid);
      for (Long pid : List.of(sparkDriverPid, pythonDriverPid, pythonExecutorPid)) {
        ProcessHandle ownedProcess = ownedProcesses.get(pid);
        boolean processAlive = ownedProcess.isAlive();
        assertThat(processAlive)
            .as("Spark process %s is alive under the original Worker", pid)
            .isTrue();
        assertThat(ownedStartTimes.get(pid)).isNotBlank().isNotEqualTo("unknown");
      }
      String sparkDriverCommand =
          ownedProcesses.get(sparkDriverPid).info().command().orElse("");
      assertThat(sparkDriverCommand).endsWith("/java");
      assertThat(activeTask).containsEntry("partition_id", 0).containsEntry("attempt_number", 0);
      Number activeStage = (Number) activeTask.get("stage_id");
      assertThat(activeStage.intValue()).isGreaterThanOrEqualTo(0);
      boolean gateReleased = Files.exists(releaseGate);
      assertThat(gateReleased).isFalse();
      List<String> effectsBeforeCrash = readAtomicFilesystemEffects(effectDirectory, true);
      List<String> driverStartsBeforeCrash =
          Files.readAllLines(driverMarker, StandardCharsets.UTF_8);
      List<String> activeTasksBeforeCrash = Files.readAllLines(taskMarker, StandardCharsets.UTF_8);
      assertThat(effectsBeforeCrash).containsExactly("{\"id\":0}");
      assertThat(driverStartsBeforeCrash).hasSize(1);
      assertThat(activeTasksBeforeCrash).hasSize(1);
      Map<String, Object> beforeCrash =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      assertThat(beforeCrash).containsEntry("task_status", "RUNNING");
      assertThat(beforeCrash.get("non_idempotent_started_at")).isNotNull();
      Long taskId = ((Number) beforeCrash.get("id")).longValue();
      Long partitionId = ((Number) beforeCrash.get("job_partition_id")).longValue();
      String dispatchPayload =
          platformJdbc.queryForObject(selectDispatchSql, String.class, TENANT, taskId);
      TaskDispatchMessage dispatch = JsonUtils.fromJson(dispatchPayload, TaskDispatchMessage.class);
      assertThat(dispatch).isNotNull();
      Set<String> originalConsumerIds = atomicConsumerIds(consumerGroupId);
      recordActiveSparkProcessEvidence(
          processEvidence,
          "before-worker-jvm-crash",
          applicationId,
          ownedProcesses,
          ownedStartTimes,
          processRoles);

      // 只注入 Worker JVM 崩溃；Spark 子进程状态仅观察，直到 finally 才清理。
      boolean killRequested = firstWorker.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      assertThat(killRequested)
          .as("the original Worker JVM exited after the crash injection")
          .isTrue();
      boolean workerStillAlive = workerHandle.isAlive();
      assertThat(workerStillAlive).isFalse();
      firstWorker = null;
      recordActiveSparkProcessEvidence(
          processEvidence,
          "after-worker-jvm-crash",
          applicationId,
          ownedProcesses,
          ownedStartTimes,
          processRoles);
      assertThat(reportAttempts).hasValue(0);
      platformJdbc.update(expireLeaseSql, TENANT, partitionId);
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      Map<String, Object> reclaimedTask =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      assertThat(reclaimedTask)
          .containsEntry("task_status", "FAILED")
          .containsEntry("error_code", "EXTERNAL_OUTCOME_UNKNOWN")
          .containsEntry("failure_class", "UNKNOWN");
      Integer retryCount =
          platformJdbc.queryForObject(selectRetryCountSql, Integer.class, TENANT, partitionId);
      Map<String, Object> deadLetter =
          platformJdbc.queryForMap(selectDeadLetterSql, TENANT, partitionId);
      assertThat(retryCount).isZero();
      assertThat(deadLetter)
          .containsEntry("dead_letter_count", 1)
          .containsEntry("replay_budget", 0)
          .containsEntry("scheduled_replay_count", 0);
      outboxPublishSupport.publishAllPending(TENANT);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        String workflowStatus = platformJdbc.queryForObject(
            selectWorkflowStatusSql, String.class, TENANT, seed.requestId());
        assertThat(workflowStatus).isEqualTo("FAILED");
      });
      observedTaskId.set(taskId);
      replacementWorker = startAtomicWorker(
          workerPoolCode, consumerGroupId, replacementWorkerLog, "attempt-1", executorOptions);
      awaitAtomicWorkerStarted(replacementWorker, replacementWorkerLog);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");
      Path consumerGroupLog = replacementWorkerLog.resolveSibling(
          replacementWorkerLog.getFileName() + ".consumer-group.log");
      awaitAtomicConsumerReplacement(
          consumerGroupId, originalConsumerIds, dispatchTopic, consumerGroupLog);
      String dispatchKey =
          TENANT + ":" + dispatch.jobCode() + ":" + dispatch.instanceNo() + ":task:" + taskId;
      kafkaTemplate.send(dispatchTopic, dispatchKey, dispatchPayload).get();
      boolean duplicateClaimFinished = duplicateClaimCompleted.await(30, TimeUnit.SECONDS);
      assertThat(duplicateClaimFinished).isTrue();
      BizException claimFailure = duplicateClaimFailure.get();
      assertThat(claimFailure).isNotNull();
      assertThat(claimFailure.getCode()).isEqualTo(ResultCode.NOT_FOUND);
      assertThat(claimFailure.getMessageKey()).isEqualTo("error.task.claim_not_available");
      List<String> finalEffects = readAtomicFilesystemEffects(effectDirectory, true);
      List<String> finalDriverStarts = Files.readAllLines(driverMarker, StandardCharsets.UTF_8);
      List<String> finalActiveTasks = Files.readAllLines(taskMarker, StandardCharsets.UTF_8);
      Map<String, Object> afterRedelivery =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      String finalJobStatus = reportStatus(seed);
      assertThat(finalEffects).containsExactly("{\"id\":0}");
      assertThat(finalDriverStarts).containsExactlyElementsOf(driverStartsBeforeCrash);
      assertThat(finalActiveTasks).containsExactlyElementsOf(activeTasksBeforeCrash);
      assertThat(reportAttempts).hasValue(0);
      assertThat(afterRedelivery)
          .containsEntry("task_status", "FAILED")
          .containsEntry("error_code", "EXTERNAL_OUTCOME_UNKNOWN")
          .containsEntry("failure_class", "UNKNOWN");
      assertThat(finalJobStatus).isEqualTo("FAILED");
      recordActiveSparkProcessEvidence(
          processEvidence,
          "after-replacement-claim-rejected",
          applicationId,
          ownedProcesses,
          ownedStartTimes,
          processRoles);
    } finally {
      if (firstWorker != null) {
        captureActiveSparkOwnedProcesses(firstWorker.toHandle(), ownedProcesses, ownedStartTimes);
        firstWorker.destroyForcibly();
      }
      Files.writeString(releaseGate, "test-cleanup", StandardCharsets.UTF_8);
      stopProcess(replacementWorker);
      for (ProcessHandle ownedProcess : ownedProcesses.values()) {
        String capturedStartTime = ownedStartTimes.get(ownedProcess.pid());
        String currentStartTime =
            ownedProcess.info().startInstant().map(Object::toString).orElse("unknown");
        boolean identityStillMatches =
            !"unknown".equals(capturedStartTime) && capturedStartTime.equals(currentStartTime);
        if (ownedProcess.isAlive() && identityStillMatches) {
          ownedProcess.destroyForcibly();
        }
      }
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        for (ProcessHandle ownedProcess : ownedProcesses.values()) {
          boolean processAlive = ownedProcess.isAlive();
          assertThat(processAlive)
              .as("owned Spark process %s stopped during cleanup", ownedProcess.pid())
              .isFalse();
        }
      });
      recordActiveSparkProcessEvidence(
          processEvidence,
          "after-test-owned-process-cleanup",
          applicationId,
          ownedProcesses,
          ownedStartTimes,
          processRoles);
      try (var paths = Files.walk(effectDirectory)) {
        List<Path> cleanupPaths = paths.sorted(Comparator.reverseOrder()).toList();
        for (Path cleanupPath : cleanupPaths) {
          Files.deleteIfExists(cleanupPath);
        }
      }
      boolean effectDirectoryStillExists = Files.exists(effectDirectory);
      assertThat(effectDirectoryStillExists).isFalse();
    }
  }

  private boolean activeSparkMarkerComplete(Path marker) throws IOException {
    if (!Files.isRegularFile(marker)) {
      return false;
    }
    String markerContent = Files.readString(marker, StandardCharsets.UTF_8);
    return !markerContent.isBlank() && markerContent.endsWith("\n");
  }

  private Map<String, Object> readActiveSparkMarker(Path marker) throws IOException {
    List<String> markerRows = Files.readAllLines(marker, StandardCharsets.UTF_8);
    assertThat(markerRows).hasSize(1);
    String markerJson = markerRows.getFirst();
    return objectMapper.readValue(markerJson, new TypeReference<Map<String, Object>>() {});
  }

  private void captureActiveSparkOwnedProcesses(
      ProcessHandle workerHandle,
      Map<Long, ProcessHandle> ownedProcesses,
      Map<Long, String> ownedStartTimes) {
    List<ProcessHandle> currentProcesses = new ArrayList<>();
    currentProcesses.add(workerHandle);
    try (var descendants = workerHandle.descendants()) {
      currentProcesses.addAll(descendants.toList());
    }
    for (ProcessHandle process : currentProcesses) {
      String startedAt = process.info().startInstant().map(Object::toString).orElse("unknown");
      String previousStartTime = ownedStartTimes.get(process.pid());
      boolean firstObservation = previousStartTime == null;
      boolean startTimeNowAvailable =
          "unknown".equals(previousStartTime) && !"unknown".equals(startedAt);
      if (firstObservation || startTimeNowAvailable) {
        ownedProcesses.put(process.pid(), process);
        ownedStartTimes.put(process.pid(), startedAt);
      }
    }
  }

  private void recordActiveSparkProcessEvidence(
      Path evidenceLog,
      String phase,
      String applicationId,
      Map<Long, ProcessHandle> ownedProcesses,
      Map<Long, String> ownedStartTimes,
      Map<Long, String> processRoles)
      throws IOException {
    List<Map<String, Object>> processStates = new ArrayList<>();
    for (Map.Entry<Long, ProcessHandle> owned : ownedProcesses.entrySet()) {
      Long pid = owned.getKey();
      Map<String, Object> processState = new LinkedHashMap<>();
      processState.put("pid", pid);
      processState.put("startedAt", ownedStartTimes.get(pid));
      processState.put("role", processRoles.getOrDefault(pid, "owned-descendant"));
      processState.put("alive", owned.getValue().isAlive());
      processStates.add(processState);
    }
    Map<String, Object> evidence =
        Map.of("phase", phase, "applicationId", applicationId, "processes", processStates);
    String evidenceJson = objectMapper.writeValueAsString(evidence);
    Files.writeString(
        evidenceLog,
        evidenceJson + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  @Test
  @DisplayName("ATOMIC SQL 提交后丢失 REPORT，强杀和租约回收保留 UNKNOWN 且不重复写入")
  void shouldStopAtomicSqlReplay_whenWorkerCrashesAfterDatabaseCommit() throws Exception {
    assertAtomicDatabaseCommitIsNotReplayed("sql");
  }

  @Test
  @DisplayName("ATOMIC 存储过程提交后丢失 REPORT，强杀和租约回收保留 UNKNOWN 且不重复调用")
  void shouldStopAtomicStoredProcReplay_whenWorkerCrashesAfterDatabaseCommit() throws Exception {
    assertAtomicDatabaseCommitIsNotReplayed("stored_proc");
  }

  private void assertAtomicDatabaseCommitIsNotReplayed(String taskType) throws Exception {
    final String insertQueueSql = """
        insert into batch.resource_queue (
            tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
            max_running_partitions, max_qps, worker_group, resource_tag,
            priority_policy, fair_share_weight, enabled, quota_reset_policy,
            group_shared_max_running_jobs, burst_limit
        ) values (?, ?, 'R25 atomic database commit', 'MIXED', 0, 0, 0, 'ATOMIC', null,
                  'FIFO', 1, true, 'NONE', 0, 0)
        """;
    final String updateJobSql = """
        update batch.job_definition
        set queue_code = ?, retry_policy = 'FIXED', retry_max_count = 2
        where tenant_id = ? and job_code = ?
        """;
    final String selectTaskSql = """
        select task.id, task.job_partition_id, task.task_status, task.error_code,
               task.failure_class, task.non_idempotent_started_at
        from batch.job_task task
        join batch.job_instance instance on instance.tenant_id = task.tenant_id
            and instance.id = task.job_instance_id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    final String expireLeaseSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'
        where tenant_id = ? and id = ?
        """;
    final String selectRetryCountSql = """
        select count(*)::int from batch.retry_schedule
        where tenant_id = ? and related_type = 'JOB_PARTITION' and related_id = ?
        """;
    final String selectDeadLetterSql = """
        select count(*)::int as dead_letter_count,
               coalesce(max(max_replay_count), -1)::int as replay_budget,
               count(*) filter (where next_replay_at is not null)::int as scheduled_replay_count
        from batch.dead_letter_task
        where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
        """;
    final String selectDispatchSql = """
        select payload_json::text from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        order by id limit 1
        """;
    final String selectWorkflowStatusSql = """
        select run.run_status from batch.trigger_request request
        join batch.workflow_run run on run.tenant_id = request.tenant_id
            and run.trigger_request_id = request.id
        where request.tenant_id = ? and request.request_id = ?
        """;
    final String selectTableAbsentSql = "select to_regclass(?) is null";
    final String selectProcedureAbsentSql = "select to_regprocedure(?) is null";

    String suffix = Long.toUnsignedString(System.nanoTime());
    String effectTable = "batch.r25_atomic_effect_" + suffix;
    String effectProcedure = "batch.r25_atomic_commit_" + suffix;
    String createTableSql = """
        create table %s (execution_id bigint generated always as identity primary key)
        """.formatted(effectTable);
    String databaseMutationSql = "insert into %s default values".formatted(effectTable);
    String createProcedureSql = """
        create procedure %s() language plpgsql security invoker as $r25$
        begin
          insert into %s default values;
        end;
        $r25$
        """.formatted(effectProcedure, effectTable);
    String selectBusinessCountSql = "select count(*)::int from %s".formatted(effectTable);
    String dropProcedureSql = "drop procedure if exists %s()".formatted(effectProcedure);
    String dropTableSql = "drop table if exists %s".formatted(effectTable);
    String workerPoolCode = "r25-atomic-db-" + suffix;
    String consumerGroupId = "batch-worker-atomic-r25-db-" + suffix;
    String queueCode = "r25-atomic-db-q-" + suffix;
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch firstReportCompleted = new CountDownLatch(1);
    AtomicReference<TaskExecutionReportCommand> firstReport = new AtomicReference<>();
    AtomicReference<Long> observedTaskId = new AtomicReference<>();
    AtomicReference<BizException> duplicateClaimFailure = new AtomicReference<>();
    CountDownLatch duplicateClaimCompleted = new CountDownLatch(1);
    doAnswer(invocation -> {
          TaskExecutionReportCommand report = invocation.getArgument(1);
          if (firstReport.compareAndSet(null, report)) {
            firstReportStarted.countDown();
            try {
              if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release database REPORT");
              }
              return invocation.callRealMethod();
            } finally {
              firstReportCompleted.countDown();
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));
    doAnswer(invocation -> {
          if (observedTaskId.get() != null
              && observedTaskId.get().equals(invocation.getArgument(0))) {
            try {
              return invocation.callRealMethod();
            } catch (BizException unavailableTask) {
              duplicateClaimFailure.set(unavailableTask);
              throw unavailableTask;
            } finally {
              duplicateClaimCompleted.countDown();
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));

    Path workerLogDirectory = projectRoot().resolve("logs/verification/workflow");
    Files.createDirectories(workerLogDirectory);
    Path firstWorkerLog = Files.createTempFile(
        workerLogDirectory, "r25-atomic-" + taskType + "-commit-worker-1-", ".log");
    Path replacementWorkerLog = Files.createTempFile(
        workerLogDirectory, "r25-atomic-" + taskType + "-commit-worker-2-", ".log");
    Process firstWorker = null;
    Process replacementWorker = null;
    platformJdbc.execute(createTableSql);
    try {
      Map<String, Object> parameters = new LinkedHashMap<>();
      parameters.put("taskType", taskType);
      if ("stored_proc".equals(taskType)) {
        platformJdbc.execute(createProcedureSql);
        parameters.put("procedureName", effectProcedure);
      } else {
        parameters.put("sql", databaseMutationSql);
      }
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          platformJdbc, TENANT, "ATOMIC", "atomic", TriggerType.API, BIZ_DATE);
      platformJdbc.update(insertQueueSql, TENANT, queueCode);
      platformJdbc.update(updateJobSql, queueCode, TENANT, seed.jobCode());
      ensureKafkaTopicsExist(List.of(
          BatchTopics.TASK_DISPATCH_ATOMIC,
          BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_ATOMIC, workerPoolCode)));
      // 仅为隔离 Testcontainers 的 superuser 连接关闭执行器角色闸，不改共享运行配置。
      List<String> databaseExecutorOptions = List.of(
          "--batch.worker.executors.sql.forbid-os-capable-role=false",
          "--batch.worker.executors.stored-proc.forbid-os-capable-role=false");
      firstWorker = startAtomicWorker(
          workerPoolCode, consumerGroupId, firstWorkerLog, "attempt-1", databaseExecutorOptions);
      awaitAtomicWorkerStarted(firstWorker, firstWorkerLog);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r25-atomic-db-commit-" + suffix,
          parameters));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportStarted.await(300, TimeUnit.SECONDS)).isTrue();
      assertThat(firstReport.get().success())
          .as("the database executor committed successfully")
          .isTrue();
      Integer committedBusinessRows =
          platformJdbc.queryForObject(selectBusinessCountSql, Integer.class);
      assertThat(committedBusinessRows)
          .as("a separate connection observes the committed effect")
          .isEqualTo(1);
      Map<String, Object> beforeCrash =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      assertThat(beforeCrash).containsEntry("task_status", "RUNNING");
      assertThat(beforeCrash.get("non_idempotent_started_at")).isNotNull();
      Long taskId = ((Number) beforeCrash.get("id")).longValue();
      Long partitionId = ((Number) beforeCrash.get("job_partition_id")).longValue();
      String dispatchPayload =
          platformJdbc.queryForObject(selectDispatchSql, String.class, TENANT, taskId);
      TaskDispatchMessage dispatch = JsonUtils.fromJson(dispatchPayload, TaskDispatchMessage.class);
      assertThat(dispatch).isNotNull();
      Set<String> originalConsumerIds = atomicConsumerIds(consumerGroupId);

      stopProcess(firstWorker);
      assertThat(firstWorker.isAlive()).isFalse();
      firstWorker = null;
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(expireLeaseSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      Map<String, Object> reclaimedTask =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      assertThat(reclaimedTask)
          .containsEntry("task_status", "FAILED")
          .containsEntry("error_code", "EXTERNAL_OUTCOME_UNKNOWN")
          .containsEntry("failure_class", "UNKNOWN");
      Integer retryCount =
          platformJdbc.queryForObject(selectRetryCountSql, Integer.class, TENANT, partitionId);
      Map<String, Object> deadLetter =
          platformJdbc.queryForMap(selectDeadLetterSql, TENANT, partitionId);
      assertThat(retryCount).isZero();
      assertThat(deadLetter)
          .containsEntry("dead_letter_count", 1)
          .containsEntry("replay_budget", 0)
          .containsEntry("scheduled_replay_count", 0);
      releaseFirstReport.countDown();
      assertThat(firstReportCompleted.await(30, TimeUnit.SECONDS)).isTrue();
      outboxPublishSupport.publishAllPending(TENANT);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        String workflowStatus = platformJdbc.queryForObject(
            selectWorkflowStatusSql, String.class, TENANT, seed.requestId());
        assertThat(workflowStatus).isEqualTo("FAILED");
      });

      replacementWorker = startAtomicWorker(
          workerPoolCode,
          consumerGroupId,
          replacementWorkerLog,
          "attempt-1",
          databaseExecutorOptions);
      awaitAtomicWorkerStarted(replacementWorker, replacementWorkerLog);
      awaitWorkerPoolOnline(workerPoolCode, "ATOMIC");
      observedTaskId.set(taskId);
      // Kafka 订阅以稳定池代码路由，消息内的运行实例身份继续保持原值。
      String dispatchTopic =
          BatchTopics.directDispatchTopic(BatchTopics.TASK_DISPATCH_ATOMIC, workerPoolCode);
      Path consumerGroupLog = replacementWorkerLog.resolveSibling(
          replacementWorkerLog.getFileName() + ".consumer-group.log");
      awaitAtomicConsumerReplacement(
          consumerGroupId, originalConsumerIds, dispatchTopic, consumerGroupLog);
      String dispatchKey =
          TENANT + ":" + dispatch.jobCode() + ":" + dispatch.instanceNo() + ":task:" + taskId;
      kafkaTemplate.send(dispatchTopic, dispatchKey, dispatchPayload).get();
      assertThat(duplicateClaimCompleted.await(30, TimeUnit.SECONDS))
          .as("the restarted logical Worker consumes the original targeted message")
          .isTrue();
      assertThat(duplicateClaimFailure.get()).isNotNull();
      assertThat(duplicateClaimFailure.get().getCode()).isEqualTo(ResultCode.NOT_FOUND);
      assertThat(duplicateClaimFailure.get().getMessageKey())
          .isEqualTo("error.task.claim_not_available");
      Map<String, Object> afterRedelivery =
          platformJdbc.queryForMap(selectTaskSql, TENANT, seed.dedupKey());
      Integer finalBusinessRows =
          platformJdbc.queryForObject(selectBusinessCountSql, Integer.class);
      assertThat(afterRedelivery)
          .containsEntry("task_status", "FAILED")
          .containsEntry("error_code", "EXTERNAL_OUTCOME_UNKNOWN")
          .containsEntry("failure_class", "UNKNOWN");
      assertThat(finalBusinessRows).isEqualTo(1);
      String finalJobStatus = reportStatus(seed);
      assertThat(finalJobStatus).isEqualTo("FAILED");
    } finally {
      releaseFirstReport.countDown();
      stopProcess(firstWorker);
      stopProcess(replacementWorker);
      platformJdbc.execute(dropProcedureSql);
      platformJdbc.execute(dropTableSql);
      Boolean tableAbsent =
          platformJdbc.queryForObject(selectTableAbsentSql, Boolean.class, effectTable);
      Boolean procedureAbsent = platformJdbc.queryForObject(
          selectProcedureAbsentSql, Boolean.class, effectProcedure + "()");
      assertThat(tableAbsent).isTrue();
      assertThat(procedureAbsent).isTrue();
    }
  }

  private Set<String> atomicConsumerIds(String consumerGroupId) throws Exception {
    Map<String, Object> adminProperties =
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
    try (AdminClient admin = AdminClient.create(adminProperties)) {
      ConsumerGroupDescription group = admin
          .describeConsumerGroups(List.of(consumerGroupId))
          .all()
          .get(10, TimeUnit.SECONDS)
          .get(consumerGroupId);
      Set<String> consumerIds = new LinkedHashSet<>();
      group.members().forEach(member -> consumerIds.add(member.consumerId()));
      assertThat(consumerIds).isNotEmpty();
      return consumerIds;
    }
  }

  private void awaitAtomicConsumerReplacement(
      String consumerGroupId,
      Set<String> originalConsumerIds,
      String dispatchTopic,
      Path evidenceLog)
      throws Exception {
    Map<String, Object> adminProperties =
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
    try (AdminClient admin = AdminClient.create(adminProperties)) {
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofSeconds(1))
          .untilAsserted(() -> {
            ConsumerGroupDescription group;
            try {
              group = admin
                  .describeConsumerGroups(List.of(consumerGroupId))
                  .all()
                  .get(10, TimeUnit.SECONDS)
                  .get(consumerGroupId);
            } catch (ExecutionException exception) {
              if (exception.getCause() instanceof GroupIdNotFoundException) {
                throw new AssertionError("replacement Worker has not joined its group", exception);
              }
              throw exception;
            }
            Files.writeString(
                evidenceLog,
                group + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            Set<String> consumerIds = new LinkedHashSet<>();
            Set<String> assignedTopics = new LinkedHashSet<>();
            group.members().forEach(member -> {
              consumerIds.add(member.consumerId());
              member
                  .assignment()
                  .topicPartitions()
                  .forEach(partition -> assignedTopics.add(partition.topic()));
            });
            assertThat(group.groupState()).isEqualTo(GroupState.STABLE);
            assertThat(consumerIds).isNotEmpty().doesNotContainAnyElementsOf(originalConsumerIds);
            assertThat(assignedTopics).contains(dispatchTopic);
          });
    }
  }

  @Test
  @DisplayName("真实 Export Worker reclaim 与重复派发复用同一对象和 file_record，旧报告被拒")
  void shouldReuseExportArtifactAfterReclaimOnReplacementWorker_withoutDuplicateFileRecord()
      throws Exception {
    final String insertSettlementBatchSql = """
        insert into biz.settlement_batch (
            tenant_id, batch_no, biz_date, accounting_period, batch_status,
            total_record_count, total_amount, currency
        ) values (?, ?, date '2026-01-15', '202601', 'READY', 1, 0, 'CNY')
        returning id
        """;
    final String insertSettlementDetailSql = """
        insert into biz.settlement_detail (
            tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
            gross_amount, fee_amount, net_amount, currency, settlement_status
        ) values (?, ?, ?, ?, date '2026-01-15', '202601', 10.00, 1.00, 9.00, 'CNY', 'READY')
        """;
    final String selectIdSql = """
            select p.id from batch.job_partition p join batch.job_instance ji on ji.id = p.job_instance_id\s\
            where ji.tenant_id = ? and ji.dedup_key = ?\
            """;
    final String updateJobPartitionSql = """
            update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
            where tenant_id = ? and id = ?\
            """;
    final String selectPayloadJsonSql = """
            select payload_json ->> 'selectedWorkerId' from batch.outbox_event where tenant_id = ?\s\
            and event_key = ?\
            """;
    final String selectCountSql = """
            select count(*)::int from batch.file_record where tenant_id = ? and source_ref = ?\s\
            and file_status = 'GENERATED'\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.job_task where tenant_id = ? and job_instance_id = (select id\s\
            from batch.job_instance where tenant_id = ? and dedup_key = ?)\
            """;
    final String selectIdSql2 = """
            select id from batch.job_task where tenant_id = ? and job_instance_id = (select id\s\
            from batch.job_instance where tenant_id = ? and dedup_key = ?)\
            """;
    final String selectPayloadJsonSql2 = """
            select payload_json::text from batch.outbox_event where tenant_id = ?\s\
            and aggregate_type = 'JOB_TASK' and aggregate_id = ? and event_key = ?\
            """;
    final String selectCountSql3 = """
            select count(*)::int from batch.file_record where tenant_id = ? and source_ref = ?\s\
            and file_status = 'GENERATED'\
            """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        platformJdbc, TENANT, "EXPORT", "export", TriggerType.API, BIZ_DATE);
    String batchNo = "R15-EXPORT-" + Long.toUnsignedString(System.nanoTime());
    String settlementNo = "R15-SET-" + Long.toUnsignedString(System.nanoTime());
    String customerNo = "R15-CUSTOMER-" + Long.toUnsignedString(System.nanoTime());
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Long batchId =
        businessJdbc.queryForObject(insertSettlementBatchSql, Long.class, TENANT, batchNo);
    businessJdbc.update(insertSettlementDetailSql, TENANT, batchId, settlementNo, customerNo);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("batchNo", batchNo);
    params.put("templateCode", "EXP-SETTLEMENT-JSON");
    params.put("bizDate", BIZ_DATE.toString());
    params.put("bizType", "SETTLEMENT");
    params.put("fileCode", "r15-export-" + Long.toUnsignedString(System.nanoTime()));

    AtomicReference<Long> taskIdForDuplicateClaim = new AtomicReference<>();
    AtomicInteger duplicateDispatchClaimAttempts = new AtomicInteger();
    doAnswer(invocation -> {
          if (taskIdForDuplicateClaim.get() != null
              && taskIdForDuplicateClaim.get().equals(invocation.getArgument(0))) {
            duplicateDispatchClaimAttempts.incrementAndGet();
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));

    AtomicInteger reportAttempts = new AtomicInteger();
    AtomicReference<TaskExecutionReportCommand> firstReport = new AtomicReference<>();
    AtomicReference<TaskExecutionReportCommand> replacementReport = new AtomicReference<>();
    AtomicReference<BizException> staleReportFailure = new AtomicReference<>();
    CountDownLatch firstReportStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstReport = new CountDownLatch(1);
    CountDownLatch replacementReportStarted = new CountDownLatch(1);
    CountDownLatch releaseReplacementReport = new CountDownLatch(1);
    CountDownLatch staleReportRejected = new CountDownLatch(1);
    doAnswer(invocation -> {
          TaskExecutionReportCommand report = invocation.getArgument(1);
          int attempt = reportAttempts.incrementAndGet();
          if (attempt == 1) {
            firstReport.set(report);
            firstReportStarted.countDown();
            if (!releaseFirstReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException("R15 timed out waiting to release Export I1 REPORT");
            }
            try {
              return invocation.callRealMethod();
            } catch (BizException staleReport) {
              staleReportFailure.set(staleReport);
              staleReportRejected.countDown();
              throw staleReport;
            }
          }
          if (attempt == 2) {
            replacementReport.set(report);
            replacementReportStarted.countDown();
            if (!releaseReplacementReport.await(180, TimeUnit.SECONDS)) {
              throw new IllegalStateException("R15 timed out waiting to release Export I2 REPORT");
            }
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .report(any(Long.class), any(TaskExecutionReportCommand.class));

    String workerPoolCode = "r15-export-" + Long.toUnsignedString(System.nanoTime());
    String consumerGroupId = "batch-worker-export-r15-" + Long.toUnsignedString(System.nanoTime());
    Path worker1Log = createWorkerLog("r15-export-worker-1-");
    Path worker2Log = createWorkerLog("r15-export-worker-2-");
    Process worker1 = null;
    Process worker2 = null;
    try {
      worker1 = startExportWorker(workerPoolCode, consumerGroupId, worker1Log, "attempt-1");
      awaitExportWorkerStarted(worker1, worker1Log);
      awaitWorkerPoolOnline(workerPoolCode, "EXPORT");

      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          "r15-export-trace-" + batchNo,
          params));
      outboxPublishSupport.publishAllPending(TENANT);

      assertThat(firstReportStarted.await(300, TimeUnit.SECONDS))
          .as("Export I1 has stored and registered its object before REPORT")
          .isTrue();
      TaskExecutionReportCommand i1Report = firstReport.get();
      assertThat(i1Report).isNotNull();
      assertThat(reportStatus(seed)).isEqualTo("RUNNING");
      Map<String, Object> firstArtifact = queryGeneratedExport(batchNo);
      String artifactPath = (String) firstArtifact.get("storage_path");
      String artifactChecksum = (String) firstArtifact.get("checksum_value");
      assertThat(artifactPath).isNotBlank();
      assertThat(artifactChecksum).isNotBlank();

      stopProcess(worker1);
      worker1 = null;
      Long partitionId =
          platformJdbc.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, partitionId);
      platformJdbc.update(updateJobPartitionSql, TENANT, partitionId);
      partitionReclaimUnit.reclaim(partition);
      assertThat(reportStatus(seed)).isEqualTo("READY");
      String selectedPayloadJson = platformJdbc.queryForObject(
          selectPayloadJsonSql,
          String.class,
          TENANT,
          TENANT + ":reclaim:" + partitionId + ":v" + partition.getVersion());
      assertThat(selectedPayloadJson).isNull();
      outboxPublishSupport.publishAllPending(TENANT);

      worker2 = startExportWorker(workerPoolCode, consumerGroupId, worker2Log, "attempt-2");
      awaitExportWorkerStarted(worker2, worker2Log);
      awaitWorkerPoolOnline(workerPoolCode, "EXPORT");
      assertThat(replacementReportStarted.await(300, TimeUnit.SECONDS))
          .as("replacement Export Worker reuses the registered artifact and reaches REPORT")
          .isTrue();
      TaskExecutionReportCommand i2Report = replacementReport.get();
      assertThat(i2Report).isNotNull();
      assertThat(i2Report.partitionInvocationId()).isNotEqualTo(i1Report.partitionInvocationId());
      Map<String, Object> replacementArtifact = queryGeneratedExport(batchNo);
      assertThat(replacementArtifact.get("storage_path")).isEqualTo(artifactPath);
      assertThat(replacementArtifact.get("checksum_value")).isEqualTo(artifactChecksum);

      releaseFirstReport.countDown();
      assertThat(staleReportRejected.await(30, TimeUnit.SECONDS))
          .as("late I1 REPORT is rejected while I2 owns the task")
          .isTrue();
      assertThat(staleReportFailure.get().getCode()).isEqualTo(ResultCode.FORBIDDEN);
      assertThat(staleReportFailure.get().getMessageKey()).isEqualTo("error.worker.not_owner");
      releaseReplacementReport.countDown();

      await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
        assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
        assertThat(reportOutboxCount(seed)).isZero();
        Map<String, Object> finalArtifact = queryGeneratedExport(batchNo);
        assertThat(finalArtifact.get("storage_path")).isEqualTo(artifactPath);
        assertThat(finalArtifact.get("checksum_value")).isEqualTo(artifactChecksum);
        Integer selectedCount =
            platformJdbc.queryForObject(selectCountSql, Integer.class, TENANT, batchNo);
        assertThat(selectedCount).isEqualTo(1);
      });
      Integer selectedJobTaskCount = platformJdbc.queryForObject(
          selectCountSql2, Integer.class, TENANT, TENANT, seed.dedupKey());
      assertThat(selectedJobTaskCount).isEqualTo(1);

      Long taskId =
          platformJdbc.queryForObject(selectIdSql2, Long.class, TENANT, TENANT, seed.dedupKey());
      String recoveryDispatchPayload = platformJdbc.queryForObject(
          selectPayloadJsonSql2,
          String.class,
          TENANT,
          taskId,
          TENANT + ":reclaim:" + partitionId + ":v" + partition.getVersion());
      TaskDispatchMessage recoveryDispatch =
          JsonUtils.fromJson(recoveryDispatchPayload, TaskDispatchMessage.class);
      assertThat(recoveryDispatch).isNotNull();
      taskIdForDuplicateClaim.set(taskId);
      String dispatchKey = TENANT + ":" + recoveryDispatch.jobCode() + ":"
          + recoveryDispatch.instanceNo() + ":task:" + recoveryDispatch.taskId();
      kafkaTemplate
          .send(BatchTopics.TASK_DISPATCH_EXPORT, dispatchKey, recoveryDispatchPayload)
          .get();
      kafkaTemplate
          .send(BatchTopics.TASK_DISPATCH_EXPORT, dispatchKey, recoveryDispatchPayload)
          .get();
      // 发送响应前，接收端的幂等副作用已经提交。
      // 进程重启可能在显式重复消息之外重新投递原始 Kafka 记录；
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> assertThat(duplicateDispatchClaimAttempts.get()).isGreaterThanOrEqualTo(2));
      assertThat(reportStatus(seed)).isEqualTo("SUCCESS");
      assertThat(reportOutboxCount(seed)).isZero();
      Map<String, Object> afterDuplicateDispatch = queryGeneratedExport(batchNo);
      assertThat(afterDuplicateDispatch.get("storage_path")).isEqualTo(artifactPath);
      assertThat(afterDuplicateDispatch.get("checksum_value")).isEqualTo(artifactChecksum);
      Integer selectedCountRead2 =
          platformJdbc.queryForObject(selectCountSql3, Integer.class, TENANT, batchNo);
      assertThat(selectedCountRead2).isEqualTo(1);
    } finally {
      releaseFirstReport.countDown();
      releaseReplacementReport.countDown();
      stopProcess(worker1);
      stopProcess(worker2);
    }
  }

  private HttpResponse<String> postReport(TaskExecutionReportCommand report)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + localServerPort + "/internal/tasks/" + report.taskId()
            + "/report"))
        .header("Content-Type", "application/json")
        .header("X-Internal-Secret", internalSecret)
        .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(report)))
        .build();
    return REPORT_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String reportStatus(LaunchSeed seed) {
    final String selectTaskStatusSql = """
        select t.task_status from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;

    return platformJdbc.queryForObject(selectTaskStatusSql, String.class, TENANT, seed.dedupKey());
  }

  private int reportOutboxCount(LaunchSeed seed) {
    final String selectCountSql = """
        select count(*) from batch.worker_report_outbox where tenant_id = ? and task_id = (select t.id\s\
        from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id where ji.tenant_id = ?\s\
        and ji.dedup_key = ?)\
        """;

    return platformJdbc.queryForObject(
        selectCountSql, Integer.class, TENANT, TENANT, seed.dedupKey());
  }

  private String reportOutboxStatus(LaunchSeed seed) {
    final String selectPublishStatusSql = """
        select publish_status from batch.worker_report_outbox where tenant_id = ? and task_id = (select t.id\s\
        from batch.job_task t join batch.job_instance ji on ji.id = t.job_instance_id where ji.tenant_id = ?\s\
        and ji.dedup_key = ?)\
        """;

    return platformJdbc.queryForObject(
        selectPublishStatusSql, String.class, TENANT, TENANT, seed.dedupKey());
  }

  private Map<String, Object> queryGeneratedExport(String batchNo) {
    final String selectStoragePathSql = """
        select storage_path, checksum_value from batch.file_record where tenant_id = ? and source_ref = ?\s\
        and file_status = 'GENERATED'\
        """;

    return platformJdbc.queryForMap(selectStoragePathSql, TENANT, batchNo);
  }

  private Process startImportWorker(
      String workerCode, String consumerGroupId, Path logFile, long reportOutboxPollMillis)
      throws IOException, InterruptedException {
    return startImportWorker(workerCode, consumerGroupId, logFile, reportOutboxPollMillis, null);
  }

  private Process startImportWorker(
      String workerCode,
      String consumerGroupId,
      Path logFile,
      long reportOutboxPollMillis,
      String instanceId)
      throws IOException, InterruptedException {
    Path workerExecJar = locateImportExecJar();
    List<String> command = new ArrayList<>(List.of(
        JAVA_BIN,
        "-jar",
        workerExecJar.toString(),
        "--spring.profiles.active=test,e2e",
        "--server.port=0",
        "--spring.datasource.url=" + platformJdbcUrl(),
        "--spring.datasource.username=" + platformJdbcUsername(),
        "--spring.datasource.password=" + platformJdbcPassword(),
        "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--batch.datasource.business.url=" + businessJdbcUrl(),
        "--batch.datasource.business.username=" + businessJdbcUsername(),
        "--batch.datasource.business.password=" + businessJdbcPassword(),
        "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
        "--batch.rls.startup-fail-fast=false",
        "--batch.orchestrator.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.task-client.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.registry.fail-fast-on-startup=false",
        "--batch.security.bypass-mode=true",
        "--batch.security.internal-secret=" + internalSecret,
        "--batch.security.kms.default-key-ref=DEFAULT_TEST",
        "--batch.security.kms.keys.DEFAULT_TEST=AAAAAAAAAAAAAAAAAAAAAA==",
        "--batch.storage.s3.endpoint=" + s3Endpoint(),
        "--batch.storage.s3.region=us-east-1",
        "--batch.storage.s3.access-key=minioadmin",
        "--batch.storage.s3.secret-key=minioadmin123",
        "--batch.storage.s3.bucket=" + s3Bucket(),
        "--batch.worker.type=IMPORT",
        "--batch.worker.import.worker-code=" + workerCode,
        "--batch.worker.import.worker-type=IMPORT",
        "--batch.worker.import.tenant-id=" + TENANT,
        "--batch.worker.import.heartbeat-interval-millis=1000",
        "--batch.worker.import.topic=batch.task.dispatch.import",
        "--batch.worker.import.consumer-group-id=" + consumerGroupId,
        "--batch.worker.report-outbox.enabled=true",
        "--batch.worker.report-outbox.storage=PLATFORM_PG",
        "--batch.worker.report-outbox.backend-guard.cutover-id=r14-report-recovery-e2e-v1",
        "--batch.worker.report-outbox.poll-interval-millis=" + reportOutboxPollMillis,
        "--batch.worker.task-client.report-max-attempts=1"));
    if (instanceId != null && !instanceId.isBlank()) {
      command.add("--batch.worker.identity.instance-id=" + instanceId);
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    return builder.start();
  }

  private void awaitWorkerStarted(Process worker, Path logFile) {
    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(worker.isAlive()).isTrue();
          String log = Files.readString(logFile, StandardCharsets.UTF_8);
          assertThat(log).contains("Started BatchWorkerImportApplication");
        });
  }

  private void awaitWorkerOnline(String workerCode) {
    final String selectCountSql = """
        select count(1)::int from batch.worker_registry where tenant_id = ? and worker_code = ?\s\
        and status = 'ONLINE' and worker_group = 'IMPORT'\
        """;

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer selectedCount =
              platformJdbc.queryForObject(selectCountSql, Integer.class, TENANT, workerCode);
          assertThat(selectedCount).isEqualTo(1);
        });
  }

  private void awaitWorkerPoolOnline(String workerPoolCode) {
    awaitWorkerPoolOnline(workerPoolCode, "IMPORT");
  }

  private void awaitWorkerPoolOnline(String workerPoolCode, String workerGroup) {
    final String selectCountSql = """
        select count(1)::int from batch.worker_registry where tenant_id = ?\s\
        and coalesce(worker_pool_code, worker_code) = ? and status = 'ONLINE' and worker_group = ?\
        """;

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer selectedCount = platformJdbc.queryForObject(
              selectCountSql, Integer.class, TENANT, workerPoolCode, workerGroup);
          assertThat(selectedCount).isGreaterThanOrEqualTo(1);
        });
  }

  private Process startDispatchWorker(
      String workerPoolCode, String consumerGroupId, Path logFile, String instanceId)
      throws IOException, InterruptedException {
    return startDispatchWorker(workerPoolCode, consumerGroupId, logFile, instanceId, Map.of());
  }

  private Process startDispatchWorker(
      String workerPoolCode,
      String consumerGroupId,
      Path logFile,
      String instanceId,
      Map<String, String> propertyOverrides)
      throws IOException, InterruptedException {
    Path workerExecJar = locateDispatchExecJar();
    List<String> command = new ArrayList<>(List.of(
        JAVA_BIN,
        "-jar",
        workerExecJar.toString(),
        "--spring.profiles.active=test,e2e",
        "--server.port=0",
        "--spring.datasource.url=" + platformJdbcUrl(),
        "--spring.datasource.username=" + platformJdbcUsername(),
        "--spring.datasource.password=" + platformJdbcPassword(),
        "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
        "--batch.rls.startup-fail-fast=false",
        "--batch.orchestrator.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.task-client.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.registry.fail-fast-on-startup=false",
        "--batch.security.bypass-mode=true",
        "--batch.security.internal-secret=" + internalSecret,
        "--batch.storage.s3.endpoint=" + s3Endpoint(),
        "--batch.storage.s3.region=us-east-1",
        "--batch.storage.s3.access-key=minioadmin",
        "--batch.storage.s3.secret-key=minioadmin123",
        "--batch.storage.s3.bucket=" + s3Bucket(),
        "--batch.worker.type=DISPATCH",
        "--batch.worker.dispatch.worker-code=" + workerPoolCode,
        "--batch.worker.dispatch.worker-type=DISPATCH",
        "--batch.worker.dispatch.tenant-id=" + TENANT,
        "--batch.worker.dispatch.heartbeat-interval-millis=1000",
        "--batch.worker.dispatch.topic=batch.task.dispatch.dispatch",
        "--batch.worker.dispatch.consumer-group-id=" + consumerGroupId,
        "--batch.worker.dispatch.http-channel.call-timeout-millis=5000",
        "--batch.worker.report-outbox.enabled=true",
        "--batch.worker.report-outbox.storage=PLATFORM_PG",
        "--batch.worker.report-outbox.backend-guard.cutover-id=r16-dispatch-recovery-e2e-v1",
        "--batch.worker.report-outbox.poll-interval-millis=600000",
        "--batch.worker.task-client.report-max-attempts=1",
        "--batch.worker.identity.instance-id=" + instanceId));
    propertyOverrides.forEach((property, value) -> {
      String optionPrefix = "--" + property + "=";
      command.removeIf(option -> option.startsWith(optionPrefix));
      command.add(optionPrefix + value);
    });
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    return builder.start();
  }

  private void awaitDispatchWorkerStarted(Process worker, Path logFile) {
    await()
        .atMost(Duration.ofSeconds(240))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> {
          assertThat(worker.isAlive()).isTrue();
          String log = Files.readString(logFile, StandardCharsets.UTF_8);
          // 仅 "Started Application" 不代表 Kafka 消费者已完成分区分配：在消费者入组前派发的
          // 消息会被 latest 起点跳过，随后所有等待都会超时。这里显式等待消费组完成分区分配，
          // 再触发 launch，消除该时序竞争。
          assertThat(log)
              .contains("Started BatchWorkerDispatchApplication")
              .contains("partitions assigned");
        });
  }

  private Process startAtomicWorker(
      String workerPoolCode, String consumerGroupId, Path logFile, String instanceId)
      throws IOException, InterruptedException {
    return startAtomicWorker(workerPoolCode, consumerGroupId, logFile, instanceId, List.of());
  }

  private Process startAtomicWorker(
      String workerPoolCode,
      String consumerGroupId,
      Path logFile,
      String instanceId,
      List<String> executorOptions)
      throws IOException, InterruptedException {
    Path workerExecJar = locateAtomicExecJar();
    List<String> command = new ArrayList<>(List.of(
        JAVA_BIN,
        "-jar",
        workerExecJar.toString(),
        "--spring.profiles.active=test,e2e",
        "--server.port=0",
        "--spring.datasource.url=" + platformJdbcUrl(),
        "--spring.datasource.username=" + platformJdbcUsername(),
        "--spring.datasource.password=" + platformJdbcPassword(),
        "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
        "--batch.rls.startup-fail-fast=false",
        "--batch.orchestrator.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.task-client.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.registry.fail-fast-on-startup=false",
        "--batch.security.bypass-mode=true",
        "--batch.security.internal-secret=" + internalSecret,
        "--batch.worker.type=ATOMIC",
        "--batch.worker.atomic.worker-code=" + workerPoolCode,
        "--batch.worker.atomic.worker-type=ATOMIC",
        "--batch.worker.atomic.tenant-id=" + TENANT,
        "--batch.worker.atomic.heartbeat-interval-millis=1000",
        "--batch.worker.atomic.topic=batch.task.dispatch.atomic",
        "--batch.worker.atomic.consumer-group-id=" + consumerGroupId,
        "--batch.worker.executors.http.enabled=true",
        "--batch.worker.executors.http.block-private-ips=false",
        "--batch.worker.report-outbox.enabled=true",
        "--batch.worker.report-outbox.storage=PLATFORM_PG",
        "--batch.worker.report-outbox.backend-guard.cutover-id=r16-atomic-recovery-e2e-v1",
        "--batch.worker.report-outbox.poll-interval-millis=600000",
        "--batch.worker.task-client.report-max-attempts=1",
        "--batch.worker.identity.instance-id=" + instanceId));
    command.addAll(executorOptions);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    return builder.start();
  }

  private void awaitAtomicWorkerStarted(Process worker, Path logFile) {
    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(worker.isAlive()).isTrue();
          String log = Files.readString(logFile, StandardCharsets.UTF_8);
          assertThat(log).contains("Started BatchWorkerAtomicApplication");
        });
  }

  private Path locateAtomicExecJar() throws IOException, InterruptedException {
    if (atomicExecJar != null && Files.isRegularFile(atomicExecJar)) {
      return atomicExecJar;
    }
    Path targetDir = projectRoot().resolve("batch-worker/atomic/target");
    Path logFile = Files.createTempFile("worker-atomic-build-", ".log");
    ProcessBuilder builder = new ProcessBuilder(
        "./mvnw", "-o", "-ntp", "-pl", "batch-worker/atomic", "-am", "-DskipTests", "package");
    builder.directory(projectRoot().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    Process process = builder.start();
    try {
      if (!process.waitFor(WORKER_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IllegalStateException("timed out building batch-worker-atomic; see " + logFile);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("failed to build batch-worker-atomic; see " + logFile);
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    atomicExecJar = findLatestExecJar(targetDir);
    if (atomicExecJar == null) {
      throw new IllegalStateException("batch-worker-atomic exec jar not found under " + targetDir);
    }
    return atomicExecJar;
  }

  private Path locateDispatchExecJar() throws IOException, InterruptedException {
    if (dispatchExecJar != null && Files.isRegularFile(dispatchExecJar)) {
      return dispatchExecJar;
    }
    Path targetDir = projectRoot().resolve("batch-worker/dispatch/target");
    Path logFile = Files.createTempFile("worker-dispatch-build-", ".log");
    ProcessBuilder builder = new ProcessBuilder(
        "./mvnw",
        "-o",
        "-q",
        "-ntp",
        "-pl",
        "batch-worker/dispatch",
        "-am",
        "-DskipTests",
        "package");
    builder.directory(projectRoot().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    Process process = builder.start();
    try {
      if (!process.waitFor(WORKER_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IllegalStateException("timed out building batch-worker-dispatch; see " + logFile);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("failed to build batch-worker-dispatch; see " + logFile);
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    dispatchExecJar = findLatestExecJar(targetDir);
    if (dispatchExecJar == null) {
      throw new IllegalStateException(
          "batch-worker-dispatch exec jar not found under " + targetDir);
    }
    return dispatchExecJar;
  }

  private void ensureKafkaTopicsExist(List<String> topics) throws Exception {
    try (AdminClient adminClient = AdminClient.create(
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers()))) {
      adminClient
          .createTopics(
              topics.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
          .all()
          .get();
    } catch (ExecutionException exception) {
      if (!(exception.getCause() instanceof TopicExistsException)) {
        throw exception;
      }
    }
  }

  private Process startProcessWorker(
      String workerPoolCode, String consumerGroupId, Path logFile, String instanceId)
      throws IOException, InterruptedException {
    return startProcessWorker(
        workerPoolCode, consumerGroupId, logFile, instanceId, businessJdbcUrl());
  }

  private Process startProcessWorker(
      String workerPoolCode,
      String consumerGroupId,
      Path logFile,
      String instanceId,
      String businessUrl)
      throws IOException, InterruptedException {
    Path workerExecJar = locateProcessExecJar();
    List<String> command = new ArrayList<>(List.of(
        JAVA_BIN,
        "-jar",
        workerExecJar.toString(),
        "--spring.profiles.active=test,e2e",
        "--server.port=0",
        "--spring.datasource.url=" + platformJdbcUrl(),
        "--spring.datasource.username=" + platformJdbcUsername(),
        "--spring.datasource.password=" + platformJdbcPassword(),
        "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--batch.datasource.business.url=" + businessUrl,
        "--batch.datasource.business.username=" + businessJdbcUsername(),
        "--batch.datasource.business.password=" + businessJdbcPassword(),
        "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
        "--batch.rls.startup-fail-fast=false",
        "--batch.orchestrator.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.task-client.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.registry.fail-fast-on-startup=false",
        "--batch.security.bypass-mode=true",
        "--batch.security.internal-secret=" + internalSecret,
        "--batch.worker.type=PROCESS",
        "--batch.worker.process.worker-code=" + workerPoolCode,
        "--batch.worker.process.worker-type=PROCESS",
        "--batch.worker.process.tenant-id=" + TENANT,
        "--batch.worker.process.heartbeat-interval-millis=1000",
        "--batch.worker.process.topic=batch.task.dispatch.process",
        "--batch.worker.process.consumer-group-id=" + consumerGroupId,
        "--batch.worker.process.staging-cleanup.deletion-enabled=false",
        "--batch.worker.report-outbox.enabled=true",
        "--batch.worker.report-outbox.storage=PLATFORM_PG",
        "--batch.worker.report-outbox.backend-guard.cutover-id=r16-process-recovery-e2e-v1",
        "--batch.worker.report-outbox.poll-interval-millis=600000",
        "--batch.worker.task-client.report-max-attempts=1",
        "--batch.worker.identity.instance-id=" + instanceId));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    return builder.start();
  }

  private String proxiedJdbcUrl(
      String jdbcUrl, String proxyHost, int proxyPort, String applicationName) {
    URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    String query = uri.getRawQuery();
    String suffix = query == null ? "?" : "&";
    return "jdbc:postgresql://" + proxyHost + ":" + proxyPort + uri.getRawPath()
        + (query == null ? "" : "?" + query)
        + suffix + "ApplicationName=" + applicationName;
  }

  private void awaitProcessWorkerStarted(Process worker, Path logFile) {
    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(worker.isAlive()).isTrue();
          String log = Files.readString(logFile, StandardCharsets.UTF_8);
          assertThat(log).contains("Started BatchWorkerProcessApplication");
        });
  }

  private Path locateProcessExecJar() throws IOException, InterruptedException {
    if (processExecJar != null && Files.isRegularFile(processExecJar)) {
      return processExecJar;
    }
    Path targetDir = projectRoot().resolve("batch-worker/process/target");
    Path logFile = Files.createTempFile("worker-process-build-", ".log");
    ProcessBuilder builder = new ProcessBuilder(
        "./mvnw",
        "-o",
        "-q",
        "-ntp",
        "-pl",
        "batch-worker/process",
        "-am",
        "-DskipTests",
        "package");
    builder.directory(projectRoot().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    Process process = builder.start();
    try {
      if (!process.waitFor(WORKER_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IllegalStateException("timed out building batch-worker-process; see " + logFile);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("failed to build batch-worker-process; see " + logFile);
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    processExecJar = findLatestExecJar(targetDir);
    if (processExecJar == null) {
      throw new IllegalStateException("batch-worker-process exec jar not found under " + targetDir);
    }
    return processExecJar;
  }

  private Process startExportWorker(
      String workerCode, String consumerGroupId, Path logFile, String instanceId)
      throws IOException, InterruptedException {
    Path workerExecJar = locateExportExecJar();
    List<String> command = new ArrayList<>(List.of(
        JAVA_BIN,
        "-jar",
        workerExecJar.toString(),
        "--spring.profiles.active=test,e2e",
        "--server.port=0",
        "--spring.datasource.url=" + platformJdbcUrl(),
        "--spring.datasource.username=" + platformJdbcUsername(),
        "--spring.datasource.password=" + platformJdbcPassword(),
        "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--batch.datasource.business.url=" + businessJdbcUrl(),
        "--batch.datasource.business.username=" + businessJdbcUsername(),
        "--batch.datasource.business.password=" + businessJdbcPassword(),
        "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
        "--batch.rls.startup-fail-fast=false",
        "--batch.orchestrator.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.task-client.base-url=http://127.0.0.1:" + localServerPort,
        "--batch.worker.registry.fail-fast-on-startup=false",
        "--batch.security.bypass-mode=true",
        "--batch.security.internal-secret=" + internalSecret,
        "--batch.security.kms.default-key-ref=DEFAULT_TEST",
        "--batch.security.kms.keys.DEFAULT_TEST=AAAAAAAAAAAAAAAAAAAAAA==",
        "--batch.storage.s3.endpoint=" + s3Endpoint(),
        "--batch.storage.s3.region=us-east-1",
        "--batch.storage.s3.access-key=minioadmin",
        "--batch.storage.s3.secret-key=minioadmin123",
        "--batch.storage.s3.bucket=" + s3Bucket(),
        "--batch.storage.s3.path-style-enabled=true",
        "--batch.worker.type=EXPORT",
        "--batch.worker.export.worker-code=" + workerCode,
        "--batch.worker.export.worker-type=EXPORT",
        "--batch.worker.export.tenant-id=" + TENANT,
        "--batch.worker.export.heartbeat-interval-millis=1000",
        "--batch.worker.export.topic=batch.task.dispatch.export",
        "--batch.worker.export.consumer-group-id=" + consumerGroupId,
        "--batch.worker.report-outbox.enabled=true",
        "--batch.worker.report-outbox.storage=PLATFORM_PG",
        "--batch.worker.report-outbox.backend-guard.cutover-id=r15-export-recovery-e2e-v1",
        "--batch.worker.report-outbox.poll-interval-millis=600000",
        "--batch.worker.task-client.report-max-attempts=1",
        "--batch.worker.identity.instance-id=" + instanceId));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    return builder.start();
  }

  private void awaitExportWorkerStarted(Process worker, Path logFile) {
    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(worker.isAlive()).isTrue();
          String log = Files.readString(logFile, StandardCharsets.UTF_8);
          assertThat(log).contains("Started BatchWorkerExportApplication");
        });
  }

  private Path locateExportExecJar() throws IOException, InterruptedException {
    if (exportExecJar != null && Files.isRegularFile(exportExecJar)) {
      return exportExecJar;
    }
    Path targetDir = projectRoot().resolve("batch-worker/export/target");
    Path logFile = Files.createTempFile("worker-export-build-", ".log");
    ProcessBuilder builder = new ProcessBuilder(
        "./mvnw",
        "-o",
        "-q",
        "-ntp",
        "-pl",
        "batch-worker/export",
        "-am",
        "-DskipTests",
        "package");
    builder.directory(projectRoot().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    Process process = builder.start();
    try {
      if (!process.waitFor(WORKER_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IllegalStateException("timed out building batch-worker-export; see " + logFile);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("failed to build batch-worker-export; see " + logFile);
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    exportExecJar = findLatestExecJar(targetDir);
    if (exportExecJar == null) {
      throw new IllegalStateException("batch-worker-export exec jar not found under " + targetDir);
    }
    return exportExecJar;
  }

  private Path locateImportExecJar() throws IOException, InterruptedException {
    if (importExecJar != null && Files.isRegularFile(importExecJar)) {
      return importExecJar;
    }
    Path targetDir = projectRoot().resolve("batch-worker/import/target");
    Path logFile = Files.createTempFile("worker-import-build-", ".log");
    ProcessBuilder builder = new ProcessBuilder(
        "./mvnw",
        "-o",
        "-q",
        "-ntp",
        "-pl",
        "batch-worker/import",
        "-am",
        "-DskipTests",
        "package");
    builder.directory(projectRoot().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    Process process = builder.start();
    try {
      if (!process.waitFor(WORKER_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IllegalStateException("timed out building batch-worker-import; see " + logFile);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("failed to build batch-worker-import; see " + logFile);
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    importExecJar = findLatestExecJar(targetDir);
    if (importExecJar == null) {
      throw new IllegalStateException("batch-worker-import exec jar not found under " + targetDir);
    }
    return importExecJar;
  }

  private Path findLatestExecJar(Path targetDir) throws IOException {
    if (!Files.isDirectory(targetDir)) {
      return null;
    }
    try (var stream = Files.list(targetDir)) {
      return stream
          .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
          .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
          .orElse(null);
    }
  }

  private Path projectRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("batch-worker/import"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "unable to locate project root from " + System.getProperty("user.dir"));
  }

  private Path createWorkerLog(String prefix) throws IOException {
    return Files.createTempFile(prefix, ".log");
  }

  private void stopProcess(Process process) {
    if (process == null || !process.isAlive()) {
      return;
    }
    ProcessHandle handle = process.toHandle();
    handle.descendants().forEach(ProcessHandle::destroyForcibly);
    handle.destroyForcibly();
    try {
      handle.onExit().get(10, TimeUnit.SECONDS);
    } catch (TimeoutException timeoutException) {
      handle.descendants().forEach(ProcessHandle::destroyForcibly);
      handle.destroyForcibly();
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException ignored) {
      // 每份观察到的消息都必须经过领取围栏，但总数不保证固定。
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestDataSourceConfiguration {

    @Bean(name = "e2eBusinessDataSource")
    DataSource businessDataSource(
        @Value("${r14.test.business.url}") String url,
        @Value("${r14.test.business.username}") String username,
        @Value("${r14.test.business.password}") String password) {
      return DataSourceBuilder.create()
          .url(url)
          .username(username)
          .password(password)
          .driverClassName("org.postgresql.Driver")
          .build();
    }

    @Bean(name = "e2eBusinessTransactionManager", autowireCandidate = false)
    DataSourceTransactionManager businessTransactionManager(
        @Qualifier("e2eBusinessDataSource") DataSource businessDataSource) {
      return new DataSourceTransactionManager(businessDataSource);
    }

    @Bean(name = "transactionManager")
    @ConditionalOnMissingBean(name = "transactionManager")
    DataSourceTransactionManager transactionManager(
        @Qualifier("dataSource") DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }
}
