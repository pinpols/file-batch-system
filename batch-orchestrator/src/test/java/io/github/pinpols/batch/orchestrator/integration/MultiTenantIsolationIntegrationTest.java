package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.timeout.JobInstanceTimeoutEnforcer;
import io.github.pinpols.batch.orchestrator.infrastructure.timeout.TaskTimeoutEnforcer;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.PlatformTestdataSql;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.AopTestUtils;

/** 集成测试：多租户数据隔离。验证租户 t1 的运行态数据对其他租户不可见，且共享多租户种子（ta/tb/tc）按租户隔离。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts = {PlatformTestdataSql.MULTI_TENANT_SEED})
@DisplayName("多租户数据隔离: 运行态数据与共享种子配置在租户之间的可见性边界")
class MultiTenantIsolationIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private WorkflowRunManagementApplicationService workflowRunManagementApplicationService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private WorkflowNodeRunMapper workflowNodeRunMapper;

  @Autowired
  private BatchOrchestratorGovernanceProperties governance;

  @Autowired
  private JobInstanceTimeoutEnforcer jobInstanceTimeoutEnforcer;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private TaskTimeoutEnforcer taskTimeoutEnforcer;

  @Test
  @DisplayName("按相同去重键跨租户查询时候只能查到本租户的作业实例")
  void shouldNotExposeJobInstanceAcrossTenants() {
    final String selectIdSql = """
        select id from batch.job_task where tenant_id = 't1' and job_instance_id = ? order by id limit 1\
        """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t1", "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest isolationRequest = LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-isolation")
        .params(Map.of())
        .build();
    var response = workflowRunLaunchService.launch(isolationRequest);

    // t1 can find the job instance
    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, "t1", response.instanceNo());
    JobInstanceEntity t1Instance = jobInstanceMapper.selectById("t1", jobInstanceId);
    assertThat(t1Instance).isNotNull();
    assertThat(t1Instance.getTenantId()).isEqualTo("t1");

    Long taskId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        jobInstanceId);
    assertThat(jobTaskMapper.selectById("t1", taskId)).isNotNull();
    assertThat(jobTaskMapper.selectById("t2", taskId)).isNull();
    assertThat(jobTaskMapper.selectForWorkerClaim("t1", taskId)).isNotNull();
    assertThat(jobTaskMapper.selectForWorkerClaim("t2", taskId)).isNull();

    // t2 cannot find t1's job instance
    JobInstanceEntity t2Lookup =
        jobInstanceMapper.selectByTenantAndDedupKey("t2", t1Instance.getDedupKey());
    assertThat(t2Lookup).isNull();
  }

  @Test
  @DisplayName("伪造其它租户的 Workflow ID 时拒绝启动且不产生运行副作用")
  void shouldRejectForgedCrossTenantWorkflowAssociation() {
  final String selectCountSql = """
      select count(*) from batch.workflow_run where tenant_id = 't1' and workflow_definition_id = ?\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.workflow_run where tenant_id = 't2' and workflow_definition_id = ?\
      """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = 't1' and request_id = ?\
        """;

    WorkflowLaunchSeed tenantA = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t1", "IMPORT", "IMPORT", TriggerType.API);
    WorkflowLaunchSeed tenantB = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t2", "EXPORT", "EXPORT", TriggerType.API);
    long tenantAJobInstancesBefore = countJobInstances("t1");
    long tenantAOutboxEventsBefore = countOutboxEvents("t1");

    LaunchRequest forgedRequest = LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(tenantB.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(tenantA.requestId())
        .traceId("trace-forged-cross-tenant-workflow")
        .params(Map.of())
        .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(forgedRequest))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.STATE_CONFLICT);

    Long selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, tenantA.workflowDefinitionId());
    assertThat(selectedWorkflowRunCount).isZero();
    Long selectedWorkflowRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, tenantB.workflowDefinitionId());
    assertThat(selectedWorkflowRunCountRead2).isZero();
    assertThat(countJobInstances("t1")).isEqualTo(tenantAJobInstancesBefore);
    assertThat(countOutboxEvents("t1")).isEqualTo(tenantAOutboxEventsBefore);
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, tenantA.requestId());
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("伪造其它租户 WorkflowRun ID 执行节点人工跳过时拒绝且不改变状态")
  void shouldRejectCrossTenantWorkflowNodeSkipWithoutMutation() {
  final String selectNodeCodeSql = """
      select node_code from batch.workflow_node_run where workflow_run_id = ? and node_type = 'JOB'\s\
      order by id limit 1\
      """;
  final String updateWorkflowNodeRunSql = """
      update batch.workflow_node_run set node_status = 'FAILED' where workflow_run_id = ? and node_code = ?\
      """;
  final String updateWorkflowRunSql = """
      update batch.workflow_run set run_status = 'PAUSED' where tenant_id = 't2' and id = ?
  """;
  final String updateWorkflowRunSql2 = """
      update batch.workflow_run set run_status = 'RUNNING' where tenant_id = 't2' and id = ?
  """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ? and tenant_id = 't2'
    """;

    WorkflowLaunchSeed tenantB = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t2", "EXPORT", "EXPORT", TriggerType.API);
    var response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId("t2")
        .workflowDefinitionId(tenantB.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(tenantB.requestId())
        .traceId("trace-cross-tenant-node-skip")
        .params(Map.of())
        .build());
    Long tenantBWorkflowRunId = Long.valueOf(response.instanceNo());
    String tenantBNodeCode = jdbcTemplate.queryForObject(
        selectNodeCodeSql,
        String.class,
        tenantBWorkflowRunId);
    jdbcTemplate.update(
        updateWorkflowNodeRunSql,
        tenantBWorkflowRunId,
        tenantBNodeCode);

    assertTenantCannotManageRun(
        () -> workflowRunManagementApplicationService.pause("t1", tenantBWorkflowRunId));
    assertThat(workflowRunStatus(tenantBWorkflowRunId)).isEqualTo("RUNNING");

    jdbcTemplate.update(
        updateWorkflowRunSql,
        tenantBWorkflowRunId);
    assertTenantCannotManageRun(
        () -> workflowRunManagementApplicationService.resume("t1", tenantBWorkflowRunId));
    assertThat(workflowRunStatus(tenantBWorkflowRunId)).isEqualTo("PAUSED");

    jdbcTemplate.update(
        updateWorkflowRunSql2,
        tenantBWorkflowRunId);
    assertTenantCannotManageRun(
        () -> workflowRunManagementApplicationService.cancel("t1", tenantBWorkflowRunId));
    assertTenantCannotManageRun(
        () -> workflowRunManagementApplicationService.terminate("t1", tenantBWorkflowRunId));
    assertThatThrownBy(() -> workflowRunManagementApplicationService.skipNode(
            "t1", tenantBWorkflowRunId, tenantBNodeCode, "operator-cross-tenant", "forged id"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);

    String selectedNodeStatus = jdbcTemplate.queryForObject(
        selectNodeStatusSql, String.class, tenantBWorkflowRunId, tenantBNodeCode);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, tenantBWorkflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
  }

  private void assertTenantCannotManageRun(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.NOT_FOUND);
  }

  private String workflowRunStatus(Long workflowRunId) {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ? and tenant_id = 't2'
    """;

    return jdbcTemplate.queryForObject(
        selectRunStatusSql,
        String.class,
        workflowRunId);
  }

  @Test
  @DisplayName("拒绝把其他租户 JobInstance 关联到本租户 Workflow 节点")
  void shouldRejectCrossTenantJobInstanceAttachment() {
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)\s\
        values (?, 'TENANT_ATTACHMENT', 'JOB', 'READY') returning id\
        """;
    final String selectJobInstanceIdSql = """
        select job_instance_id from batch.workflow_node_run where id = ?
    """;

    WorkflowLaunchSeed tenantA = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t1", "IMPORT", "IMPORT", TriggerType.API);
    WorkflowLaunchSeed tenantB = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t2", "EXPORT", "EXPORT", TriggerType.API);
    var tenantARun = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(tenantA.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(tenantA.requestId())
        .traceId("trace-tenant-a-attachment")
        .params(Map.of())
        .build());
    var tenantBRun = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId("t2")
        .workflowDefinitionId(tenantB.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(tenantB.requestId())
        .traceId("trace-tenant-b-attachment")
        .params(Map.of())
        .build());

    Long tenantAWorkflowRunId = Long.valueOf(tenantARun.instanceNo());
    Long tenantBJobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, "t2", tenantBRun.instanceNo());
    Long nodeRunId = jdbcTemplate.queryForObject(
        insertWorkflowNodeRunSql,
        Long.class,
        tenantAWorkflowRunId);

    assertThat(workflowNodeRunMapper.attachJobInstance("t1", nodeRunId, tenantBJobInstanceId))
        .isZero();
    Long selectedJobInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, nodeRunId);
    assertThat(selectedJobInstanceId).isNull();
  }

  @Test
  @DisplayName("Job 超时系统扫描跨租户处理候选时只终结各自租户实例")
  void shouldEnforceTimedOutInstancesAcrossTenantsWithoutMixingTenantState() {
  final String selectCountSql = """
      select count(*) from batch.job_task where tenant_id = 't1' and job_instance_id = ?\s\
      and task_status = 'FAILED'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.job_partition where tenant_id = 't2' and job_instance_id = ?\s\
      and partition_status = 'FAILED'\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.workflow_node_run where job_instance_id = ? and node_status = 'FAILED'\
      """;
    final String selectRunStatusSql = """
        select wr.run_status from batch.workflow_run wr\s\
        join batch.workflow_node_run nr on nr.workflow_run_id = wr.id where nr.job_instance_id = ?\
        """;

    WorkflowLaunchSeed tenantA = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t1", "IMPORT", "IMPORT", TriggerType.API);
    WorkflowLaunchSeed tenantB = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t2", "EXPORT", "EXPORT", TriggerType.API);
    var tenantAResponse = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(tenantA.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(tenantA.requestId())
        .traceId("trace-timeout-tenant-a")
        .params(Map.of())
        .build());
    var tenantBResponse = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId("t2")
        .workflowDefinitionId(tenantB.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(tenantB.requestId())
        .traceId("trace-timeout-tenant-b")
        .params(Map.of())
        .build());
    Long tenantAInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, "t1", tenantAResponse.instanceNo());
    Long tenantBInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, "t2", tenantBResponse.instanceNo());
    markRunningTimeoutCandidate("t1", tenantA.jobCode(), tenantAInstanceId);
    markRunningTimeoutCandidate("t2", tenantB.jobCode(), tenantBInstanceId);
    governance.timeout().setBatchSize(10_000);
    assertThat(jobInstanceMapper.selectTimedOutCandidates(10_000))
        .extracting(JobInstanceEntity::getId)
        .contains(tenantAInstanceId, tenantBInstanceId);

    AopTestUtils.<JobInstanceTimeoutEnforcer>getTargetObject(jobInstanceTimeoutEnforcer)
        .enforce();

    assertThat(RlsTenantContextHolder.get()).isNull();
    assertThat(instanceStatus("t1", tenantAInstanceId)).isEqualTo("FAILED");
    assertThat(instanceStatus("t2", tenantBInstanceId)).isEqualTo("FAILED");
    Long selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, tenantAInstanceId);
    assertThat(selectedJobTaskCount).isEqualTo(1L);
    Long selectedJobPartitionCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, tenantBInstanceId);
    assertThat(selectedJobPartitionCount).isEqualTo(1L);
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, tenantAInstanceId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1L);
    Long selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, tenantBInstanceId);
    assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1L);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, tenantAInstanceId);
    assertThat(selectedRunStatus).isEqualTo("FAILED");
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, tenantBInstanceId);
    assertThat(selectedRunStatusRead2).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("全局 Task 超时扫描只取消各租户到期任务,错租户写入与重复扫描不改变其它状态")
  void shouldCancelExpiredTasksForEachTenant_withoutCrossTenantWritesOrDuplicateMutation() {
    String markTaskExpiredSql = """
        update batch.job_task set task_status = 'RUNNING',
               started_at = current_timestamp - interval '2 hours', task_timeout_seconds = 60
         where tenant_id = ? and job_instance_id = ?
        """;
    String selectTaskSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ? order by id limit 1
        """;
    String selectCancellationSql = """
        select cancel_requested, task_status, updated_at from batch.job_task where tenant_id = ? and id = ?
        """;
    Map<String, Long> tasks = new java.util.LinkedHashMap<>();
    for (String tenant : List.of("t1", "t2")) {
      WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
          jdbcTemplate, tenant, "IMPORT", "IMPORT", TriggerType.API);
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenant)
          .workflowDefinitionId(seed.workflowDefinitionId())
          .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
          .triggerType(TriggerType.API)
          .requestId(seed.requestId())
          .traceId("r20-task-timeout-" + tenant)
          .params(Map.of())
          .build();
      var response = workflowRunLaunchService.launch(request);
      Long instanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
          jdbcTemplate, tenant, response.instanceNo());
      jdbcTemplate.update(markTaskExpiredSql, tenant, instanceId);
      Long taskId = jdbcTemplate.queryForObject(selectTaskSql, Long.class, tenant, instanceId);
      tasks.put(tenant, taskId);
    }
    Long firstTenantTaskId = tasks.get("t1");
    int wrongTenantChanges = jobTaskMapper.requestCancel("t2", firstTenantTaskId);
    assertThat(wrongTenantChanges).isZero();
    int previousBatchSize = governance.timeout().getBatchSize();
    try {
      governance.timeout().setBatchSize(10_000);
      TaskTimeoutEnforcer target = AopTestUtils.getTargetObject(taskTimeoutEnforcer);
      target.enforce();
      assertThat(RlsTenantContextHolder.get()).isNull();
      Map<String, Map<String, Object>> snapshots = new java.util.LinkedHashMap<>();
      for (var task : tasks.entrySet()) {
        String taskTenant = task.getKey();
        Long taskId = task.getValue();
        Map<String, Object> state = jdbcTemplate.queryForMap(selectCancellationSql, taskTenant, taskId);
        assertThat(state).containsEntry("cancel_requested", true).containsEntry("task_status", "RUNNING");
        snapshots.put(task.getKey(), state);
      }
      target.enforce();
      for (var task : tasks.entrySet()) {
        String taskTenant = task.getKey();
        Long taskId = task.getValue();
        Map<String, Object> repeated = jdbcTemplate.queryForMap(selectCancellationSql, taskTenant, taskId);
        assertThat(repeated).isEqualTo(snapshots.get(task.getKey()));
      }
      assertThat(RlsTenantContextHolder.get()).isNull();
    } finally {
      governance.timeout().setBatchSize(previousBatchSize);
    }
  }

  @Test
  @DisplayName("dry-run Job 超时后原子收敛任务、Workflow 节点与根运行")
  void shouldCloseDryRunWorkflowWhenJobInstanceTimesOut() {
    final String insertBatchDayInstanceSql = """
          insert into batch.batch_day_instance (
              tenant_id, calendar_code, biz_date, day_status, open_at, cutoff_at
          ) values (?, 'JOB_CALENDAR', ?, 'CUTOFF', current_timestamp - interval '3 days',
              current_timestamp - interval '2 days')
          on conflict (tenant_id, calendar_code, biz_date)
          do update set day_status = 'CUTOFF', cutoff_at = excluded.cutoff_at
          """;
    final String selectDryRunSql = """
        select dry_run from batch.job_instance where tenant_id = 't1' and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_task where tenant_id = 't1' and job_instance_id = ?\s\
        and task_status = 'FAILED'\
        """;
    final String selectNodeStatusSql = """
        select nr.node_status from batch.workflow_node_run nr where nr.job_instance_id = ?\
        """;
    final String selectRunStatusSql = """
        select wr.run_status from batch.workflow_run wr\s\
        join batch.workflow_node_run nr on nr.workflow_run_id = wr.id where nr.job_instance_id = ?\
        """;

    LocalDate bizDate = LocalDate.of(2026, Month.JANUARY, 14);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t1", "IMPORT", "IMPORT", TriggerType.API, bizDate);
    jdbcTemplate.update(insertBatchDayInstanceSql, "t1", bizDate);
    var response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-timeout-dry-run")
        .params(Map.of())
        .dryRun(true)
        .build());
    Long instanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, "t1", response.instanceNo());
    Boolean selectedDryRun =
        jdbcTemplate.queryForObject(selectDryRunSql, Boolean.class, instanceId);
    assertThat(selectedDryRun).isTrue();
    markRunningTimeoutCandidate("t1", seed.jobCode(), instanceId);
    governance.timeout().setBatchSize(10_000);

    AopTestUtils.<JobInstanceTimeoutEnforcer>getTargetObject(jobInstanceTimeoutEnforcer)
        .enforce();

    assertThat(instanceStatus("t1", instanceId)).isEqualTo("FAILED_DRY_RUN");
    Long selectedJobTaskCount = jdbcTemplate.queryForObject(selectCountSql, Long.class, instanceId);
    assertThat(selectedJobTaskCount).isEqualTo(1L);
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, instanceId);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, instanceId);
    assertThat(selectedRunStatus).isEqualTo("FAILED_DRY_RUN");
  }

  @Test
  @DisplayName("作业启动产生的待发事件不跨租户可见,其它租户查不到同类事件")
  void shouldNotExposeOutboxEventsAcrossTenants() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t1", "EXPORT", "EXPORT", TriggerType.MANUAL);
    long t1OutboxBefore =
        LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t1", "EXPORT");
    long t2OutboxBefore =
        LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t2", "EXPORT");

    LaunchRequest outboxIsolationRequest = LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.MANUAL)
        .requestId(seed.requestId())
        .traceId("trace-outbox-isolation")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(outboxIsolationRequest);

    long t1Outbox = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t1", "EXPORT");
    long t2Outbox = LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, "t2", "EXPORT");

    assertThat(t1Outbox).isGreaterThan(t1OutboxBefore);
    // t2 has no export outbox from t1's launch
    assertThat(t2Outbox).isEqualTo(t2OutboxBefore);
  }

  @Test
  @DisplayName("种子里为 tb 租户预置的作业定义只在该租户下可查,其它租户计数为零")
  void shouldExposeTbSeededJobDefinitionsOnlyToTbTenant() {
  final String selectCountSql = """
      select count(*) from batch.job_definition where tenant_id = 'tb' and job_code like 'TB_%%'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.job_definition where tenant_id = 't1' and job_code like 'TB_%%'\
      """;

    Long tbCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Long.class);
    Long t1Count = jdbcTemplate.queryForObject(
        selectCountSql2,
        Long.class);

    assertThat(tbCount).isEqualTo(2L);
    assertThat(t1Count).isZero();
  }

  @Test
  @DisplayName("种子里为 tc 租户预置的作业定义只在该租户下可查,其它租户计数为零")
  void shouldExposeTcSeededJobDefinitionsOnlyToTcTenant() {
  final String selectCountSql = """
      select count(*) from batch.job_definition where tenant_id = 'tc' and job_code like 'TC_%%'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.job_definition where tenant_id = 't1' and job_code like 'TC_%%'\
      """;

    Long tcCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Long.class);
    Long t1Count = jdbcTemplate.queryForObject(
        selectCountSql2,
        Long.class);

    assertThat(tcCount).isGreaterThanOrEqualTo(3L);
    assertThat(t1Count).isZero();
  }

  @Test
  @DisplayName("配额策略按租户各自维护,不同租户的运行上限与重置策略互不相同")
  void shouldScopeQuotaPoliciesPerTenant() {
  final String selectCountSql = """
      select count(*) from batch.tenant_quota_policy where tenant_id = 'tb' and policy_code = 'DEFAULT'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.tenant_quota_policy where tenant_id = 'tc' and policy_code = 'DEFAULT'\
      """;
  final String selectMaxRunningJobsPerTenantSql = """
      select max_running_jobs_per_tenant, quota_reset_policy from batch.tenant_quota_policy\s\
      where tenant_id = 'tb' and policy_code = 'DEFAULT' order by id desc limit 1\
      """;
  final String selectMaxRunningJobsPerTenantSql2 = """
      select max_running_jobs_per_tenant, quota_reset_policy from batch.tenant_quota_policy\s\
      where tenant_id = 'tc' and policy_code = 'DEFAULT' order by id desc limit 1\
      """;

    Long tbPolicies = jdbcTemplate.queryForObject(
        selectCountSql,
        Long.class);
    Long tcPolicies = jdbcTemplate.queryForObject(
        selectCountSql2,
        Long.class);

    assertThat(tbPolicies).isGreaterThanOrEqualTo(1L);
    assertThat(tcPolicies).isGreaterThanOrEqualTo(1L);

    // 验证不同租户的配额限制不同
    Map<String, Object> tbPolicy = jdbcTemplate.queryForMap(
        selectMaxRunningJobsPerTenantSql);
    Map<String, Object> tcPolicy = jdbcTemplate.queryForMap(
        selectMaxRunningJobsPerTenantSql2);

    assertThat(tbPolicy).containsEntry("max_running_jobs_per_tenant", 50);
    assertThat(tcPolicy).containsEntry("max_running_jobs_per_tenant", 30);
    assertThat(tbPolicy).containsEntry("quota_reset_policy", "CALENDAR_DAY");
    assertThat(tcPolicy).containsEntry("quota_reset_policy", "SLIDING_WINDOW");
  }

  @Test
  @DisplayName("工作节点注册表按租户隔离,不同租户的节点编码集合不重叠")
  void shouldScopeWorkerRegistriesPerTenant() {
  final String selectWorkerCodeSql = """
      select worker_code from batch.worker_registry where tenant_id = 'tb'
  """;
  final String selectWorkerCodeSql2 = """
      select worker_code from batch.worker_registry where tenant_id = 'tc'
  """;

    List<Map<String, Object>> tbWorkers = jdbcTemplate.queryForList(
        selectWorkerCodeSql);
    List<Map<String, Object>> tcWorkers = jdbcTemplate.queryForList(
        selectWorkerCodeSql2);

    assertThat(tbWorkers).isNotEmpty();
    assertThat(tcWorkers).isNotEmpty();

    List<String> tbWorkerCodes =
        tbWorkers.stream().map(r -> (String) r.get("worker_code")).toList();
    List<String> tcWorkerCodes =
        tcWorkers.stream().map(r -> (String) r.get("worker_code")).toList();

    assertThat(tbWorkerCodes).isNotEmpty();
    assertThat(tcWorkerCodes).isNotEmpty();
    for (String tbCode : tbWorkerCodes) {
      assertThat(tcWorkerCodes).doesNotContain(tbCode);
    }
  }

  @Test
  @DisplayName("在其它租户启动作业不影响本租户既有实例数,新租户自身计数增加")
  void shouldNotChangeOtherTenantJobCount_whenLaunchingNewJob() {
    long t1Before = countJobInstances("t1");

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, "t2", "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest t2Request = LaunchRequest.builder()
        .tenantId("t2")
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, Month.JANUARY, 15))
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-t2-launch")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(t2Request);

    long t1After = countJobInstances("t1");
    long t2After = countJobInstances("t2");

    assertThat(t1After).isEqualTo(t1Before);
    assertThat(t2After).isGreaterThan(0L);
  }

  private long countJobInstances(String tenantId) {
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ?
    """;

    Long count = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, tenantId);
    return count == null ? 0L : count;
  }

  private long countOutboxEvents(String tenantId) {
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ?
    """;

    Long count = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, tenantId);
    return count == null ? 0L : count;
  }

  private void markRunningTimeoutCandidate(String tenantId, String jobCode, Long instanceId) {
    final String updateJobDefinitionSql = """
        update batch.job_definition set timeout_seconds = 1 where tenant_id = ? and job_code = ?\
        """;
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status = 'RUNNING', started_at = current_timestamp - interval\s\
        '1 hour' where tenant_id = ? and id = ?\
        """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'RUNNING' where tenant_id = ? and job_instance_id = ?\
        """;

    jdbcTemplate.update(
        updateJobDefinitionSql,
        tenantId,
        jobCode);
    jdbcTemplate.update(
        updateJobInstanceSql,
        tenantId,
        instanceId);
    jdbcTemplate.update(
        updateJobTaskSql,
        tenantId,
        instanceId);
  }

  private String instanceStatus(String tenantId, Long instanceId) {
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;

    return jdbcTemplate.queryForObject(
        selectInstanceStatusSql,
        String.class,
        tenantId,
        instanceId);
  }
}
