package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskCreationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：任务启动 → 任务认领 → 任务上报（成功）→ job_instance 达到 SUCCESS。
 *
 * <p>验证完整的同步生命周期链路：
 *
 * <ol>
 *   <li>{@link WorkflowRunLaunchService#launch} 创建 Workflow Run 并派发 Job 节点。
 *   <li>{@link TaskExecutionService#assignWorker} 将任务转为 RUNNING。
 *   <li>{@link TaskExecutionService#applyTaskOutcome} 以 success=true 推进任务， 最终使 job_instance 达到
 *       SUCCESS。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("作业启动到收尾的端到端链路: 启动落库,任务认领与结果上报后任务与作业实例的状态收敛")
class JobLaunchToFinishLifecycleIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private TaskExecutionService taskExecutionService;

  @Autowired
  private TaskCreationService taskCreationService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void refreshWorkersForClaim() {
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  @DisplayName("启动并认领任务后上报成功时候任务与作业实例都进入成功终态")
  void launchThenClaimThenReport_jobInstanceReachesSuccess() {
    final String selectCurrentInvocationIdSql = """
        select current_invocation_id from batch.job_partition where id = ?
    """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    // 1) Launch
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-lifecycle-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance = jobInstanceForWorkflowRun(response.instanceNo());
    assertThat(jobInstance).isNotNull();
    assertThat(jobInstance.getInstanceStatus())
        .isIn(
            JobInstanceStatus.READY.code(),
            JobInstanceStatus.RUNNING.code(),
            JobInstanceStatus.WAITING.code());

    // 2) 认领任务
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
        new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);
    String selectedWorkerCode = task.getAssignedWorkerCode();
    assertThat(selectedWorkerCode).isNotBlank();

    JobTaskEntity claimed = assignWorkerWithRetry(task, selectedWorkerCode);
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(selectedWorkerCode);

    // 3) Report success —— 镜像生产:worker CLAIM 时确立的 invocationId 必须随 report 回填。
    // 服务端 assignWorker 在 CLAIM 时把 current_invocation_id 写进 partition;真 worker 持此值并在 report
    // 携带。测试从 partition 读回该值填入命令,复现生产回填(否则触发 R3-P1-10 report invocation fence)。
    String invocationId = jdbcTemplate.queryForObject(
        selectCurrentInvocationIdSql, String.class, claimed.getJobPartitionId());
    TaskOutcomeCommand successOutcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(claimed.getId())
        .success(true)
        .resultSummary("{\"status\":\"processed ok\"}")
        .partitionInvocationId(invocationId)
        .build();
    taskExecutionService.applyTaskOutcome(successOutcome);

    // 4) Verify final task status
    JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, claimed.getId());
    assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.code());

    // 5) Verify job_instance reaches SUCCESS
    JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
    assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.SUCCESS.code());
  }

  @Test
  @DisplayName("启动并认领任务后上报失败时候任务与作业实例都进入失败终态")
  void launchThenClaimThenReport_failureTransitionsTaskToFailed() {
    final String selectCurrentInvocationIdSql = """
        select current_invocation_id from batch.job_partition where id = ?
    """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-fail-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance = jobInstanceForWorkflowRun(response.instanceNo());
    assertThat(jobInstance).isNotNull();

    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
        new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);
    String selectedWorkerCode = task.getAssignedWorkerCode();
    assertThat(selectedWorkerCode).isNotBlank();

    JobTaskEntity claimed = assignWorkerWithRetry(task, selectedWorkerCode);
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(selectedWorkerCode);

    // 上报失败（测试夹具中未配置重试策略：retry_max_count = 0）
    String invocationId = jdbcTemplate.queryForObject(
        selectCurrentInvocationIdSql, String.class, claimed.getJobPartitionId());
    TaskOutcomeCommand failureOutcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(claimed.getId())
        .success(false)
        .errorCode("TEST_FAILURE")
        .errorMessage("simulated error")
        .partitionInvocationId(invocationId)
        .build();
    taskExecutionService.applyTaskOutcome(failureOutcome);

    JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, claimed.getId());
    assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED.code());

    JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
    assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.FAILED.code());
  }

  @Test
  @DisplayName("DISPATCH 束文件号在建任务时记录关联并校验租户")
  void shouldRecordAndValidateBundleFileForDispatchTask() {
    final String insertFileRecordSql1 = """
        insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status)
        values (?, 'INPUT', ?, 'BINARY', 'LOCAL', ?, 'UPLOAD', 'RECEIVED') returning id
        """;
    final String selectJobStepInstanceSql2 =
        "select related_file_id from batch.job_step_instance where tenant_id = ? and job_task_id = ?";
    final String insertFileRecordSql3 = """
        insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status)
        values ('t2', 'INPUT', ?, 'BINARY', 'LOCAL', ?, 'UPLOAD', 'RECEIVED') returning id
        """;
    final String selectJobStepInstanceSql4 =
        "select related_file_id from batch.job_step_instance where tenant_id = ? and job_task_id = ?";
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    LaunchResponse response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .params(Map.of())
        .build());
    JobInstanceEntity instance = jobInstanceForWorkflowRun(response.instanceNo());
    JobTaskEntity existing = jobTaskMapper
        .selectByQuery(new JobTaskQuery(TENANT, instance.getId(), null, null, null))
        .getFirst();
    Long fileId = jdbcTemplate.queryForObject(
        insertFileRecordSql1,
        Long.class,
        TENANT,
        "bundle-" + seed.requestId(),
        "/bundle/" + seed.requestId());
    JobTaskEntity task = new JobTaskEntity();
    task.setTenantId(TENANT);
    task.setJobInstanceId(instance.getId());
    task.setJobInstanceBizDate(instance.getBizDate());
    task.setJobPartitionId(existing.getJobPartitionId());
    task.setTaskType("DISPATCH");
    task.setTaskSeq(987650);
    task.setTaskStatus(TaskStatus.CREATED.code());
    task.setTaskPayload("{\"bundleSourceFileId\":" + fileId + "}");
    RlsTenantContextHolder.runWithTenant(TENANT, () -> taskCreationService.createTask(task));

    assertThat(jdbcTemplate.queryForObject(
            selectJobStepInstanceSql2, Long.class, TENANT, task.getId()))
        .isEqualTo(fileId);
    Long foreignFile = jdbcTemplate.queryForObject(
        insertFileRecordSql3,
        Long.class,
        "foreign-" + seed.requestId(),
        "/foreign/" + seed.requestId());
    JobTaskEntity mixed = new JobTaskEntity();
    mixed.setTenantId(TENANT);
    mixed.setJobInstanceId(instance.getId());
    mixed.setJobInstanceBizDate(instance.getBizDate());
    mixed.setJobPartitionId(existing.getJobPartitionId());
    mixed.setTaskType("DISPATCH");
    mixed.setTaskSeq(987652);
    mixed.setTaskStatus(TaskStatus.CREATED.code());
    mixed.setTaskPayload("{\"bundleSourceFileId\":" + fileId + ",\"fileId\":" + foreignFile + "}");
    RlsTenantContextHolder.runWithTenant(TENANT, () -> taskCreationService.createTask(mixed));
    assertThat(jdbcTemplate.queryForObject(
            selectJobStepInstanceSql4, Long.class, TENANT, mixed.getId()))
        .isEqualTo(fileId);
    JobTaskEntity foreign = new JobTaskEntity();
    foreign.setTenantId(TENANT);
    foreign.setJobInstanceId(instance.getId());
    foreign.setJobInstanceBizDate(instance.getBizDate());
    foreign.setJobPartitionId(existing.getJobPartitionId());
    foreign.setTaskType("DISPATCH");
    foreign.setTaskSeq(987651);
    foreign.setTaskStatus(TaskStatus.CREATED.code());
    foreign.setTaskPayload("{\"bundleSourceFileId\":" + foreignFile + "}");
    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            TENANT, () -> taskCreationService.createTask(foreign)))
        .hasMessage("error.file.record_not_found");
  }

  @Test
  @DisplayName("任务创建时小数文件号不会关联到整数部分对应的文件")
  void taskCreation_doesNotAssociateFractionalFileId() {
    final String insertFileSql = """
        insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status)
        values (?, 'INPUT', ?, 'BINARY', 'LOCAL', ?, 'UPLOAD', 'RECEIVED') returning id
        """;
    final String selectStepFileSql =
        "select related_file_id from batch.job_step_instance where tenant_id = ? and job_task_id = ?";
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    LaunchResponse response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .params(Map.of())
        .build());
    JobInstanceEntity instance = jobInstanceForWorkflowRun(response.instanceNo());
    JobTaskEntity existing = jobTaskMapper
        .selectByQuery(new JobTaskQuery(TENANT, instance.getId(), null, null, null))
        .getFirst();
    Long fileId = jdbcTemplate.queryForObject(
        insertFileSql,
        Long.class,
        TENANT,
        "fractional-" + seed.requestId(),
        "/fractional/" + seed.requestId());
    JobTaskEntity task = new JobTaskEntity();
    task.setTenantId(TENANT);
    task.setJobInstanceId(instance.getId());
    task.setJobInstanceBizDate(instance.getBizDate());
    task.setJobPartitionId(existing.getJobPartitionId());
    task.setTaskType("DISPATCH");
    task.setTaskSeq(987653);
    task.setTaskStatus(TaskStatus.CREATED.code());
    task.setTaskPayload("{\"fileId\":" + fileId + ".9}");
    RlsTenantContextHolder.runWithTenant(TENANT, () -> taskCreationService.createTask(task));

    assertThat(jdbcTemplate.queryForObject(selectStepFileSql, Long.class, TENANT, task.getId()))
        .isNull();
  }

  @Test
  @DisplayName("任务创建拒绝引用其他租户的文件且不写任务或步骤实例")
  void taskCreation_rejectsForeignTenantRelatedFileBeforeWritingRows() {
    final String insertFileRecordSql = """
      insert into batch.file_record (tenant_id, file_category, file_name, file_format_type, storage_type,\s\
      storage_path, source_type, file_status)\s\
      values ('t2', 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED') returning id\
      """;
    final String selectCountSql = """
      select count(*) from batch.job_task where tenant_id = ? and task_seq = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.job_step_instance where tenant_id = ? and related_file_id = ?\
      """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-task-foreign-file-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);
    JobInstanceEntity instance = jobInstanceForWorkflowRun(response.instanceNo());
    JobTaskEntity existingTask = jobTaskMapper
        .selectByQuery(new JobTaskQuery(TENANT, instance.getId(), null, null, null))
        .get(0);
    Long foreignFileId = jdbcTemplate.queryForObject(
        insertFileRecordSql,
        Long.class,
        "foreign-task-file-" + seed.requestId(),
        "/seed/t2/foreign-task-file-" + seed.requestId());
    JobTaskEntity invalidTask = new JobTaskEntity();
    invalidTask.setTenantId(TENANT);
    invalidTask.setJobInstanceId(instance.getId());
    invalidTask.setJobInstanceBizDate(instance.getBizDate());
    invalidTask.setJobPartitionId(existingTask.getJobPartitionId());
    invalidTask.setTaskType(existingTask.getTaskType());
    invalidTask.setTaskSeq(987654);
    invalidTask.setTaskStatus(TaskStatus.CREATED.code());
    invalidTask.setVersion(0L);
    invalidTask.setTaskPayload("{\"relatedFileId\":" + foreignFileId + "}");

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            TENANT, () -> taskCreationService.createTask(invalidTask)))
        .hasMessage("error.file.record_not_found");

    Integer selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, 987654);
    assertThat(selectedJobTaskCount).isZero();
    Integer selectedJobStepInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, foreignFileId);
    assertThat(selectedJobStepInstanceCount).isZero();
  }

  @Test
  @DisplayName("批量步骤归属约束失败返回冲突并回滚本批任务和步骤, 保留已有运行树")
  void shouldRollbackTasksAndSteps_whenStepOwnershipFailsAfterTaskInsert() {
    final String selectCountSql = """
          select count(*) from batch.job_task
           where tenant_id=? and job_instance_id=? and task_seq in (987655,987656)
          """;
    final String selectCountSql2 = """
        select count(*) from batch.job_step_instance where tenant_id=? and step_code in (?, ?)
        """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    LaunchResponse response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .params(Map.of())
        .build());
    JobInstanceEntity instance = jobInstanceForWorkflowRun(response.instanceNo());
    JobTaskEntity original = jobTaskMapper
        .selectByQuery(new JobTaskQuery(TENANT, instance.getId(), null, null, null))
        .get(0);
    String marker = "step_identity_" + UUID.randomUUID().toString().replace("-", "");
    List<JobTaskEntity> tasks = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      JobTaskEntity task = new JobTaskEntity();
      task.setTenantId(TENANT);
      task.setJobInstanceId(instance.getId());
      task.setJobInstanceBizDate(instance.getBizDate());
      task.setJobPartitionId(original.getJobPartitionId());
      task.setTaskType(original.getTaskType());
      task.setTaskSeq(987655 + i);
      task.setTaskStatus(TaskStatus.CREATED.code());
      task.setVersion(0L);
      task.setTaskPayload("{\"workflowNodeCode\":\"" + marker + i + "\"}");
      tasks.add(task);
    }
    // 较新的运行捕获了 v2；补跑仍必须解析上方记录的准确 v1 来源运行。
    final String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          NEW.job_partition_id := NULL;
          return NEW;
        end
        $$
        """.formatted(marker);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on batch.job_step_instance
          for each row when (NEW.step_code = '%s1') execute function batch.%s()
        """.formatted(marker, marker, marker);
    jdbcTemplate.execute(createTriggerSql);
    try {
      assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
              TENANT, () -> taskCreationService.createTasks(tasks)))
          .isInstanceOfSatisfying(BizException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo(ResultCode.CONFLICT);
            assertThat(failure.getMessageKey()).isEqualTo("error.job_task.reference_changed");
          });
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.job_step_instance\
          """.formatted(marker);
      jdbcTemplate.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(marker);
      jdbcTemplate.execute(dropFunctionSql);
    }
    Long selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, instance.getId());
    assertThat(selectedJobTaskCount).isZero();
    Long selectedJobStepInstanceCount = jdbcTemplate.queryForObject(
        selectCountSql2, Long.class, TENANT, marker + "0", marker + "1");
    assertThat(selectedJobStepInstanceCount).isZero();
    assertThat(jobTaskMapper.selectById(TENANT, original.getId())).isNotNull();
  }

  private JobInstanceEntity jobInstanceForWorkflowRun(String workflowRunId) {
    final String selectJobInstanceIdSql = """
          select node_run.job_instance_id
          from batch.workflow_node_run node_run
          where node_run.workflow_run_id = ?
            and node_run.node_type = 'JOB'
            and node_run.job_instance_id is not null
          """;

    Long jobInstanceId = jdbcTemplate.queryForObject(
        selectJobInstanceIdSql, Long.class, Long.valueOf(workflowRunId));
    return jobInstanceMapper.selectById(TENANT, jobInstanceId);
  }

  /**
   * 长 IT 套件会保留同组的多个在线 worker，launch 可能选择早先用例留下的 worker，而不一定是本用例刚 seed 的实例。因此调用方必须使用
   * task 快照中的 assignedWorkerCode 认领，不能假定 seed worker 就是最终路由结果。
   *
   * <p>认领前同时等待 partition.status==READY，减少前置状态推进与 lease CAS 的瞬时竞争。最长等待 5 秒，超时仍返回当前 task，
   * 由调用方断言输出真实状态。
   */
  private JobTaskEntity assignWorkerWithRetry(JobTaskEntity task, String workerCode) {
    final String selectPartitionStatusSql = """
        select partition_status from batch.job_partition where tenant_id = ? and id = ?
    """;

    JobTaskEntity claimed = task;
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      String partitionStatus = jdbcTemplate.queryForObject(
          selectPartitionStatusSql, String.class, TENANT, task.getJobPartitionId());
      if (!PartitionStatus.READY.code().equals(partitionStatus)) {
        if (!sleepBeforeClaimRetry()) {
          return claimed;
        }
        continue;
      }
      LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
      claimed = taskExecutionService.assignWorker(TENANT, task.getId(), workerCode);
      if (claimed != null && TaskStatus.RUNNING.code().equals(claimed.getTaskStatus())) {
        return claimed;
      }
      if (!sleepBeforeClaimRetry()) {
        return claimed;
      }
    }
    return claimed;
  }

  private boolean sleepBeforeClaimRetry() {
    try {
      Thread.sleep(50L);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
