package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowGraphValidator;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WaitingPartitionDispatchScheduler;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("Workflow 晚派发共享作业:冻结版本、分区计划、配额与 SLA 起点")
class WorkflowFrozenPartitionPlanIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbc;
  private final WorkflowRunLaunchService launchService;
  private final TaskExecutionService taskExecutionService;
  private final JobInstanceMapper jobInstanceMapper;
  private final TenantSchedulerSnapshotService snapshotService;
  private final OrchestratorConfigCacheService configCache;
  private final WaitingPartitionDispatchScheduler waitingScheduler;
  private final WorkflowGraphValidator graphValidator;

  WorkflowFrozenPartitionPlanIntegrationTest(
      JdbcTemplate jdbc,
      WorkflowRunLaunchService launchService,
      TaskExecutionService taskExecutionService,
      JobInstanceMapper jobInstanceMapper,
      TenantSchedulerSnapshotService snapshotService,
      OrchestratorConfigCacheService configCache,
      WaitingPartitionDispatchScheduler waitingScheduler,
      WorkflowGraphValidator graphValidator) {
    this.jdbc = jdbc;
    this.launchService = launchService;
    this.taskExecutionService = taskExecutionService;
    this.jobInstanceMapper = jobInstanceMapper;
    this.snapshotService = snapshotService;
    this.configCache = configCache;
    this.waitingScheduler = waitingScheduler;
    this.graphValidator = graphValidator;
  }

  @ParameterizedTest(name = "{0}/{1}")
  @CsvSource({"GENERAL,STATIC", "GENERAL,DYNAMIC", "GENERAL,AUTO", "BUNDLE_IMPORT,DYNAMIC"})
  @DisplayName("在途定义变更不改变晚派发节点的分区、执行配置和 SLA,每个实际作业独立占额并收口")
  void shouldKeepFrozenPlanAndSla_whenSharedJobDefinitionChangesBeforeLateDispatch(
      String jobType, String strategy) {
    runFrozenPlanScenario(jobType, strategy, false);
  }

  @Test
  @DisplayName("停用作业只阻止新 Workflow 接纳,已冻结的在途节点仍按原版完成")
  void shouldRejectNewRunAndFinishAdmittedRun_whenJobDefinitionIsDisabled() {
    FrozenRunCase scenario = runFrozenPlanScenario("GENERAL", "STATIC", true);
    WorkflowLaunchSeed next = seedAnotherWorkflow(scenario);
    assertThat(graphValidator.validate(next.workflowDefinitionId()).errors())
        .anySatisfy(error -> assertThat(error.code()).isEqualTo("JOB_REF_NOT_FOUND"));

    assertThatThrownBy(() -> launchService.launch(request(scenario.tenant(), next)))
        .isInstanceOfSatisfying(BizException.class,
            error -> assertThat(error.getMessageKey()).isEqualTo("error.workflow.definition_invalid"));

    String runCountSql = "select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?";
    assertThat(jdbc.queryForObject(runCountSql, Integer.class, scenario.tenant(), next.workflowDefinitionId())).isZero();
    String requestStatusSql = "select request_status from batch.trigger_request where tenant_id = ? and request_id = ?";
    assertThat(jdbc.queryForObject(requestStatusSql, String.class, scenario.tenant(), next.requestId())).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("多个 Workflow 引用同一作业时各自冻结接纳版本,原运行保持旧版而新运行使用新版")
  void shouldUseIndependentDefinitionVersions_whenTwoWorkflowsShareOneJob() {
    FrozenRunCase scenario = runFrozenPlanScenario("GENERAL", "STATIC", false);
    WorkflowLaunchSeed next = seedAnotherWorkflow(scenario);
    launchService.launch(request(scenario.tenant(), next));
    String runSql = "select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?";
    Long runId = jdbc.queryForObject(runSql, Long.class, scenario.tenant(), next.workflowDefinitionId());
    Long jobId = nodeJob(runId, "SHARED_JOB");
    JobInstanceEntity instance = jobInstanceMapper.selectById(scenario.tenant(), jobId);
    assertThat(instance.getJobDefinitionVersion()).isEqualTo(2);
    assertThat(instance.getExpectedPartitionCount()).isEqualTo(1);
    assertThat(instance.getExpectedDurationSeconds()).isEqualTo(300);
    assertCapacity(scenario.tenant(), 1, 1);
    String taskSql = "select id from batch.job_task where tenant_id = ? and job_instance_id = ?";
    Long taskId = jdbc.queryForObject(taskSql, Long.class, scenario.tenant(), jobId);
    JobTaskEntity claimed = taskExecutionService.assignWorker(scenario.tenant(), taskId, next.workerCode());
    assertThat(claimed).isNotNull();
    EffectiveTaskConfig config = taskExecutionService.loadEffectiveConfig(scenario.tenant(), claimed);
    assertThat(config.timeoutSeconds()).isEqualTo(900);
    assertThat(config.partitionCount()).isEqualTo(1);
    String invocationSql = "select current_invocation_id from batch.job_partition where tenant_id = ? and id = ?";
    String invocation = jdbc.queryForObject(invocationSql, String.class, scenario.tenant(), claimed.getJobPartitionId());
    taskExecutionService.applyTaskOutcome(TaskOutcomeCommand.builder()
        .tenantId(scenario.tenant()).taskId(taskId).success(true).resultSummary("{}")
        .partitionInvocationId(invocation).build());
    assertCapacity(scenario.tenant(), 0, 0);
    String versionsSql = """
        select job_definition_version from batch.job_instance
        where tenant_id = ? and job_code = ? order by id
        """;
    assertThat(jdbc.queryForList(versionsSql, Integer.class, scenario.tenant(), next.jobCode()))
        .containsExactly(1, 1, 2);
    String statusesSql = "select run_status from batch.workflow_run where tenant_id = ? order by id";
    assertThat(jdbc.queryForList(statusesSql, String.class, scenario.tenant())).containsExactly("SUCCESS", "SUCCESS");
  }

  private record FrozenRunCase(String tenant, WorkflowLaunchSeed seed) {}

  private FrozenRunCase runFrozenPlanScenario(String jobType, String strategy, boolean disableForNewRuns) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    long fileBase = System.nanoTime();
    String tenant = "t-r28-" + suffix;
    String queue = "r28_queue_" + suffix;
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbc, tenant, jobType, "R28_GROUP_" + suffix);
    seedSerialGraph(tenant, seed);
    seedCapacity(tenant, queue);
    seedFiles(tenant, fileBase);
    final String updateJobSql = """
    update batch.job_definition set shard_strategy = ?, queue_code = ?,
    default_params = ?::jsonb, timeout_seconds = ?, version = ?
    where tenant_id = ? and job_code = ?
    """;
    jdbc.update(updateJobSql, strategy, queue, JsonUtils.toJson(params(1, fileBase)), 600, 1,
        tenant, seed.jobCode());

    Instant firstAdmissionBefore = BatchDateTimeSupport.utcNow();
    launchService.launch(LaunchRequest.builder()
        .tenantId(tenant)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, 1, 15))
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .params(Map.of())
        .build());
    Instant firstAdmissionAfter = BatchDateTimeSupport.utcNow();
    final String selectRunSql = """
    select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?
    """;
    Long runId = jdbc.queryForObject(selectRunSql, Long.class, tenant, seed.workflowDefinitionId());
    Long firstId = nodeJob(runId, "BUNDLE_JOB");
    assertThat(nodeJob(runId, "LATE_JOB")).isNull();
    assertInstanceSnapshot(tenant, firstId, firstAdmissionBefore, firstAdmissionAfter);
    assertBundleBindings(tenant, firstId, jobType, fileBase);
    assertCapacity(tenant, 1, 2);

    String editedStrategy = "BUNDLE_IMPORT".equals(jobType) ? "DYNAMIC" : "NONE";
    jdbc.update(updateJobSql, editedStrategy, queue, JsonUtils.toJson(params(2, fileBase)), 900, 2,
        tenant, seed.jobCode());
    if (disableForNewRuns) {
      String disableJobSql = "update batch.job_definition set enabled = false where tenant_id = ? and job_code = ?";
      jdbc.update(disableJobSql, tenant, seed.jobCode());
    }
    configCache.evictJobDefinition(tenant, seed.jobCode());
    if (disableForNewRuns) {
      assertThat(configCache.findEnabledJobDefinition(tenant, seed.jobCode())).isNull();
    } else {
      assertThat(configCache.findEnabledJobDefinition(tenant, seed.jobCode()).version()).isEqualTo(2);
    }
    final String disableCurrentEdgesSql = """
    update batch.workflow_edge set enabled = false
    where tenant_id = ? and workflow_definition_id = ? and from_node_code = 'BUNDLE_JOB'
    """;
    jdbc.update(disableCurrentEdgesSql, tenant, seed.workflowDefinitionId());
    final String pauseScheduleSql = """
    update batch.workflow_definition set schedule_enabled = false, version = 2
    where tenant_id = ? and id = ?
    """;
    jdbc.update(pauseScheduleSql, tenant, seed.workflowDefinitionId());

    Instant lateAdmissionBefore = BatchDateTimeSupport.utcNow();
    finishJobWithFrozenConfig(tenant, firstId, seed.workerCode());
    Instant lateAdmissionAfter = BatchDateTimeSupport.utcNow();
    Long lateId = nodeJob(runId, "LATE_JOB");
    assertThat(lateId).isNotNull().isNotEqualTo(firstId);
    assertInstanceSnapshot(tenant, lateId, lateAdmissionBefore, lateAdmissionAfter);
    assertBundleBindings(tenant, lateId, jobType, fileBase);
    Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
      // 子 Job 的 T2 可能先于前驱 REPORT 提交而进入等待,提交后由正式恢复入口重新准入。
      waitingScheduler.dispatchWaitingPartitions();
      assertCapacity(tenant, 1, 2);
    });
    JobInstanceEntity first = jobInstanceMapper.selectById(tenant, firstId);
    JobInstanceEntity late = jobInstanceMapper.selectById(tenant, lateId);
    assertThat(first.getInstanceStatus()).isEqualTo("SUCCESS");
    assertThat(late.getStartedAt()).isAfterOrEqualTo(first.getFinishedAt());
    assertThat(late.getDeadlineAt()).isAfter(first.getDeadlineAt());

    finishJobWithFrozenConfig(tenant, lateId, seed.workerCode());
    assertCapacity(tenant, 0, 0);
    final String runStatusSql = "select run_status from batch.workflow_run where tenant_id = ? and id = ?";
    assertThat(jdbc.queryForObject(runStatusSql, String.class, tenant, runId)).isEqualTo("SUCCESS");
    final String countJobsSql = """
    select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;
    assertThat(jdbc.queryForObject(countJobsSql, Integer.class, tenant, seed.jobCode())).isEqualTo(2);
    final String terminalEventSql = """
    select count(*) from batch.outbox_event
    where tenant_id = ? and event_type = 'WORKFLOW_TERMINAL'
    and payload_json->>'workflowRunId' = ?
    """;
    assertThat(jdbc.queryForObject(terminalEventSql, Integer.class, tenant, runId.toString())).isEqualTo(1);
    return new FrozenRunCase(tenant, seed);
  }

  private LaunchRequest request(String tenant, WorkflowLaunchSeed seed) {
    return LaunchRequest.builder().tenantId(tenant).workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, 1, 15)).triggerType(TriggerType.EVENT)
        .requestId(seed.requestId()).params(Map.of()).build();
  }

  private WorkflowLaunchSeed seedAnotherWorkflow(FrozenRunCase scenario) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String workflowSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name,
          workflow_type, version, enabled, calendar_code, schedule_enabled)
        values (?, ?, 'R28 shared Job workflow', 'DAG', 1, true, 'IT_CALENDAR', false) returning id
        """;
    Long id = jdbc.queryForObject(workflowSql, Long.class, scenario.tenant(), "R28_WF_" + suffix);
    String nodeSql = """
        insert into batch.workflow_node (tenant_id, workflow_definition_id, node_code, node_name,
          node_type, related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled)
        values (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
          (?, ?, 'SHARED_JOB', 'Shared Job', 'JOB', ?, 1, 'NONE', 0, 0, true),
          (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
        """;
    jdbc.update(nodeSql, scenario.tenant(), id, scenario.tenant(), id, scenario.seed().jobCode(), scenario.tenant(), id);
    String edgeSql = """
        insert into batch.workflow_edge (tenant_id, workflow_definition_id, from_node_code,
          to_node_code, edge_type, enabled) values (?, ?, 'START', 'SHARED_JOB', 'ALWAYS', true),
          (?, ?, 'SHARED_JOB', 'END', 'SUCCESS', true)
        """;
    jdbc.update(edgeSql, scenario.tenant(), id, scenario.tenant(), id);
    String requestId = "r28-next-" + suffix;
    String requestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,
          biz_date, dedup_key, request_status) values (?, ?, 'EVENT', ?, date '2026-01-15', ?, 'ACCEPTED')
        """;
    jdbc.update(requestSql, scenario.tenant(), requestId, id, requestId);
    return new WorkflowLaunchSeed(scenario.seed().jobCode(), id, requestId, requestId, scenario.seed().workerCode());
  }

  private Map<String, Object> params(int version, long fileBase) {
    List<Map<String, Object>> files = version == 1
        ? List.of(Map.of("sourceFileId", fileBase + 1, "templateCode", "TPL_OLD_A"),
            Map.of("sourceFileId", fileBase + 2, "templateCode", "TPL_OLD_B"))
        : List.of(Map.of("sourceFileId", fileBase + 3, "templateCode", "TPL_NEW"));
    return Map.of("partitionCount", version == 1 ? 2 : 3,
        "expectedDurationSeconds", version == 1 ? 120 : 300,
        "marker", version == 1 ? "original" : "edited", "bundleFiles", files);
  }

  private void assertInstanceSnapshot(
      String tenant, Long instanceId, Instant admissionBefore, Instant admissionAfter) {
    JobInstanceEntity instance = jobInstanceMapper.selectById(tenant, instanceId);
    assertThat(instance.getJobDefinitionVersion()).isEqualTo(1);
    assertThat(instance.getExpectedPartitionCount()).isEqualTo(2);
    assertThat(instance.getExpectedDurationSeconds()).isEqualTo(120);
    assertThat(instance.getDeadlineAt())
        .isBetween(admissionBefore.plusSeconds(600), admissionAfter.plusSeconds(600));
    Map<String, Object> snapshot = JsonUtils.fromJson(instance.getParamsSnapshot(), new TypeReference<>() {});
    assertThat(((Map<?, ?>) snapshot.get("effectiveParams")).get("marker")).isEqualTo("original");
  }

  private void assertBundleBindings(String tenant, Long instanceId, String jobType, long fileBase) {
    if (!"BUNDLE_IMPORT".equals(jobType)) return;
    String sql = """
        select source_file_id, template_code from batch.job_partition
        where tenant_id = ? and job_instance_id = ? order by partition_no
        """;
    assertThat(jdbc.queryForList(sql, tenant, instanceId)).containsExactly(
        Map.of("source_file_id", fileBase + 1, "template_code", "TPL_OLD_A"),
        Map.of("source_file_id", fileBase + 2, "template_code", "TPL_OLD_B"));
  }

  private void finishJobWithFrozenConfig(String tenant, Long instanceId, String worker) {
    String selectTasksSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ? order by id
        """;
    List<Long> tasks = jdbc.queryForList(selectTasksSql, Long.class, tenant, instanceId);
    assertThat(tasks).hasSize(2);
    for (Long taskId : tasks) {
      JobTaskEntity claimed = taskExecutionService.assignWorker(tenant, taskId, worker);
      assertThat(claimed).isNotNull();
      EffectiveTaskConfig config = taskExecutionService.loadEffectiveConfig(tenant, claimed);
      assertThat(config.timeoutSeconds()).isEqualTo(600);
      assertThat(config.partitionCount()).isEqualTo(2);
      assertThat(config.partitionPlanVersion()).isEqualTo(1);
      assertThat(config.shardTotal()).isEqualTo(2);
      String invocationSql = "select current_invocation_id from batch.job_partition where tenant_id = ? and id = ?";
      String invocation = jdbc.queryForObject(invocationSql, String.class, tenant, claimed.getJobPartitionId());
      taskExecutionService.applyTaskOutcome(TaskOutcomeCommand.builder()
          .tenantId(tenant).taskId(taskId).success(true).resultSummary("{}")
          .partitionInvocationId(invocation).build());
    }
  }

  private void assertCapacity(String tenant, long jobs, long partitions) {
    var snapshot = snapshotService.buildLive(tenant);
    assertThat(snapshot.policies()).hasSize(1);
    assertThat(snapshot.policies().getFirst().activeJobs()).isEqualTo(jobs);
    assertThat(snapshot.policies().getFirst().activePartitions()).isEqualTo(partitions);
    assertThat(snapshot.queues()).hasSize(1);
    assertThat(snapshot.queues().getFirst().activeJobs()).isEqualTo(jobs);
    assertThat(snapshot.queues().getFirst().activePartitions()).isEqualTo(partitions);
  }

  private Long nodeJob(Long runId, String nodeCode) {
    String sql = "select job_instance_id from batch.workflow_node_run where workflow_run_id = ? and node_code = ?";
    List<Long> ids = jdbc.queryForList(sql, Long.class, runId, nodeCode);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private void seedCapacity(String tenant, String queue) {
    String policySql = """
        insert into batch.tenant_quota_policy (
          tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant,
          max_qps_per_tenant, fair_share_weight, enabled, burst_limit, partition_burst_limit,
          quota_reset_policy, group_shared_max_running_jobs, exceeded_strategy)
        values (?, 'r28_policy', 1, 2, 0, 1, true, 0, 0, 'NONE', 0, 'QUEUE_DEFER')
        """;
    jdbc.update(policySql, tenant);
    String queueSql = """
        insert into batch.resource_queue (tenant_id, queue_code, queue_name, queue_type, max_running_jobs,
          max_running_partitions, max_qps, fair_share_weight, enabled)
        values (?, ?, 'R28 frozen plan queue', 'MIXED', 1, 2, 0, 1, true)
        """;
    jdbc.update(queueSql, tenant, queue);
  }

  private void seedFiles(String tenant, long fileBase) {
    String sql = """
        insert into batch.file_record (tenant_id, id, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status)
        values (?, ?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED')
        """;
    for (long id : List.of(fileBase + 1, fileBase + 2, fileBase + 3)) {
      jdbc.update(sql, tenant, id, "r28-" + tenant + id, "/r28-input/" + tenant + "/" + id);
    }
  }

  private void seedSerialGraph(String tenant, WorkflowLaunchSeed seed) {
    String workflowSql = """
        update batch.workflow_definition set single_job_definition_id = null, schedule_enabled = true
        where tenant_id = ? and id = ?
        """;
    jdbc.update(workflowSql, tenant, seed.workflowDefinitionId());
    String endOrderSql = """
        update batch.workflow_node set node_order = 3
        where tenant_id = ? and workflow_definition_id = ? and node_code = 'END'
        """;
    jdbc.update(endOrderSql, tenant, seed.workflowDefinitionId());
    String nodeSql = """
        insert into batch.workflow_node (tenant_id, workflow_definition_id, node_code, node_name, node_type,
          related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled)
        values (?, ?, 'LATE_JOB', 'Late Job', 'JOB', ?, 2, 'NONE', 0, 0, true)
        """;
    jdbc.update(nodeSql, tenant, seed.workflowDefinitionId(), seed.jobCode());
    String edgeSql = """
        update batch.workflow_edge set to_node_code = 'LATE_JOB'
        where tenant_id = ? and workflow_definition_id = ? and from_node_code = 'BUNDLE_JOB'
        """;
    jdbc.update(edgeSql, tenant, seed.workflowDefinitionId());
    String lastEdgeSql = """
        insert into batch.workflow_edge (tenant_id, workflow_definition_id, from_node_code,
          to_node_code, edge_type, enabled) values (?, ?, 'LATE_JOB', 'END', 'SUCCESS', true)
        """;
    jdbc.update(lastEdgeSql, tenant, seed.workflowDefinitionId());
  }
}
