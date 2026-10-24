package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eProcessApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eStatusLogger;
import io.github.pinpols.batch.e2e.support.ProcessE2eFixture;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.application.archive.WorkflowArchiveService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.processes.cleanup.ProcessStagingGenerationCandidate;
import io.github.pinpols.batch.worker.processes.cleanup.ProcessStagingGenerationCleanupFence;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.stage.ProcessComputePlugin;
import io.github.pinpols.batch.worker.processes.stage.ProcessRuntimeKeys;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 端到端测试：PROCESS 主链路成功闭环（WAP+bookends 完整 5 stage）。
 *
 * <p>链路路径：
 *
 * <pre>
 * launch → orchestrator 调度/落 outbox → Kafka 派发 → process worker claim →
 *     PREPARE → COMPUTE（写 staging）→ VALIDATE → COMMIT（原子写 target）→ FEEDBACK（清 staging + 上报水位）
 *     → worker report → orchestrator 终态
 * </pre>
 *
 * <p>本类只覆盖成功路径（sqlTransformCompute 与自定义 plugin）；失败路径（validation 失败）见 {@link
 * ProcessFailurePipelineE2eIT}。
 */
@E2eSpringBootTest(
    classes = E2eProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.process.worker-type=PROCESS",
      "batch.retry.poll-interval-millis=600000",
      "batch.outbox.poll-interval-millis=600000",
      "batch.job-instance.archive.enabled=true",
      "batch.job-instance.archive.retention-days=1",
      "batch.job-instance.archive.batch-size=1000",
      "batch.workflow.archive.enabled=true",
      "batch.workflow.archive.retention-days=1",
      "batch.workflow.archive.batch-size=1000"
    })
