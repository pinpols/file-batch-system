package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eStatusLogger;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.plugin.GenericJdbcMappedImportLoadPlugin;
import io.github.pinpols.batch.worker.imports.stage.ValidateStep;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

/**
 * 端到端测试：Import 主链路成功闭环。
 *
 * <p>链路路径：
 *
 * <pre>
 * API 触发 launch → orchestrator 调度/落 outbox → Kafka 派发 → import worker claim → 执行 pipeline
 *     → worker report → orchestrator 写入数据库终态（task/partition/job_instance）
 * </pre>
 *
 * <p>断言点（典型）：
 *
 * <ul>
 *   <li>task_status 最终为 SUCCESS
 *   <li>业务表写入成功（import 的“交付结果”）
 * </ul>
 */
@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.file-governance.arrival.enabled=true",
      "batch.file-governance.arrival.poll-interval-millis=3600000",
      "batch.file-governance.arrival.batch-size=2"
    })
@E2eBusinessSchema
@Sql(
    scripts = {
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("smoke")
@DisplayName("导入任务主链路成功闭环:调度派发、执行进程认领执行、回报汇聚到任务成功终态,并核对业务结果落库")
class ImportPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Autowired
  private FileGovernanceScheduler fileGovernanceScheduler;

  @Autowired
  private BatchDaySettleScheduler batchDaySettleScheduler;

  @Autowired
  private TaskExecutionService taskExecutionService;

  @Autowired
  private RetryGovernanceService retryGovernanceService;

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  @MockitoSpyBean
  private TaskControllerApplicationService taskControllerApplicationService;

  @Autowired
  @Qualifier("importBusinessDataSource")
  private DataSource businessDataSource;

  @Autowired
  private WorkerCheckpointProperties checkpointProperties;

  @MockitoSpyBean
  private GenericJdbcMappedImportLoadPlugin genericImportLoadPlugin;

  @MockitoSpyBean
  private ValidateStep validateStep;

  @Test
  @DisplayName("导入任务经派发与认领执行后回报成功,任务终态为成功,且业务表中存在对应客户记录")
  void shouldImportAndPersistBusinessRecords_whenJobDispatchedAndClaimed() {
    final String selectCountSql = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?\
        """;

    final String selectTaskStatusSql = """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        "[{\"customerNo\":\"E2E001\",\"customerName\":\"E2E User\",\"customerType\":\"PERSONAL\","
            + "\"certificateNo\":\"ID-20260115-0001\",\"mobileNo\":\"13800000001\","
            + "\"email\":\"e2e@example.com\",\"status\":\"ACTIVE\"}]");

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-import",
        params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          E2eStatusLogger.logJobFlowSnapshot(
              jdbcTemplate, TENANT, seed.dedupKey(), "ImportPipelineE2eIT");
          String status = jdbcTemplate.queryForObject(
              selectTaskStatusSql, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Integer rows = businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, "E2E001");
    assertThat(rows).isNotNull();
    assertThat(rows).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("LOAD 不可重试失败时保留已推进的指纹位点和已写入数据")
  void shouldRetainFingerprintCheckpointWhenLoadFails() throws Exception {
    final String updateJobSql = """
        update batch.job_definition set retry_policy = 'FIXED', retry_max_count = 2
        where tenant_id = ? and job_code = ?
        """;
    final String updateTemplateSql = """
        update batch.file_template_config set chunk_size = 1
        where tenant_id = ? and template_code = 'IMP-CUSTOMER-JSON-ARRAY'
        """;
    final String selectPartitionSql = """
        select p.id from batch.job_partition p join batch.job_instance ji
          on ji.tenant_id = p.tenant_id and ji.id = p.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectTaskStatusSql = """
        select t.error_code from batch.job_task t join batch.job_instance ji
          on ji.tenant_id = t.tenant_id and ji.id = t.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectCheckpointSql = """
        select pp.position_marker from batch.pipeline_progress pp
        join batch.pipeline_instance pi on pi.tenant_id = pp.tenant_id and pi.id = pp.pipeline_instance_id
        join batch.job_instance ji on ji.tenant_id = pi.tenant_id and ji.id = pi.related_job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ? and pp.stage = 'LOAD'
        """;
    final String selectRetryCountSql = """
        select count(*) from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'
          and related_id = ? and retry_status = 'WAITING'
        """;
    final String selectCustomersSql = """
        select customer_no from biz.customer_account where tenant_id = ? and customer_no in (?, ?)
        order by customer_no
        """;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    jdbcTemplate.update(updateJobSql, TENANT, seed.jobCode());
    jdbcTemplate.update(updateTemplateSql, TENANT);
    String firstCustomer = "CKPTA" + Long.toUnsignedString(System.nanoTime());
    String secondCustomer = "CKPTB" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> params = importParams(firstCustomer);
    params.put(
        "content",
        JsonUtils.toJson(List.of(
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
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> {
          if (calls.incrementAndGet() == 2) {
            throw new IllegalStateException("injected failure before second chunk");
          }
          return invocation.callRealMethod();
        })
        .when(genericImportLoadPlugin)
        .loadChunk(any(), any());

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-import-checkpoint-retry",
        params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(jdbcTemplate.queryForObject(
                  selectTaskStatusSql, String.class, TENANT, seed.dedupKey()))
              .isEqualTo("IMPORT_LOAD_FAILED");
          assertThat(jdbcTemplate.queryForObject(
                  selectCheckpointSql, String.class, TENANT, seed.dedupKey()))
              .matches("1:[0-9a-f]{64}");
        });
    Long partitionId =
        jdbcTemplate.queryForObject(selectPartitionSql, Long.class, TENANT, seed.dedupKey());
    assertThat(jdbcTemplate.queryForObject(selectRetryCountSql, Integer.class, TENANT, partitionId))
        .isZero();
    assertThat(new JdbcTemplate(businessDataSource)
            .queryForList(selectCustomersSql, String.class, TENANT, firstCustomer, secondCustomer))
        .containsExactly(firstCustomer);
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("校验后暂存文件丢失且存在跳过记录时任务失败，业务库不写入")
  void shouldFailImportWhenValidatedFileDisappearsAfterValidation() throws Exception {
    final String selectTaskErrorSql = """
        select t.error_code from batch.job_task t
        join batch.job_instance ji on ji.tenant_id = t.tenant_id and ji.id = t.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectCustomerCountSql = """
        select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?
        """;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "LOST" + Long.toUnsignedString(System.nanoTime());
    AtomicBoolean injected = new AtomicBoolean();
    doAnswer(invocation -> {
          ImportStageResult result = (ImportStageResult) invocation.callRealMethod();
          ImportJobContext context = invocation.getArgument(0);
          if (result.success()) {
            assertThat(context.getAttributes().get(PipelineRuntimeKeys.IMPORT_VALIDATED_COUNT))
                .isEqualTo(1L);
            Path validated = Path.of(String.valueOf(
                context.getAttributes().get(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH)));
            Files.delete(validated);
            context.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT, 1L);
            injected.set(true);
          }
          return result;
        })
        .when(validateStep)
        .execute(any());

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-import-missing-validated",
        importParams(customerNo)));
    e2eOutboxPublishSupport.publishAllPending(TENANT);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String error = jdbcTemplate.queryForObject(
              selectTaskErrorSql, String.class, TENANT, seed.dedupKey());
          assertThat(error).isEqualTo("IMPORT_LOAD_NO_PAYLOAD");
        });
    assertThat(injected).isTrue();
    Integer count = new JdbcTemplate(businessDataSource)
        .queryForObject(selectCustomerCountSql, Integer.class, TENANT, customerNo);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("非幂等 LOAD 已提交业务写入后失败时保留 UNKNOWN 且不自动重投")
  void shouldNotRetryImportLoad_whenUnknownPluginFailsAfterCommittedChunk() throws Exception {
    assertUnknownPluginOutcome(false);
  }

  @Test
  @DisplayName("非幂等 LOAD 未确认整批时保留 UNKNOWN 且不自动重投")
  void shouldNotRetryImportLoad_whenUnknownPluginAcknowledgesPartialChunk() throws Exception {
    assertUnknownPluginOutcome(true);
  }

  private void assertUnknownPluginOutcome(boolean partialAcknowledgement) throws Exception {
    final String selectCountSql2 = """
        select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?
    """;

    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = 'FIXED', retry_max_count = 2 where tenant_id = ?\s\
        and job_code = ?\
        """;
    final String selectErrorCodeSql = """
        select t.error_code from batch.job_task t join batch.job_instance ji on ji.tenant_id = t.tenant_id\s\
        and ji.id = t.job_instance_id where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String selectIdSql = """
        select p.id from batch.job_partition p join batch.job_instance ji on ji.tenant_id = p.tenant_id\s\
        and ji.id = p.job_instance_id where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String selectNonIdempotentStartedAtSql = """
        select t.non_idempotent_started_at is not null from batch.job_task t\s\
        join batch.job_instance ji on ji.tenant_id = t.tenant_id and ji.id = t.job_instance_id\s\
        where ji.tenant_id = ? and ji.dedup_key = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'\s\
        and related_id = ?\
        """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "UNKNOWN" + Long.toUnsignedString(System.nanoTime());
    jdbcTemplate.update(updateJobDefinitionSql, TENANT, seed.jobCode());

    boolean checkpointEnabled = checkpointProperties.isEnabled();
    AtomicInteger loadCalls = new AtomicInteger();
    checkpointProperties.setEnabled(false);
    doReturn(IdempotencyCapability.UNKNOWN).when(genericImportLoadPlugin).idempotencyCapability();
    doAnswer(invocation -> {
          loadCalls.incrementAndGet();
          invocation.callRealMethod();
          if (partialAcknowledgement) {
            return 0;
          }
          throw new IllegalStateException("injected failure after committed Import chunk");
        })
        .when(genericImportLoadPlugin)
        .loadChunk(any(), any());

    try {
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-import-unknown-outcome",
          importParams(customerNo)));
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            String selectedErrorCode = jdbcTemplate.queryForObject(
                selectErrorCodeSql, String.class, TENANT, seed.dedupKey());
            assertThat(selectedErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
          });

      Long partitionId =
          jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      assertThat(partitionId).isNotNull();
      Boolean selectedNonIdempotentStartedAt = jdbcTemplate.queryForObject(
          selectNonIdempotentStartedAtSql, Boolean.class, TENANT, seed.dedupKey());
      assertThat(selectedNonIdempotentStartedAt).isTrue();
      Integer selectedRetryScheduleCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, partitionId);
      assertThat(selectedRetryScheduleCount).isZero();
      assertThat(loadCalls).hasValue(1);

      JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
      Integer selectedCustomerAccountCount =
          businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, customerNo);
      assertThat(selectedCustomerAccountCount).isEqualTo(1);
    } finally {
      checkpointProperties.setEnabled(checkpointEnabled);
    }
  }

  @Test
  @DisplayName("同一 Workflow 和业务日并发 dry-run 与正式导入,仅正式运行写入业务表")
  void shouldIsolateSameDayDryRunAndFormalImport_whenWorkerExecutesBothRuns() throws Exception {
    final String selectCountSql = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?
    """;
    final String selectPipelineFileSql = """
        select pi.file_id
        from batch.trigger_request tr
        join batch.workflow_run wr on wr.tenant_id = tr.tenant_id and wr.trigger_request_id = tr.id
        join batch.workflow_node_run nr on nr.workflow_run_id = wr.id and nr.node_type = 'JOB'
        join batch.pipeline_instance pi
          on pi.tenant_id = wr.tenant_id and pi.related_job_instance_id = nr.job_instance_id
        where tr.tenant_id = ? and tr.request_id = ?
        """;

    final String selectInstanceStatusSql = """
          select ji.instance_status
          from batch.trigger_request tr
          join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
              and wr.trigger_request_id = tr.id
          join batch.workflow_node_run nr on nr.workflow_run_id = wr.id
              and nr.node_type = 'JOB' and nr.job_instance_id is not null
          join batch.job_instance ji on ji.tenant_id = wr.tenant_id
              and ji.id = nr.job_instance_id
          where tr.tenant_id = ? and tr.request_id in (?, ?)
          """;
    final String selectRunStatusSql = """
          select wr.run_status
          from batch.trigger_request tr
          join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
              and wr.trigger_request_id = tr.id
          where tr.tenant_id = ? and tr.request_id in (?, ?)
          """;
    final String selectStatusSql = """
        select status from batch.result_version where tenant_id = ? and business_key = ? order by version_no\
        """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LocalDate bizDate = LocalDate.of(2026, 1, 16);
    String dryRunRequestId = "e2e-dry-" + Long.toUnsignedString(System.nanoTime());
    String formalRequestId = "e2e-formal-" + Long.toUnsignedString(System.nanoTime());
    String dryRunCustomerNo = "DRY" + Long.toUnsignedString(System.nanoTime());
    String formalCustomerNo = "FORMAL" + Long.toUnsignedString(System.nanoTime());
    Map<String, Object> dryRunParams = importParams(dryRunCustomerNo);
    Map<String, Object> formalParams = importParams(formalCustomerNo);
    insertWorkflowTriggerRequest(seed.workflowDefinitionId(), bizDate, dryRunRequestId, true);
    insertWorkflowTriggerRequest(seed.workflowDefinitionId(), bizDate, formalRequestId, false);

    LaunchRequest dryRunRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(dryRunRequestId)
        .traceId("e2e-dry-run-same-day")
        .params(dryRunParams)
        .dryRun(true)
        .build();
    LaunchRequest formalRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(formalRequestId)
        .traceId("e2e-formal-same-day")
        .params(formalParams)
        .dryRun(false)
        .build();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> dryRunLaunch = executor.submit(() -> {
        ready.countDown();
        start.await();
        launchService.launch(dryRunRequest);
        return null;
      });
      Future<?> formalLaunch = executor.submit(() -> {
        ready.countDown();
        start.await();
        launchService.launch(formalRequest);
        return null;
      });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      dryRunLaunch.get();
      formalLaunch.get();
    } finally {
      executor.shutdownNow();
    }
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          List<String> instanceStatuses = jdbcTemplate.queryForList(
              selectInstanceStatusSql, String.class, TENANT, dryRunRequestId, formalRequestId);
          assertThat(instanceStatuses).containsExactlyInAnyOrder("SUCCESS_DRY_RUN", "SUCCESS");
          List<String> workflowStatuses = jdbcTemplate.queryForList(
              selectRunStatusSql, String.class, TENANT, dryRunRequestId, formalRequestId);
          assertThat(workflowStatuses).containsExactlyInAnyOrder("SUCCESS_DRY_RUN", "SUCCESS");
        });

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Integer selectedCount =
        businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, dryRunCustomerNo);
    assertThat(selectedCount).isZero();
    Integer selectedCountRead2 =
        businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, formalCustomerNo);
    assertThat(selectedCountRead2).isEqualTo(1);

    List<Long> dryFileIds =
        jdbcTemplate.queryForList(selectPipelineFileSql, Long.class, TENANT, dryRunRequestId);
    List<Long> formalFileIds =
        jdbcTemplate.queryForList(selectPipelineFileSql, Long.class, TENANT, formalRequestId);
    assertThat(dryFileIds).containsExactly((Long) null);
    assertThat(formalFileIds).singleElement().isNotNull();

    List<String> resultStatuses = jdbcTemplate.queryForList(
        selectStatusSql, String.class, TENANT, "job:" + seed.jobCode() + ":" + bizDate);
    assertThat(resultStatuses).containsExactlyInAnyOrder("DRY_RUN", "EFFECTIVE");
  }

  @Test
  @DisplayName("同一任务派发消息重复投递时,真实 Worker 只认领一次并只写入一条业务记录")
  void shouldExecuteBusinessEffectOnce_whenDispatchMessageIsRedelivered() throws Exception {
    final String selectCountSql3 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?
    """;

    final String selectIdSql = """
        select task.id
        from batch.trigger_request tr
        join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
            and wr.trigger_request_id = tr.id
        join batch.workflow_node_run node_run on node_run.workflow_run_id = wr.id
            and node_run.node_type = 'JOB'
        join batch.job_task task on task.job_instance_id = node_run.job_instance_id
        where tr.tenant_id = ? and tr.request_id = ?
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
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.job_task where tenant_id = ? and id = ?
    """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LocalDate bizDate = LocalDate.of(2026, 1, 18);
    String requestId = "e2e-dispatch-redelivery-" + Long.toUnsignedString(System.nanoTime());
    String customerNo = "REDELIVERY" + Long.toUnsignedString(System.nanoTime());
    insertWorkflowTriggerRequest(seed.workflowDefinitionId(), bizDate, requestId, false);
    launchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(requestId)
        .traceId("e2e-dispatch-redelivery")
        .params(importParams(customerNo))
        .build());

    Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, requestId);
    String payload =
        jdbcTemplate.queryForObject(selectPayloadJsonSql, String.class, TENANT, taskId);
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
        BatchTopics.TASK_DISPATCH_IMPORT, message.selectedWorkerId());
    String kafkaKey =
        TENANT + ":" + message.jobCode() + ":" + message.instanceNo() + ":task:" + message.taskId();
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
        });

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Integer selectedCount =
        businessJdbc.queryForObject(selectCountSql3, Integer.class, TENANT, customerNo);
    assertThat(selectedCount).isEqualTo(1);
    Integer selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, TENANT, requestId);
    assertThat(selectedWorkflowRunCount).isEqualTo(1);
    Integer selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, taskId);
    assertThat(selectedJobTaskCount).isEqualTo(1);
  }

  @Test
  @DisplayName("同 Workflow 和业务日 dry-run 重试后仍只产出演练版本,正式运行独立落库")
  void shouldKeepDryRunRetryArtifactsIsolatedFromFormalRun() throws Exception {
    final String selectCountSql = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no = ?
    """;

    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = 'FIXED', retry_max_count = 1 where tenant_id = ?\s\
        and job_code = ?\
        """;
    final String selectJobInstanceIdSql = """
        select node_run.job_instance_id
        from batch.trigger_request tr
        join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
            and wr.trigger_request_id = tr.id
        join batch.workflow_node_run node_run on node_run.workflow_run_id = wr.id
            and node_run.node_type = 'JOB'
        where tr.tenant_id = ? and tr.request_id = ?
        """;
    final String selectIdSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ?
    """;
    final String selectIdSql2 = """
        select id from batch.job_partition where tenant_id = ? and job_instance_id = ?
    """;
    final String selectTaskStatusSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectCurrentInvocationIdSql = """
        select current_invocation_id from batch.job_partition where tenant_id = ? and id = ?
    """;
    final String updateRetryScheduleSql = """
        update batch.retry_schedule set next_retry_at = now() - interval '1 second' where tenant_id = ?\s\
        and related_type = 'JOB_PARTITION' and related_id = ? and retry_status = 'WAITING'\
        """;
    final String selectPayloadJsonSql = """
        select payload_json::text
        from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        order by id desc
        limit 1
        """;
    final String selectRequestIdSql = """
          select tr.request_id, wr.run_status, ji.instance_status
          from batch.trigger_request tr
          join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
              and wr.trigger_request_id = tr.id
          join batch.workflow_node_run node_run on node_run.workflow_run_id = wr.id
              and node_run.node_type = 'JOB'
          join batch.job_instance ji on ji.tenant_id = wr.tenant_id
              and ji.id = node_run.job_instance_id
          where tr.tenant_id = ? and tr.request_id in (?, ?)
          """;
    final String selectRetryCountSql = """
        select retry_count from batch.job_partition where tenant_id = ? and id = ?
    """;
    final String selectJobInstanceIdSql2 = """
        select job_instance_id, status from batch.result_version where tenant_id = ? and business_key = ?\
        """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    LocalDate bizDate = LocalDate.of(2026, 1, 17);
    String dryRunRequestId = "e2e-dry-retry-" + Long.toUnsignedString(System.nanoTime());
    String formalRequestId = "e2e-formal-retry-" + Long.toUnsignedString(System.nanoTime());
    String dryRunCustomerNo = "DRYRETRY" + Long.toUnsignedString(System.nanoTime());
    String formalCustomerNo = "FORMALRETRY" + Long.toUnsignedString(System.nanoTime());
    jdbcTemplate.update(updateJobDefinitionSql, TENANT, seed.jobCode());
    insertWorkflowTriggerRequest(seed.workflowDefinitionId(), bizDate, dryRunRequestId, true);
    insertWorkflowTriggerRequest(seed.workflowDefinitionId(), bizDate, formalRequestId, false);

    launchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(dryRunRequestId)
        .traceId("e2e-dry-retry-same-day")
        .params(importParams(dryRunCustomerNo))
        .dryRun(true)
        .build());
    launchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(formalRequestId)
        .traceId("e2e-formal-retry-same-day")
        .params(importParams(formalCustomerNo))
        .dryRun(false)
        .build());

    Long dryRunInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, TENANT, dryRunRequestId);
    Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, dryRunInstanceId);
    Long partitionId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, dryRunInstanceId);
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String selectedTaskStatus =
              jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
          assertThat(selectedTaskStatus).isEqualTo("READY");
        });

    taskExecutionService.assignWorker(TENANT, taskId, "e2e-import-1");
    String firstInvocationId = jdbcTemplate.queryForObject(
        selectCurrentInvocationIdSql, String.class, TENANT, partitionId);
    taskExecutionService.applyTaskOutcome(TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(taskId)
        .errorCode("R16_TRANSIENT_TEST_FAILURE")
        .errorMessage("controlled pre-dispatch retry")
        .partitionInvocationId(firstInvocationId)
        .build());
    jdbcTemplate.update(updateRetryScheduleSql, TENANT, partitionId);
    retryGovernanceService.dispatchDueRetries();

    String retryPayload =
        jdbcTemplate.queryForObject(selectPayloadJsonSql, String.class, TENANT, taskId);
    TaskDispatchMessage retryMessage = JsonUtils.fromJson(retryPayload, TaskDispatchMessage.class);
    AtomicReference<Long> retriedTaskId = new AtomicReference<>(taskId);
    AtomicInteger retryClaimAttempts = new AtomicInteger();
    AtomicInteger successfulRetryClaims = new AtomicInteger();
    doAnswer(invocation -> {
          if (retriedTaskId.get().equals(invocation.getArgument(0))) {
            retryClaimAttempts.incrementAndGet();
          }
          Object result = invocation.callRealMethod();
          if (retriedTaskId.get().equals(invocation.getArgument(0))) {
            successfulRetryClaims.incrementAndGet();
          }
          return result;
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));

    e2eOutboxPublishSupport.publishAllPending(TENANT);
    String retryDispatchTopic = BatchTopics.directDispatchTopic(
        BatchTopics.TASK_DISPATCH_IMPORT, retryMessage.selectedWorkerId());
    String retryKafkaKey = TENANT + ":" + retryMessage.jobCode() + ":" + retryMessage.instanceNo()
        + ":task:" + retryMessage.taskId();
    kafkaTemplate.send(retryDispatchTopic, retryKafkaKey, retryPayload).get();
    kafkaTemplate.send(retryDispatchTopic, retryKafkaKey, retryPayload).get();

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(retryClaimAttempts.get()).isEqualTo(4);
          assertThat(successfulRetryClaims.get()).isEqualTo(1);
          List<Map<String, Object>> runs = jdbcTemplate.queryForList(
              selectRequestIdSql, TENANT, dryRunRequestId, formalRequestId);
          assertThat(runs).hasSize(2);
          assertThat(runs)
              .anySatisfy(run -> assertThat(run)
                  .containsEntry("request_id", dryRunRequestId)
                  .containsEntry("run_status", "SUCCESS_DRY_RUN")
                  .containsEntry("instance_status", "SUCCESS_DRY_RUN"));
          assertThat(runs)
              .anySatisfy(run -> assertThat(run)
                  .containsEntry("request_id", formalRequestId)
                  .containsEntry("run_status", "SUCCESS")
                  .containsEntry("instance_status", "SUCCESS"));
          Integer selectedRetryCount =
              jdbcTemplate.queryForObject(selectRetryCountSql, Integer.class, TENANT, partitionId);
          assertThat(selectedRetryCount).isEqualTo(1);
        });

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Integer selectedCount =
        businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, dryRunCustomerNo);
    assertThat(selectedCount).isZero();
    Integer selectedCountRead2 =
        businessJdbc.queryForObject(selectCountSql, Integer.class, TENANT, formalCustomerNo);
    assertThat(selectedCountRead2).isEqualTo(1);

    Map<Long, String> resultStatuses = jdbcTemplate
        .queryForList(selectJobInstanceIdSql2, TENANT, "job:" + seed.jobCode() + ":" + bizDate)
        .stream()
        .collect(Collectors.toMap(row -> ((Number) row.get("job_instance_id")).longValue(), row ->
            (String) row.get("status")));
    Long formalInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, TENANT, formalRequestId);
    assertThat(resultStatuses)
        .containsEntry(dryRunInstanceId, "DRY_RUN")
        .containsEntry(formalInstanceId, "EFFECTIVE")
        .hasSize(2);
  }

  private Map<String, Object> importParams(String customerNo) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Same day run","customerType":"PERSONAL",\
        "certificateNo":"CERT-%s","status":"ACTIVE"}]\
        """.formatted(customerNo, customerNo);
    params.put("content", customerContent);
    return params;
  }

  private void insertWorkflowTriggerRequest(
      Long workflowDefinitionId, LocalDate bizDate, String requestId, boolean dryRun) {
    final String insertTriggerRequestSql = """
            insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
            biz_date, dedup_key, request_status, trace_id, dry_run)\s\
            values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', ?, ?)\
            """;

    jdbcTemplate.update(
        insertTriggerRequestSql,
        TENANT,
        requestId,
        workflowDefinitionId,
        bizDate,
        requestId + "-dedup",
        requestId + "-trace",
        dryRun);
  }

  @Test
  @DisplayName("已有文件的演练成功及失败均不改变正式文件记录")
  void shouldPreserveExistingFiles_whenBundleDryRunSucceedsAndFails() {
    final String missingObjectSql = """
        update batch.file_record set storage_path = ? where tenant_id = ? and id = ?
        """;
    final String enableDryRunSql = """
        update batch.trigger_request set dry_run = true where tenant_id = ? and request_id = ?
        """;
    final String fileSnapshotSql = """
        select row_to_json(f)::text from batch.file_record f where tenant_id = ? and id = ?
        """;
    final String taskStatusSql = """
        select p.source_file_id, t.task_status from batch.job_task t
        join batch.job_partition p on p.tenant_id = t.tenant_id and p.id = t.job_partition_id
        join batch.job_instance i on i.tenant_id = t.tenant_id and i.id = t.job_instance_id
        where i.tenant_id = ? and i.dedup_key = ?
        """;
    final String countBusinessRowsSql = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no like ?
        """;

    String prefix = "DRYBUNDLE" + Long.toUnsignedString(System.nanoTime()) + "-";
    long validFileId = insertBundleInput(prefix + "valid", 2);
    long missingFileId = insertBundleInput(prefix + "missing", 1);
    jdbcTemplate.update(missingObjectSql, "missing/" + prefix, TENANT, missingFileId);
    String validBefore =
        jdbcTemplate.queryForObject(fileSnapshotSql, String.class, TENANT, validFileId);
    String missingBefore =
        jdbcTemplate.queryForObject(fileSnapshotSql, String.class, TENANT, missingFileId);
    LaunchSeed seed = E2eScenarioFixture.prepareBundleLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "import");
    jdbcTemplate.update(enableDryRunSql, TENANT, seed.requestId());
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", validFileId, "templateCode", "IMP-CUSTOMER-JSON-ARRAY"),
            Map.of("sourceFileId", missingFileId, "templateCode", "IMP-CUSTOMER-JSON-ARRAY")));
    launchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, 1, 15))
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("e2e-existing-file-dry-run")
        .params(params)
        .dryRun(true)
        .build());
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await().atMost(Duration.ofSeconds(180)).untilAsserted(() -> {
      List<Map<String, Object>> statuses =
          jdbcTemplate.queryForList(taskStatusSql, TENANT, seed.dedupKey());
      assertThat(statuses).hasSize(2);
      assertThat(statuses).anySatisfy(row -> {
        assertThat(((Number) row.get("source_file_id")).longValue()).isEqualTo(validFileId);
        assertThat(row.get("task_status")).isEqualTo("SUCCESS");
      });
      assertThat(statuses).anySatisfy(row -> {
        assertThat(((Number) row.get("source_file_id")).longValue()).isEqualTo(missingFileId);
        assertThat(row.get("task_status")).isEqualTo("FAILED");
      });
    });

    assertThat(jdbcTemplate.queryForObject(fileSnapshotSql, String.class, TENANT, validFileId))
        .isEqualTo(validBefore);
    assertThat(jdbcTemplate.queryForObject(fileSnapshotSql, String.class, TENANT, missingFileId))
        .isEqualTo(missingBefore);
    assertThat(new JdbcTemplate(businessDataSource)
            .queryForObject(countBusinessRowsSql, Integer.class, TENANT, prefix + "%"))
        .isZero();
  }

  @Test
  @DisplayName("文件束经真实派发与认领后完整导入两个独立文件,分别十行与十五行且两片均成功")
  void shouldImportEveryRecord_whenBundlePartitionsBindIndependentFiles() {
    final String selectCountSql2 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no like ?
    """;

    final String selectCountSql = """
              select count(*)::int from batch.job_task t
              join batch.job_instance i on i.id = t.job_instance_id and i.tenant_id = t.tenant_id
              where i.tenant_id = ? and i.dedup_key = ? and t.task_status = 'SUCCESS'
              """;
    final String selectInstanceStatusSql = """
              select instance_status from batch.job_instance where tenant_id = ? and dedup_key = ?
              """;

    String prefix = "BNDL" + Long.toUnsignedString(System.nanoTime()) + "-";
    long first = insertBundleInput(prefix + "A", 10);
    long second = insertBundleInput(prefix + "B", 15);
    LaunchSeed seed = E2eScenarioFixture.prepareBundleLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "import");
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", first, "templateCode", "IMP-CUSTOMER-JSON-ARRAY"),
            Map.of("sourceFileId", second, "templateCode", "IMP-CUSTOMER-JSON-ARRAY")));

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.EVENT,
        seed.requestId(),
        "e2e-tr-bundle-import",
        params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await().atMost(Duration.ofSeconds(180)).untilAsserted(() -> {
      Integer successfulTasks =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
      assertThat(successfulTasks).isEqualTo(2);
      String instanceStatus = jdbcTemplate.queryForObject(
          selectInstanceStatusSql, String.class, TENANT, seed.dedupKey());
      assertThat(instanceStatus).isEqualTo("SUCCESS");
    });
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Integer selectedCount =
        businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, prefix + "A%");
    assertThat(selectedCount).isEqualTo(10);
    Integer selectedCountRead2 =
        businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, prefix + "B%");
    assertThat(selectedCountRead2).isEqualTo(15);
  }

  @Test
  @DisplayName("文件到达扫描启动的文件束保留来源业务日并经 Worker 导入后结算")
  void shouldSettleSourceBizDate_whenFileArrivalLaunchesBundleWorkflow() {
    final String selectCountSql5 = """
        select count(*)::int from biz.customer_account where tenant_id = ? and customer_no like ?
    """;

    final String updateTriggerRequestSql = """
        update batch.trigger_request set request_status = 'REJECTED' where tenant_id = ? and request_id = ?\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (
            tenant_id, calendar_code, biz_date, day_status, open_at, cutoff_at
        ) values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp - interval '1 second')
        """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request
        where tenant_id = ? and workflow_definition_id = ? and biz_date = ?
          and request_id <> ?
        """;
    final String selectCountSql = """
          select count(*)::int from batch.job_task task
          join batch.job_instance instance
            on instance.tenant_id = task.tenant_id and instance.id = task.job_instance_id
          join batch.workflow_node_run node_run
            on node_run.job_instance_id = instance.id
          join batch.workflow_run run on run.id = node_run.workflow_run_id
          where run.tenant_id = ? and run.trigger_request_id = (
              select id from batch.trigger_request where tenant_id = ? and request_id = ?)
            and task.task_status = 'SUCCESS'
          """;
    final String selectRunStatusSql = """
          select run_status from batch.workflow_run
          where tenant_id = ? and trigger_request_id = (
              select id from batch.trigger_request where tenant_id = ? and request_id = ?)
          """;
    final String selectCountSql2 = """
        select count(*)::int as linked_job_count,
               count(*) filter (where trigger_request.biz_date is distinct from run.biz_date
                   or run.biz_date is distinct from instance.biz_date)::int as mismatch_count
        from batch.trigger_request
        join batch.workflow_run run
          on run.tenant_id = trigger_request.tenant_id
         and run.trigger_request_id = trigger_request.id
        join batch.workflow_node_run node_run on node_run.workflow_run_id = run.id
            and node_run.job_instance_id is not null
        join batch.job_instance instance
          on instance.tenant_id = trigger_request.tenant_id
         and instance.id = node_run.job_instance_id
        where trigger_request.tenant_id = ? and trigger_request.request_id = ?
        """;
    final String selectCountSql3 = """
        select count(*)::int from batch.file_record
        where tenant_id = ? and id in (?, ?) and biz_date = ?
          and metadata_json ->> 'arrivalState' = 'TRIGGERED'
        """;
    final String selectCountSql4 = """
        select count(*)::int from batch.expected_work_occurrence
        where tenant_id = ? and calendar_code = ? and workflow_definition_id = ?
          and source_type = 'FILE_GROUP' and source_key = ? and biz_date = ?
            and occurrence_status = 'SATISFIED'
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance
        where tenant_id = ? and calendar_code = ? and biz_date = ?
        """;

    long suffix = System.nanoTime();
    String suffixText = Long.toUnsignedString(suffix);
    String prefixA = "ARRIVAL" + suffixText + "A";
    String prefixB = "ARRIVAL" + suffixText + "B";
    String requiredFileSet = prefixA + ".json," + prefixB + ".json";
    String fileGroupCode = "E2E_FILE_GROUP_" + suffixText;
    String workflowCalendar = "e2e_calendar";
    LocalDate bizDate = LocalDate.of(2030, 1, 1).plusDays(Long.remainderUnsigned(suffix, 3000));
    LaunchSeed seed = E2eScenarioFixture.prepareBundleLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "import");
    jdbcTemplate.update(updateTriggerRequestSql, TENANT, seed.requestId());
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, workflowCalendar, bizDate);

    long fileA = insertBundleInput(prefixA, 1);
    long fileB = insertBundleInput(prefixB, 1);
    addArrivalBundleMetadata(
        fileA, prefixA + ".json", requiredFileSet, fileGroupCode, seed.jobCode(), bizDate);
    addArrivalBundleMetadata(
        fileB, prefixB + ".json", requiredFileSet, fileGroupCode, seed.jobCode(), bizDate);

    fileGovernanceScheduler.manageFileArrivalGroups();
    String requestId = jdbcTemplate.queryForObject(
        selectRequestIdSql,
        String.class,
        TENANT,
        seed.workflowDefinitionId(),
        bizDate,
        seed.requestId());
    assertThat(requestId).isNotBlank();
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Integer successfulTasks =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, TENANT, requestId);
          assertThat(successfulTasks).isEqualTo(2);
          String selectedRunStatus = jdbcTemplate.queryForObject(
              selectRunStatusSql, String.class, TENANT, TENANT, requestId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
        });

    Map.Entry<Integer, Integer> datePropagationCounts = jdbcTemplate.queryForObject(
        selectCountSql2,
        (rs, rowNum) -> Map.entry(rs.getInt("linked_job_count"), rs.getInt("mismatch_count")),
        TENANT,
        requestId);
    assertThat(datePropagationCounts.getKey()).isPositive();
    assertThat(datePropagationCounts.getValue()).isZero();
    Integer selectedCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, fileA, fileB, bizDate);
    assertThat(selectedCount).isEqualTo(2);
    Integer selectedCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql4,
        Integer.class,
        TENANT,
        workflowCalendar,
        seed.workflowDefinitionId(),
        fileGroupCode,
        bizDate);
    assertThat(selectedCountRead2).isEqualTo(1);

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    Integer selectedCountRead3 =
        businessJdbc.queryForObject(selectCountSql5, Integer.class, TENANT, prefixA + "%");
    assertThat(selectedCountRead3).isEqualTo(1);
    Integer selectedCountRead4 =
        businessJdbc.queryForObject(selectCountSql5, Integer.class, TENANT, prefixB + "%");
    assertThat(selectedCountRead4).isEqualTo(1);

    batchDaySettleScheduler.settle();
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, workflowCalendar, bizDate);
    assertThat(selectedDayStatus).isEqualTo("SETTLED");
  }

  private void addArrivalBundleMetadata(
      long fileId,
      String fileName,
      String requiredFileSet,
      String fileGroupCode,
      String workflowCode,
      LocalDate bizDate) {
    final String updateFileRecordSql = """
        update batch.file_record
        set biz_date = ?, created_at = timestamp '1900-01-01 00:00:00', metadata_json = ?::jsonb
        where tenant_id = ? and id = ? and file_name = ?
        """;

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("fileGroupCode", fileGroupCode);
    metadata.put("waitFileGroupMode", "ALL_OF");
    metadata.put("requiredFileSet", requiredFileSet);
    metadata.put("arrivalTimeoutAction", "MANUAL_CONFIRM");
    metadata.put("triggerOnComplete", true);
    metadata.put("integrityState", "VERIFIED");
    metadata.put("bundleWorkflowCode", workflowCode);
    metadata.put("bundleTemplateCode", "IMP-CUSTOMER-JSON-ARRAY");
    jdbcTemplate.update(
        updateFileRecordSql, bizDate, JsonUtils.toJson(metadata), TENANT, fileId, fileName);
  }

  private long insertBundleInput(String prefix, int rows) {
    final String insertFileRecordSql = """
            insert into batch.file_record (
                tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
                file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
                storage_bucket, storage_path, source_type, file_status, biz_date, trace_id
            ) values (?, ?, 'CUSTOMER', 'INPUT', ?, ?, 'json', 'JSON', 'UTF-8', 'application/json',
                      ?, 'NONE', 'S3', ?, ?, 'SYSTEM', 'RECEIVED', date '2026-01-15', ?)
            returning id
            """;

    String json = IntStream.rangeClosed(1, rows)
        .mapToObj(index -> "{\"customerNo\":\"" + prefix + index
            + "\",\"customerName\":\"Bundle customer\",\"customerType\":\"PERSONAL\","
            + "\"certificateNo\":\"ID-" + prefix + index + "\",\"status\":\"ACTIVE\"}")
        .collect(Collectors.joining(",", "[", "]"));
    byte[] content = json.getBytes(StandardCharsets.UTF_8);
    String key = "ingress/" + TENANT + "/" + prefix + ".json";
    ensureBucket(s3Bucket());
    putObject(s3Bucket(), key, content);
    Long fileId = jdbcTemplate.queryForObject(
        insertFileRecordSql,
        Long.class,
        TENANT,
        prefix,
        prefix + ".json",
        prefix + ".json",
        content.length,
        s3Bucket(),
        key,
        "e2e-tr-bundle-import");
    assertThat(fileId).isNotNull();
    return fileId;
  }
}