@E2eBusinessSchema
@Import(ProcessPipelineE2eIT.ProcessE2eTestConfiguration.class)
@Tag("smoke")
@DisplayName("PROCESS 主链路成功闭环端到端:发起经派发与认领进入五阶段流水线,目标表写入正确且暂存清空,实例与任务成功收敛")
class ProcessPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String CUSTOM_PLUGIN_CODE = "e2eProcessCompute";
  // d60c9ad4 起 updateHighWaterMarkOut 加 numeric CAS 守护(value 必须匹配 ^-?[0-9]+(\.[0-9]+)?$),
  // 否则静默 no-op 防御非数字回报。测试 watermark 用 YYYYMMDD 数字形式,语义对齐 bizDate=2026-01-15。
  private static final String CUSTOM_PLUGIN_WATERMARK = "20260115";

  private final WorkflowRunLaunchService launchService;
  private final JdbcTemplate jdbcTemplate;
  private final DataSource businessDataSource;
  private final E2eOutboxPublishSupport e2eOutboxPublishSupport;
  private final ObjectMapper objectMapper;
  private final RetryGovernanceService retryGovernanceService;
  private final InstanceManagementApplicationService instanceManagementApplicationService;
  private final WorkflowRunManagementApplicationService workflowRunManagementApplicationService;
  private final SuccessInstanceArchiveService successInstanceArchiveService;
  private final WorkflowArchiveService workflowArchiveService;
  private final ProcessStagingGenerationCleanupFence stagingGenerationCleanupFence;

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  @MockitoSpyBean
  private TaskControllerApplicationService taskControllerApplicationService;

  @Autowired
  ProcessPipelineE2eIT(
      WorkflowRunLaunchService launchService,
      JdbcTemplate jdbcTemplate,
      @Qualifier("processBusinessDataSource") DataSource businessDataSource,
      E2eOutboxPublishSupport e2eOutboxPublishSupport,
      ObjectMapper objectMapper,
      RetryGovernanceService retryGovernanceService,
      InstanceManagementApplicationService instanceManagementApplicationService,
      WorkflowRunManagementApplicationService workflowRunManagementApplicationService,
      SuccessInstanceArchiveService successInstanceArchiveService,
      WorkflowArchiveService workflowArchiveService,
      ProcessStagingGenerationCleanupFence stagingGenerationCleanupFence) {
    this.launchService = launchService;
    this.jdbcTemplate = jdbcTemplate;
    this.businessDataSource = businessDataSource;
    this.e2eOutboxPublishSupport = e2eOutboxPublishSupport;
    this.objectMapper = objectMapper;
    this.retryGovernanceService = retryGovernanceService;
    this.instanceManagementApplicationService = instanceManagementApplicationService;
    this.workflowRunManagementApplicationService = workflowRunManagementApplicationService;
    this.successInstanceArchiveService = successInstanceArchiveService;
    this.workflowArchiveService = workflowArchiveService;
    this.stagingGenerationCleanupFence = stagingGenerationCleanupFence;
  }

  @Test
  @DisplayName("内置转换主链路成功:五个阶段全部成功,目标表行数与金额正确,暂存区清空,水位推进到预期值")
  void wap_sqlTransform_publishesTargetAndCleansStaging() throws Exception {
    final String selectCountSql2 = """
        select count(*)::int from biz.process_account_summary where biz_date = date '2026-01-15'\
        """;
    final String selectTotalAmountSql = """
        select total_amount::text from biz.process_account_summary where account_id='A'\s\
        and biz_date = date '2026-01-15'\
        """;
    final String selectCountSql3 = """
        select count(*)::int from batch.process_staging
    """;

    final String selectCountSql = """
        select count(*)::int from batch.pipeline_step_run where pipeline_instance_id=?
    """;
    final String selectOutputSummarySql = """
            select output_summary ->> 'stagedCount'
            from batch.pipeline_step_run
            where pipeline_instance_id = ? and stage_code = 'COMPUTE'
            """;
    final String selectOutputSummarySql2 = """
            select output_summary ->> 'publishedCount'
            from batch.pipeline_step_run
            where pipeline_instance_id = ? and stage_code = 'COMMIT'
            """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT);
    ProcessE2eFixture.configureSqlTransformJobParameters(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode(), List.of());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-process-wap",
        params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          E2eStatusLogger.logJobFlowSnapshot(
              jdbcTemplate, TENANT, seed.dedupKey(), "ProcessE2e-wap-happy");
          Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
          assertThat(outcome.get("task_status")).isEqualTo("SUCCESS");
          assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS");
          assertThat(outcome.get("high_water_mark_out")).isEqualTo("3");
        });

    // Target 表写入正确
    Integer selectedCount = businessJdbc.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedCount).isEqualTo(2);
    String selectedTotalAmount = businessJdbc.queryForObject(selectTotalAmountSql, String.class);
    assertThat(selectedTotalAmount).isEqualTo("30.00");

    // staging 在 FEEDBACK 已清空
    Integer selectedCountRead2 = businessJdbc.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedCountRead2).isZero();

    // 5 个 pipeline_step_run 全部 SUCCESS
    Long pipelineInstanceId = loadPipelineInstanceId(seed.dedupKey());
    Integer selectedCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, pipelineInstanceId);
    assertThat(selectedCountRead3).isEqualTo(5);
    String stagedCount = jdbcTemplate.queryForObject(selectOutputSummarySql, String.class, pipelineInstanceId);
    assertThat(stagedCount).isEqualTo("2");
    String publishedCount = jdbcTemplate.queryForObject(selectOutputSummarySql2, String.class, pipelineInstanceId);
    assertThat(publishedCount).isEqualTo("2");
  }

  @Test
  @DisplayName("PROCESS 派发消息重复投递时,生产认领栅栏阻止流水线和业务副作用重复执行")
  void shouldExecuteProcessSideEffectsOnce_whenDispatchMessageIsRedelivered() throws Exception {
    final String selectCountSql2 = """
        select count(*)::int from biz.process_account_summary where biz_date = date '2026-01-15'\
        """;
    final String selectTotalAmountSql = """
        select total_amount::text from biz.process_account_summary where account_id='A'\s\
        and biz_date = date '2026-01-15'\
        """;
    final String selectCountSql3 = """
        select count(*)::int from batch.process_staging
    """;

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
    final String selectCountSql = """
        select count(*)::int from batch.pipeline_step_run where pipeline_instance_id = ?\s\
        and step_status = 'SUCCESS'\
        """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT);
    ProcessE2eFixture.configureSqlTransformJobParameters(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode(), List.of());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-process-redelivery",
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
        BatchTopics.TASK_DISPATCH_PROCESS, message.selectedWorkerId());
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
          Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
          assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS");
          assertThat(outcome.get("high_water_mark_out")).isEqualTo("3");
        });

    Integer selectedCount = businessJdbc.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedCount).isEqualTo(2);
    String selectedTotalAmount = businessJdbc.queryForObject(selectTotalAmountSql, String.class);
    assertThat(selectedTotalAmount).isEqualTo("30.00");
    Integer selectedCountRead2 = businessJdbc.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedCountRead2).isZero();
    Long pipelineInstanceId = loadPipelineInstanceId(seed.dedupKey());
    Integer selectedCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, pipelineInstanceId);
    assertThat(selectedCountRead3).isEqualTo(5);
  }

  @Test
  @DisplayName("PROCESS 成功运行树真实归档到冷表后,清理器仍按冷表当前代保护并只回收旧代")
  void archivedProcessTask_cleanupUsesColdCurrentGeneration() throws Exception {
    final String insertProcessStagingGenerationSql = """
        insert into batch.process_staging_generation
          (generation_id, tenant_id, job_task_id, batch_key, generation_status, created_at)
        values (?, ?, ?, ?, 'OPEN', now() - interval '3 days')
        """;
    final String insertProcessStagingSql = """
        insert into batch.process_staging
          (batch_key, staging_generation_id, tenant_id, target_schema, target_table, payload,
           staged_at)
        values (?, ?, ?, 'biz', 'process_account_summary', '{}'::jsonb,
                now() - interval '2 days')
        """;
    final String selectGenerationStatusSql = """
            select generation_status from batch.process_staging_generation
            where tenant_id = ? and generation_id = ? and batch_key = ?
            """;
    final String selectCountSql2 = """
            select count(*)::int from batch.process_staging
            where tenant_id = ? and staging_generation_id = ? and batch_key = ?
            """;

    final String selectIdSql = """
        select instance.id as instance_id, task.id as task_id,
               task.process_staging_generation_id::text as generation_id,
               task.process_staging_batch_key as batch_key
        from batch.job_instance instance
        join batch.job_task task on task.tenant_id = instance.tenant_id
            and task.job_instance_id = instance.id
        where instance.tenant_id = ? and instance.dedup_key = ?
        """;
    final String updateJobInstanceSql = """
        update batch.job_instance set finished_at = now() - interval '2 days' where tenant_id = ? and id = ?\s\
        and instance_status = 'SUCCESS'\
        """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run workflow
        set finished_at = now() - interval '2 days'
        where workflow.tenant_id = ?
          and workflow.run_status in ('SUCCESS', 'FAILED', 'TERMINATED',
                                      'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN')
          and (
            workflow.related_job_instance_id = ?
            or exists (
              select 1 from batch.workflow_node_run node_run
              where node_run.workflow_run_id = workflow.id
                and node_run.job_instance_id = ?
            )
          )
        """;
    final String selectCountSql = """
        select count(*) from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectProcessStagingGenerationIdSql = """
        select process_staging_generation_id::text from archive.job_task_archive where tenant_id = ? and id = ?\
        """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT);
    ProcessE2eFixture.configureSqlTransformJobParameters(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode(), List.of());

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-process-archive-cleanup",
        Map.of("bizDate", "2026-01-15")));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
          assertThat(outcome.get("task_status")).isEqualTo("SUCCESS");
          assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS");
        });

    Map<String, Object> identity = jdbcTemplate.queryForMap(selectIdSql, TENANT, seed.dedupKey());
    Long instanceId = ((Number) identity.get("instance_id")).longValue();
    Long taskId = ((Number) identity.get("task_id")).longValue();
    String currentGeneration = (String) identity.get("generation_id");
    String batchKey = (String) identity.get("batch_key");
    assertThat(currentGeneration).isNotBlank();
    assertThat(batchKey).isNotBlank();

    UUID staleGeneration = UUID.randomUUID();
    businessJdbc.update(insertProcessStagingGenerationSql, staleGeneration, TENANT, taskId, batchKey);
    businessJdbc.update(insertProcessStagingSql, batchKey, staleGeneration, TENANT);
    jdbcTemplate.update(
        updateJobInstanceSql,
        TENANT,
        instanceId);
    int agedWorkflowRuns = jdbcTemplate.update(updateWorkflowRunSql, TENANT, instanceId, instanceId);
    assertThat(agedWorkflowRuns).isGreaterThan(0);

    WorkflowArchiveService.ArchiveBatchResult workflowArchiveResult =
        workflowArchiveService.archiveOnce();
    assertThat(workflowArchiveResult.workflowRunsDeleted()).isGreaterThan(0);

    SuccessInstanceArchiveService.ArchiveBatchResult archiveResult =
        successInstanceArchiveService.archiveOnce();
    assertThat(archiveResult.executed()).isTrue();
    assertThat(archiveResult.candidates())
        .as("expired successful PROCESS instance should be selected for archive: %s", archiveResult)
        .isGreaterThan(0);
    Integer selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, taskId);
    assertThat(selectedJobTaskCount).isZero();
    String selectedProcessStagingGenerationId = jdbcTemplate.queryForObject(
        selectProcessStagingGenerationIdSql, String.class, TENANT, taskId);
    assertThat(selectedProcessStagingGenerationId).isEqualTo(currentGeneration);

    int deleted = stagingGenerationCleanupFence.reclaimIfStale(
        new ProcessStagingGenerationCandidate(TENANT, taskId, staleGeneration.toString(), batchKey),
        100);

    assertThat(deleted).isEqualTo(1);
    String selectedProcessStagingGenerationIdRead2 = jdbcTemplate.queryForObject(
        selectProcessStagingGenerationIdSql, String.class, TENANT, taskId);
    assertThat(selectedProcessStagingGenerationIdRead2).isEqualTo(currentGeneration);
    String selectedGenerationStatus = businessJdbc.queryForObject(
        selectGenerationStatusSql, String.class, TENANT, staleGeneration, batchKey);
    assertThat(selectedGenerationStatus).isEqualTo("RECLAIMED");
    Integer selectedCount = businessJdbc.queryForObject(
        selectCountSql2, Integer.class, TENANT, staleGeneration, batchKey);
    assertThat(selectedCount).isZero();
  }

  @Test
  @DisplayName("PROCESS retry 由新 invocation 生成新 staging generation,旧代不混入本次提交")
  void retryAfterCommitFailure_rotatesGenerationAndPublishesOnlyCurrentAttempt() throws Exception {
    final String selectCountSql2 = """
              select count(*)::int from batch.process_staging
              where tenant_id = ? and staging_generation_id = cast(? as uuid)
              """;
    final String selectCountSql3 = """
        select count(*)::int from biz.process_account_summary
    """;
    final String selectTotalAmountSql = """
              select total_amount::text from biz.process_account_summary
              where tenant_id = ? and account_id = 'A' and biz_date = date '2026-01-15'
              """;

    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ? where tenant_id = ?\s\
        and job_code = ?\
        """;
    final String selectIdSql = """
          select task.id from batch.job_task task
          join batch.job_instance instance on instance.tenant_id = task.tenant_id
              and instance.id = task.job_instance_id
          where instance.tenant_id = ? and instance.dedup_key = ?
          """;
    final String selectCurrentInvocationIdSql = """
                select partition.current_invocation_id
                from batch.job_task task
                join batch.job_instance instance on instance.tenant_id = task.tenant_id
                    and instance.id = task.job_instance_id
                join batch.job_partition partition on partition.tenant_id = task.tenant_id
                    and partition.job_instance_id = task.job_instance_id
                    and partition.id = task.job_partition_id
                where instance.tenant_id = ? and instance.dedup_key = ?
                """;
    final String selectProcessStagingGenerationIdSql = """
        select process_staging_generation_id::text from batch.job_task where tenant_id = ? and id = ?\
        """;
    final String selectCurrentInvocationIdSql2 = """
          select partition.current_invocation_id
          from batch.job_task task
          join batch.job_instance instance on instance.tenant_id = task.tenant_id
              and instance.id = task.job_instance_id
          join batch.job_partition partition on partition.tenant_id = task.tenant_id
              and partition.job_instance_id = task.job_instance_id
              and partition.id = task.job_partition_id
          where instance.tenant_id = ? and instance.dedup_key = ?
          """;
    final String selectProcessStagingGenerationIdSql2 = """
        select process_staging_generation_id::text from batch.job_task where tenant_id = ? and id = ?\
        """;
    final String selectCountSql = """
                select count(*) from batch.retry_schedule rs
                join batch.job_partition partition on partition.tenant_id = rs.tenant_id
                    and partition.id = rs.related_id
                join batch.job_instance instance on instance.tenant_id = partition.tenant_id
                    and instance.id = partition.job_instance_id
                where instance.tenant_id = ? and instance.dedup_key = ?
                    and rs.related_type = 'JOB_PARTITION' and rs.retry_status = 'WAITING'
                """;
    final String selectTaskStatusSql = """
                select task.task_status from batch.job_task task
                join batch.job_instance instance on instance.tenant_id = task.tenant_id
                    and instance.id = task.job_instance_id
                where instance.tenant_id = ? and instance.dedup_key = ?
                """;
    final String selectPartitionStatusSql = """
                select partition.partition_status
                from batch.job_partition partition
                join batch.job_instance instance on instance.tenant_id = partition.tenant_id
                    and instance.id = partition.job_instance_id
                where instance.tenant_id = ? and instance.dedup_key = ?
                """;
    final String selectIdSql2 = """
        select id from batch.job_partition where tenant_id = ? and job_instance_id = (select id\s\
        from batch.job_instance where tenant_id = ? and dedup_key = ?)\
        """;
    final String updateRetryScheduleSql = """
          update batch.retry_schedule rs set next_retry_at = now() - interval '1 second'
          where rs.tenant_id = ? and rs.related_type = 'JOB_PARTITION'
              and rs.related_id = ? and rs.retry_status = 'WAITING'
          """;
    final String selectPayloadJsonSql = """
          select payload_json::text
          from batch.outbox_event
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
          order by id desc
          limit 1
          """;
    final String selectCurrentInvocationIdSql3 = """
        select current_invocation_id from batch.job_partition where tenant_id = ? and id = ?
    """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    String suffix = Long.toUnsignedString(System.nanoTime());
    String triggerName = "e2e_process_retry_fail_" + suffix;
    String functionName = "fail_process_retry_" + suffix;
    AtomicReference<Long> observedRetryTaskId = new AtomicReference<>();
    AtomicInteger retryClaimAttempts = new AtomicInteger();
    doAnswer(invocation -> {
          if (observedRetryTaskId.get() != null
              && observedRetryTaskId.get().equals(invocation.getArgument(0))) {
            retryClaimAttempts.incrementAndGet();
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT);
    ProcessE2eFixture.configureSqlTransformJobParameters(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode(), List.of());
    jdbcTemplate.update(
        updateJobDefinitionSql,
        "FIXED",
        1,
        TENANT,
        seed.jobCode());
    final String createFunctionSql = """
        create function biz.%s() returns trigger language plpgsql as $$
        begin
          perform pg_sleep(2);
          raise exception 'e2e transient PROCESS commit failure' using errcode = '40001';
        end
        $$
        """.formatted(functionName);
    businessJdbc.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on biz.process_account_summary
        for each row execute function biz.%s()
        """.formatted(triggerName, functionName);
    businessJdbc.execute(createTriggerSql);

    try {
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-tr-process-retry-" + suffix,
          Map.of("bizDate", "2026-01-15")));
      Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      observedRetryTaskId.set(taskId);
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            String selectedCurrentInvocationId = jdbcTemplate.queryForObject(
                selectCurrentInvocationIdSql, String.class, TENANT, seed.dedupKey());
            assertThat(selectedCurrentInvocationId).isNotBlank();
            String selectedProcessStagingGenerationId = jdbcTemplate.queryForObject(
                selectProcessStagingGenerationIdSql, String.class, TENANT, taskId);
            assertThat(selectedProcessStagingGenerationId).isNotBlank();
          });
      String firstInvocationId =
      jdbcTemplate.queryForObject(selectCurrentInvocationIdSql2, String.class, TENANT, seed.dedupKey());
      String firstGenerationId = jdbcTemplate.queryForObject(
          selectProcessStagingGenerationIdSql2,
          String.class,
          TENANT,
          taskId);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            Integer waitingRetries =
            jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
            assertThat(waitingRetries).isEqualTo(1);
            String selectedTaskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, seed.dedupKey());
            assertThat(selectedTaskStatus).isEqualTo("FAILED");
            String selectedPartitionStatus = jdbcTemplate.queryForObject(
                selectPartitionStatusSql, String.class, TENANT, seed.dedupKey());
            assertThat(selectedPartitionStatus).isEqualTo("RETRYING");
          });
      assertThat(retryClaimAttempts).hasValue(1);

      Long partitionId = jdbcTemplate.queryForObject(
          selectIdSql2,
          Long.class,
          TENANT,
          TENANT,
          seed.dedupKey());
      assertThat(firstInvocationId).isNotBlank();
      assertThat(firstGenerationId).isNotBlank();
      Integer selectedCount =
          businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, firstGenerationId);
      assertThat(selectedCount).isEqualTo(2);
      Integer selectedCountRead2 = businessJdbc.queryForObject(selectCountSql3, Integer.class);
      assertThat(selectedCountRead2).isZero();

      final String dropTriggerSql = """
          drop trigger %s on biz.process_account_summary\
          """.formatted(triggerName);
      businessJdbc.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function biz.%s()\
          """.formatted(functionName);
      businessJdbc.execute(dropFunctionSql);
      final String retryCreateFunctionSql = """
          create function biz.%s() returns trigger language plpgsql as $$
          begin
            perform pg_sleep(2);
            return new;
          end
          $$
          """.formatted(functionName);
      businessJdbc.execute(retryCreateFunctionSql);
      final String retryCreateTriggerSql = """
          create trigger %s before insert on biz.process_account_summary
          for each row execute function biz.%s()
          """.formatted(triggerName, functionName);
      businessJdbc.execute(retryCreateTriggerSql);
      int affectedRetryScheduleRows =
          jdbcTemplate.update(updateRetryScheduleSql, TENANT, partitionId);
      assertThat(affectedRetryScheduleRows).isEqualTo(1);
      retryGovernanceService.dispatchDueRetries();
      String retryDispatchPayload = jdbcTemplate.queryForObject(selectPayloadJsonSql, String.class, TENANT, taskId);
      TaskDispatchMessage retryDispatch =
          JsonUtils.fromJson(retryDispatchPayload, TaskDispatchMessage.class);
      assertThat(retryDispatch).isNotNull();
      observedRetryTaskId.set(taskId);
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            String currentInvocationId = jdbcTemplate.queryForObject(
                selectCurrentInvocationIdSql3,
                String.class,
                TENANT,
                partitionId);
            assertThat(currentInvocationId).isNotBlank().isNotEqualTo(firstInvocationId);
          });
      String retriedInvocationId = jdbcTemplate.queryForObject(
          selectCurrentInvocationIdSql3,
          String.class,
          TENANT,
          partitionId);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
            assertThat(outcome.get("task_status")).isEqualTo("SUCCESS");
            assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS");
          });
      assertThat(retryClaimAttempts).hasValue(2);

      String retryDispatchTopic = BatchTopics.directDispatchTopic(
          BatchTopics.TASK_DISPATCH_PROCESS, retryDispatch.selectedWorkerId());
      String retryDispatchKey = TENANT + ":" + retryDispatch.jobCode() + ":"
          + retryDispatch.instanceNo() + ":task:" + retryDispatch.taskId();
      kafkaTemplate
          .send(retryDispatchTopic, retryDispatchKey, retryDispatchPayload)
          .get();
      kafkaTemplate
          .send(retryDispatchTopic, retryDispatchKey, retryDispatchPayload)
          .get();
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> assertThat(retryClaimAttempts).hasValue(4));

      Long retriedTaskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      String retriedGenerationId = jdbcTemplate.queryForObject(
          selectProcessStagingGenerationIdSql2,
          String.class,
          TENANT,
          retriedTaskId);
      assertThat(retriedInvocationId).isNotEqualTo(firstInvocationId);
      assertThat(retriedGenerationId).isNotEqualTo(firstGenerationId);
      Integer selectedCountRead3 =
          businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, firstGenerationId);
      assertThat(selectedCountRead3).isEqualTo(2);
      Integer selectedCountRead4 =
          businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, retriedGenerationId);
      assertThat(selectedCountRead4).isZero();
      Integer selectedCountRead5 = businessJdbc.queryForObject(selectCountSql3, Integer.class);
      assertThat(selectedCountRead5).isEqualTo(2);
      String selectedTotalAmount =
          businessJdbc.queryForObject(selectTotalAmountSql, String.class, TENANT);
      assertThat(selectedTotalAmount).isEqualTo("30.00");
      assertThat(loadProcessOutcome(seed.dedupKey()))
          .containsEntry("task_status", "SUCCESS")
          .containsEntry("instance_status", "SUCCESS")
          .containsEntry("high_water_mark_out", "3");
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists %s on biz.process_account_summary\
          """.formatted(triggerName);
      businessJdbc.execute(
          dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists biz.%s()\
          """.formatted(functionName);
      businessJdbc.execute(dropFunctionSql);
    }
  }

  @Test
  @DisplayName("父 Workflow 仍活跃时 PROCESS 手工重试轮换 generation 并恢复原分区")
  void manualRetryWithActiveSibling_rotatesGenerationAndPublishesOnlyCurrentAttempt()
      throws Exception {
        final String selectCountSql2 = """
              select count(*)::int from batch.process_staging
              where tenant_id = ? and staging_generation_id = cast(? as uuid)
              """;
        final String selectCountSql3 = """
            select count(*)::int from biz.process_account_summary
        """;
        final String selectCountSql4 = """
              select count(*)::int from biz.process_account_summary
              where tenant_id = ? and account_id = 'A' and biz_date = date '2026-01-15'
              """;
        final String selectTotalAmountSql = """
              select total_amount::text from biz.process_account_summary
              where tenant_id = ? and account_id = 'B' and biz_date = date '2026-01-15'
              """;

        final String updateJobDefinitionSql = """
            update batch.job_definition set retry_policy = 'NONE', retry_max_count = 0 where tenant_id = ?\s\
            and job_code = ?\
            """;
        final String selectIdSql = """
          select task.id from batch.job_task task
          join batch.job_instance instance on instance.tenant_id = task.tenant_id
              and instance.id = task.job_instance_id
          join batch.job_partition partition on partition.tenant_id = task.tenant_id
              and partition.job_instance_id = task.job_instance_id
              and partition.id = task.job_partition_id
          where instance.tenant_id = ? and instance.dedup_key = ?
          order by partition.partition_no
          """;
        final String updateOutboxEventSql = """
          update batch.outbox_event set next_publish_at = now() + interval '1 day'
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
              and publish_status in ('NEW', 'FAILED')
          """;
        final String selectCurrentInvocationIdSql = """
                select partition.current_invocation_id
                from batch.job_task task
                join batch.job_partition partition on partition.tenant_id = task.tenant_id
                    and partition.job_instance_id = task.job_instance_id
                    and partition.id = task.job_partition_id
                where task.tenant_id = ? and task.id = ?
                """;
        final String selectProcessStagingGenerationIdSql = """
            select process_staging_generation_id::text from batch.job_task where tenant_id = ? and id = ?\
            """;
        final String selectCurrentInvocationIdSql2 = """
          select partition.current_invocation_id
          from batch.job_task task
          join batch.job_partition partition on partition.tenant_id = task.tenant_id
              and partition.job_instance_id = task.job_instance_id
              and partition.id = task.job_partition_id
          where task.tenant_id = ? and task.id = ?
          """;
        final String selectProcessStagingGenerationIdSql2 = """
            select process_staging_generation_id::text from batch.job_task where tenant_id = ? and id = ?\
            """;
        final String selectTaskStatusSql = """
            select task_status from batch.job_task where tenant_id = ? and id = ?
        """;
        final String selectCountSql = """
                select count(*) from batch.job_partition partition
                join batch.job_instance instance on instance.tenant_id = partition.tenant_id
                    and instance.id = partition.job_instance_id
                where instance.tenant_id = ? and instance.dedup_key = ?
                    and partition.partition_status = 'READY'
                """;
        final String selectInstanceStatusSql = """
                select instance.instance_status from batch.job_instance instance
                where instance.tenant_id = ? and instance.dedup_key = ?
                """;
        final String selectRunStatusSql = """
                select run.run_status from batch.workflow_node_run node
                join batch.workflow_run run on run.id = node.workflow_run_id
                join batch.job_instance instance on instance.tenant_id = run.tenant_id
                    and instance.id = node.job_instance_id
                where instance.tenant_id = ? and instance.dedup_key = ?
                """;
        final String selectJobPartitionIdSql = """
            select job_partition_id from batch.job_task where tenant_id = ? and id = ?
        """;
        final String selectCurrentInvocationIdSql3 = """
                select partition.current_invocation_id
                from batch.job_partition partition
                where partition.tenant_id = ? and partition.id = ?
                """;
        final String selectCurrentInvocationIdSql4 = """
          select current_invocation_id from batch.job_partition
          where tenant_id = ? and id = ?
          """;
        final String selectInstanceStatusSql2 = """
          select instance.instance_status from batch.job_instance instance
          where instance.tenant_id = ? and instance.dedup_key = ?
          """;
        final String selectIdSql2 = """
          select id from batch.job_instance
          where tenant_id = ? and dedup_key = ?
          """;
        final String selectInstanceStatusSql3 = """
            select instance_status from batch.job_instance where tenant_id = ? and id = ?
        """;
        final String selectIdSql3 = """
          select run.id from batch.workflow_node_run node
          join batch.workflow_run run on run.id = node.workflow_run_id
          join batch.job_instance instance on instance.tenant_id = run.tenant_id
              and instance.id = node.job_instance_id
          where instance.tenant_id = ? and instance.dedup_key = ?
          """;
        final String selectRunStatusSql2 = """
            select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;
        final String selectProcessStagingGenerationIdSql3 = """
            select process_staging_generation_id::text from batch.job_task where tenant_id = ? and id = ?\
            """;
        final String updateOutboxEventSql2 = """
          update batch.outbox_event set next_publish_at = now()
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
              and publish_status in ('NEW', 'FAILED')
          """;
        final String selectInstanceStatusSql4 = """
                select instance_status from batch.job_instance
                where tenant_id = ? and dedup_key = ?
                """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    String suffix = Long.toUnsignedString(System.nanoTime());
    String triggerName = "e2e_process_manual_retry_fail_" + suffix;
    String functionName = "fail_process_manual_retry_" + suffix;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        new E2eScenarioFixture.LaunchPreparationSpec(
                jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API)
            .shardStrategy("DYNAMIC"));
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT);
    ProcessE2eFixture.configurePartitionedSqlTransformJobParameters(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode());
        jdbcTemplate.update(
        updateJobDefinitionSql,
        TENANT,
        seed.jobCode());
    final String createFunctionSql = """
        create function biz.%s() returns trigger language plpgsql as $$
        begin
          if new.account_id = 'A' then
            perform pg_sleep(2);
            raise exception 'e2e transient PROCESS manual retry failure' using errcode = '40001';
          end if;
          return new;
        end
        $$
        """.formatted(functionName);
    businessJdbc.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on biz.process_account_summary
        for each row execute function biz.%s()
        """.formatted(triggerName, functionName);
    businessJdbc.execute(createTriggerSql);

    try {
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-tr-process-manual-retry-" + suffix,
          Map.of(
              "bizDate", "2026-01-15",
              "estimatedItemCount", 2,
              "targetItemsPerPartition", 1)));
      List<Long> taskIds = jdbcTemplate.queryForList(selectIdSql, Long.class, TENANT, seed.dedupKey());
      assertThat(taskIds).hasSize(2);
      Long failedTaskId = taskIds.getFirst();
      Long delayedTaskId = taskIds.get(1);
      int affectedOutboxEventRows = jdbcTemplate.update(updateOutboxEventSql, TENANT, delayedTaskId);
      assertThat(affectedOutboxEventRows).isEqualTo(1);
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            String selectedCurrentInvocationId = jdbcTemplate.queryForObject(
                selectCurrentInvocationIdSql, String.class, TENANT, failedTaskId);
            assertThat(selectedCurrentInvocationId).isNotBlank();
            String selectedProcessStagingGenerationId = jdbcTemplate.queryForObject(
                selectProcessStagingGenerationIdSql, String.class, TENANT, failedTaskId);
            assertThat(selectedProcessStagingGenerationId).isNotBlank();
          });
      String firstInvocationId =
      jdbcTemplate.queryForObject(selectCurrentInvocationIdSql2, String.class, TENANT, failedTaskId);
      String firstGenerationId = jdbcTemplate.queryForObject(
          selectProcessStagingGenerationIdSql2,
          String.class,
          TENANT,
          failedTaskId);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(150))
          .untilAsserted(() -> {
            String selectedTaskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, failedTaskId);
            assertThat(selectedTaskStatus).isEqualTo("FAILED");
            Integer selectedJobPartitionCount =
                jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
            assertThat(selectedJobPartitionCount).isEqualTo(1);
            String selectedInstanceStatus =
                jdbcTemplate.queryForObject(selectInstanceStatusSql, String.class, TENANT, seed.dedupKey());
            assertThat(selectedInstanceStatus).isEqualTo("RUNNING");
            String selectedRunStatus =
                jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, seed.dedupKey());
            assertThat(selectedRunStatus).isEqualTo("RUNNING");
          });

      assertThat(firstInvocationId).isNotBlank();
      assertThat(firstGenerationId).isNotBlank();
      Integer selectedCount =
          businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, firstGenerationId);
      assertThat(selectedCount).isEqualTo(1);
      Integer selectedCountRead2 = businessJdbc.queryForObject(selectCountSql3, Integer.class);
      assertThat(selectedCountRead2).isZero();

      Long failedPartitionId = jdbcTemplate.queryForObject(
          selectJobPartitionIdSql,
          Long.class,
          TENANT,
          failedTaskId);
      final String dropTriggerSql = """
          drop trigger %s on biz.process_account_summary\
          """.formatted(triggerName);
      businessJdbc.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function biz.%s()\
          """.formatted(functionName);
      businessJdbc.execute(dropFunctionSql);
      final String retryCreateFunctionSql = """
          create function biz.%s() returns trigger language plpgsql as $$
          begin
            perform pg_sleep(2);
            return new;
          end
          $$
          """.formatted(functionName);
      businessJdbc.execute(retryCreateFunctionSql);
      final String retryCreateTriggerSql = """
          create trigger %s before insert on biz.process_account_summary
          for each row execute function biz.%s()
          """.formatted(triggerName, functionName);
      businessJdbc.execute(retryCreateTriggerSql);
      assertThat(instanceManagementApplicationService
              .retryPartition(TENANT, failedPartitionId)
              .status())
          .isEqualTo("READY");
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            String currentInvocationId =
            jdbcTemplate.queryForObject(selectCurrentInvocationIdSql3, String.class, TENANT, failedPartitionId);
            assertThat(currentInvocationId).isNotBlank().isNotEqualTo(firstInvocationId);
          });
      String retriedInvocationId =
      jdbcTemplate.queryForObject(selectCurrentInvocationIdSql4, String.class, TENANT, failedPartitionId);
      assertThat(retriedInvocationId).isNotEqualTo(firstInvocationId);
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(150))
          .untilAsserted(() -> {
            String selectedTaskStatusRead2 =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, failedTaskId);
            assertThat(selectedTaskStatusRead2).isEqualTo("SUCCESS");
          });
      final String finalDropTriggerSql = """
          drop trigger %s on biz.process_account_summary\
          """.formatted(triggerName);
      businessJdbc.execute(finalDropTriggerSql);
      final String finalDropFunctionSql = """
          drop function biz.%s()\
          """.formatted(functionName);
      businessJdbc.execute(finalDropFunctionSql);
      String retriedGenerationId = jdbcTemplate.queryForObject(
          selectProcessStagingGenerationIdSql2,
          String.class,
          TENANT,
          failedTaskId);
      assertThat(retriedGenerationId).isNotEqualTo(firstGenerationId);
      Integer selectedCountRead3 =
          businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, firstGenerationId);
      assertThat(selectedCountRead3).isEqualTo(1);
      Integer selectedCountRead4 =
          businessJdbc.queryForObject(selectCountSql2, Integer.class, TENANT, retriedGenerationId);
      assertThat(selectedCountRead4).isZero();
      Integer selectedCountRead5 =
          businessJdbc.queryForObject(selectCountSql4, Integer.class, TENANT);
      assertThat(selectedCountRead5).isEqualTo(1);
      String selectedInstanceStatusRead2 = jdbcTemplate.queryForObject(
          selectInstanceStatusSql2, String.class, TENANT, seed.dedupKey());
      assertThat(selectedInstanceStatusRead2).isEqualTo("RUNNING");

      Long jobInstanceId = jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, seed.dedupKey());
      assertThat(
              instanceManagementApplicationService.pause(TENANT, jobInstanceId).status())
          .isEqualTo("PAUSED");
      String selectedInstanceStatusRead3 =
          jdbcTemplate.queryForObject(selectInstanceStatusSql3, String.class, TENANT, jobInstanceId);
      assertThat(selectedInstanceStatusRead3).isEqualTo("PAUSED");
      assertThat(
              instanceManagementApplicationService.resume(TENANT, jobInstanceId).status())
          .isEqualTo("RUNNING");

      Long workflowRunId = jdbcTemplate.queryForObject(selectIdSql3, Long.class, TENANT, seed.dedupKey());
      assertThat(workflowRunManagementApplicationService
              .pause(TENANT, workflowRunId)
              .status())
          .isEqualTo("PAUSED");
      String selectedRunStatusRead2 =
          jdbcTemplate.queryForObject(selectRunStatusSql2, String.class, TENANT, workflowRunId);
      assertThat(selectedRunStatusRead2).isEqualTo("PAUSED");
      assertThat(workflowRunManagementApplicationService
              .resume(TENANT, workflowRunId)
              .status())
          .isEqualTo("RUNNING");
      String selectedProcessStagingGenerationIdRead2 = jdbcTemplate.queryForObject(
          selectProcessStagingGenerationIdSql3, String.class, TENANT, failedTaskId);
      assertThat(selectedProcessStagingGenerationIdRead2).isEqualTo(retriedGenerationId);

      int affectedOutboxEventRowsWrite2 =
          jdbcTemplate.update(updateOutboxEventSql2, TENANT, delayedTaskId);
      assertThat(affectedOutboxEventRowsWrite2).isEqualTo(1);
      e2eOutboxPublishSupport.publishAllPending(TENANT);
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(150))
          .untilAsserted(() -> {
            String selectedInstanceStatusRead4 = jdbcTemplate.queryForObject(
                selectInstanceStatusSql4, String.class, TENANT, seed.dedupKey());
            assertThat(selectedInstanceStatusRead4).isEqualTo("SUCCESS");
            String selectedRunStatusRead3 =
                jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, seed.dedupKey());
            assertThat(selectedRunStatusRead3).isEqualTo("SUCCESS");
          });
      String selectedTotalAmount =
          businessJdbc.queryForObject(selectTotalAmountSql, String.class, TENANT);
      assertThat(selectedTotalAmount).isEqualTo("7.00");
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists %s on biz.process_account_summary\
          """.formatted(triggerName);
      businessJdbc.execute(
          dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists biz.%s()\
          """.formatted(functionName);
      businessJdbc.execute(dropFunctionSql);
    }
  }

  @Test
  @DisplayName("自定义计算插件链路成功:仅计算阶段产出计数,其余阶段空跑不报错,暂存区仍被清空")
  void wap_customPlugin_simpleComputeOnly_runsAll5StagesAsNoOpForOthers() throws Exception {
    final String selectCountSql2 = """
        select count(*)::int from batch.process_staging
    """;

    final String selectCountSql = """
        select count(*)::int from batch.pipeline_step_run where pipeline_instance_id=?
    """;
    final String selectOutputSummarySql = """
            select output_summary ->> 'processedCount'
            from batch.pipeline_step_run
            where pipeline_instance_id = ? and stage_code = 'COMPUTE'
            """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API);
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.configureCustomPluginJobParameters(
        jdbcTemplate,
        objectMapper,
        TENANT,
        seed.jobCode(),
        CUSTOM_PLUGIN_CODE,
        "{\"processedCount\":1}");

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");
    params.put("bizType", "SETTLEMENT_SUMMARY");

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-process-custom",
        params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          E2eStatusLogger.logJobFlowSnapshot(
              jdbcTemplate, TENANT, seed.dedupKey(), "ProcessE2e-custom-plugin");
          Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
          assertThat(outcome.get("task_status")).isEqualTo("SUCCESS");
          assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS");
          assertThat(outcome.get("high_water_mark_out")).isEqualTo(CUSTOM_PLUGIN_WATERMARK);
        });

    Long pipelineInstanceId = loadPipelineInstanceId(seed.dedupKey());
    Integer selectedCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, pipelineInstanceId);
    assertThat(selectedCount).isEqualTo(5);
    String processedCount = jdbcTemplate.queryForObject(selectOutputSummarySql, String.class, pipelineInstanceId);
    assertThat(processedCount).isEqualTo("1");
    Integer selectedCountRead2 = businessJdbc.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedCountRead2).isZero();
  }

  @Test
  @DisplayName("PROCESS dry-run 经五阶段收口,不写目标表/staging 或推进水位")
  void wap_sqlTransform_dryRunSkipsBusinessWritesAndWatermark() throws Exception {
    final String selectCountSql2 = """
        select count(*)::int from biz.process_account_summary
    """;
    final String selectCountSql3 = """
        select count(*)::int from batch.process_staging
    """;

    final String selectRunStatusSql = """
                  select run_status from batch.workflow_run wr
                  join batch.trigger_request tr on tr.tenant_id = wr.tenant_id
                      and tr.id = wr.trigger_request_id
                  where tr.tenant_id = ? and tr.request_id = ?
                  """;
    final String selectCountSql = """
        select count(*)::int from batch.pipeline_step_run where pipeline_instance_id = ?\s\
        and step_status = 'SUCCESS'\
        """;

    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        new E2eScenarioFixture.LaunchPreparationSpec(
                jdbcTemplate, TENANT, "PROCESS", "process", TriggerType.API)
            .dryRun(true));
    ProcessE2eFixture.cleanProcessRows(businessJdbc);
    ProcessE2eFixture.seedDemoOrderEvents(businessJdbc, TENANT);
    ProcessE2eFixture.configureSqlTransformJobParameters(
        jdbcTemplate, objectMapper, TENANT, seed.jobCode(), List.of());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("bizDate", "2026-01-15");
    launchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, 1, 15))
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("e2e-tr-process-dry-run")
        .params(params)
        .dryRun(true)
        .build());
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> outcome = loadProcessOutcome(seed.dedupKey());
          assertThat(outcome.get("task_status")).isEqualTo("SUCCESS");
          assertThat(outcome.get("instance_status")).isEqualTo("SUCCESS_DRY_RUN");
          assertThat(outcome.get("high_water_mark_out")).isNull();
          String selectedRunStatus =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, seed.requestId());
          assertThat(selectedRunStatus).isEqualTo("SUCCESS_DRY_RUN");
        });

    Integer selectedCount = businessJdbc.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedCount).isZero();
    Integer selectedCountRead2 = businessJdbc.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedCountRead2).isZero();
    Long pipelineInstanceId = loadPipelineInstanceId(seed.dedupKey());
    Integer selectedCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, pipelineInstanceId);
    assertThat(selectedCountRead3).isEqualTo(5);
  }

  private Long loadPipelineInstanceId(String dedupKey) {
    final String selectIdSql = """
        select pi.id
        from batch.pipeline_instance pi
        join batch.job_instance ji on ji.id = pi.related_job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ? and pi.pipeline_type = 'PROCESS'
        """;

    return jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, dedupKey);
  }

  private Map<String, Object> loadProcessOutcome(String dedupKey) {
    final String selectTaskStatusSql = """
        select t.task_status, ji.instance_status, ji.high_water_mark_out
        from batch.job_task t
        join batch.job_instance ji on ji.id = t.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;

    return jdbcTemplate.queryForMap(selectTaskStatusSql, TENANT, dedupKey);
  }

  /** 自定义 plugin 测试桩：只重写 compute()，其余 lifecycle 走 ProcessComputePlugin 默认 no-op。 */
  @TestConfiguration
  static class ProcessE2eTestConfiguration {

    /** 具名类型替代匿名内部类，避免 E2E 启动期 CGLIB/Surefire 偶发加载不到 {@code ProcessE2eTestConfiguration$1}。 */
    static final class E2eStubComputePlugin implements ProcessComputePlugin {

      @Override
      public String implCode() {
        return CUSTOM_PLUGIN_CODE;
      }

      @Override
      public ProcessStageResult compute(ProcessJobContext context) {
        Object stepParams =
            context.getAttributes().get(ProcessRuntimeKeys.PROCESS_COMPUTE_STEP_PARAMS);
        if (stepParams instanceof Map<?, ?> params
            && params.get(CUSTOM_PLUGIN_CODE) instanceof Map<?, ?> pluginParams) {
          context.getAttributes().put("processedCount", pluginParams.get("processedCount"));
        }
        context
            .getAttributes()
            .put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, CUSTOM_PLUGIN_WATERMARK);
        return ProcessStageResult.success(ProcessStage.COMPUTE);
      }
    }

    @Bean
    ProcessComputePlugin e2eProcessComputePlugin() {
      return new E2eStubComputePlugin();
    }
  }
}
