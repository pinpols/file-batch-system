package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.context.TaskOutcomePersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.integration.support.WorkerRegistryCacheTestSupport;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：Worker 认领 → 续约 → 进度上报 → 完成。
 *
 * <p>验证完整的 Worker 侧交互流程：
 *
 * <ol>
 *   <li>Worker 调用 <em>claim</em>（{@link TaskExecutionService#assignWorker}）—— 任务转为 RUNNING。
 *   <li>Worker 调用 <em>renew</em>（{@link TaskExecutionService#renewTaskLease}）—— 续约延期。
 *   <li>Worker 调用 <em>report success</em>（{@link TaskExecutionService#applyTaskOutcome}）—— 任务达到
 *       SUCCESS。
 *   <li>分区和 job_instance 均达到 SUCCESS 终态。
 * </ol>
 *
 * <p>同时验证不同 Worker 的第二次认领尝试被拒绝（冲突语义）。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("工作节点认领到完成的完整链路,验证认领,续约,成功上报后任务与分区及作业实例逐级达到成功终态,并覆盖非认领者续约被拒绝的冲突语义")
class WorkerClaimProgressCompleteIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private TaskExecutionService taskExecutionService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private WorkerRegistryCache workerRegistryCache;

  @BeforeEach
  void refreshWorkersForClaim() {
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, TENANT);
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  @DisplayName("认领后由原工作节点续约并上报成功时,任务与分区及作业实例应逐级达到成功终态,非认领者的续约尝试应被拒绝")
  void worker_claim_renewLease_reportSuccess_taskAndPartitionAndInstanceReachSuccess() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    // 1) Launch
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-wk-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);
    assertThat(response.instanceNo()).isNotBlank();

    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    assertThat(jobInstance).isNotNull();

    Long taskInstanceId = jobInstance.getId();
    JobTaskQuery taskQuery = JobTaskQuery.ofJobInstance(TENANT, taskInstanceId);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(taskQuery);
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);

    TaskOutcomePersistenceContext outcomeContext =
        jobTaskMapper.selectOutcomePersistenceContext(TENANT, task.getId());
    assertThat(outcomeContext.getTask().getId()).isEqualTo(task.getId());
    assertThat(outcomeContext.getTask().getTaskPayload()).isEqualTo(task.getTaskPayload());
    assertThat(outcomeContext.getPartition().getJobInstanceId()).isEqualTo(jobInstance.getId());

    Long partitionInstanceId = jobInstance.getId();
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(TENANT, partitionInstanceId);
    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(partitionQuery);
    assertThat(partitions).isNotEmpty();
    JobPartitionEntity partition = partitions.get(0);

    // 2) Worker 认领任务
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
    JobTaskEntity claimed =
        taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(seed.workerCode());

    // 分区也应为 RUNNING 状态
    JobPartitionEntity runningPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
    assertThat(runningPartition.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());
    assertThat(runningPartition.getCurrentInvocationId()).isNotBlank();

    // 3) Worker renews lease (simulates heartbeat / progress ping)
    boolean renewed = taskExecutionService.renewTaskLease(
        TENANT, task.getId(), seed.workerCode(), runningPartition.getCurrentInvocationId());
    assertThat(renewed).isTrue();

    // 其他 Worker 不得抢占该租约。
    boolean stolenByRogue =
        taskExecutionService.renewTaskLease(TENANT, task.getId(), "rogue-worker", null);
    assertThat(stolenByRogue).isFalse();

    // 4) Worker reports success (progress complete)
    TaskOutcomeCommand successOutcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(true)
        .resultSummary("{\"records\":100,\"status\":\"processed\"}")
        .partitionInvocationId(runningPartition.getCurrentInvocationId())
        .build();
    taskExecutionService.applyTaskOutcome(successOutcome);

    // 5) Verify task SUCCESS
    JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, task.getId());
    assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.code());

    // 6) Verify partition SUCCESS
    JobPartitionEntity finishedPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
    assertThat(finishedPartition.getPartitionStatus()).isEqualTo(PartitionStatus.SUCCESS.code());

    // 7) Verify job_instance SUCCESS
    JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
    assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.SUCCESS.code());
  }

  @Test
  @DisplayName("Worker 成功回报引用另一租户文件时整笔回报回滚并拒绝跨租户步骤关联")
  void reportSuccess_rejectsRelatedFileFromAnotherTenant() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-cross-file-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);
    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    JobTaskQuery taskQuery = JobTaskQuery.ofJobInstance(TENANT, jobInstanceId);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(taskQuery);
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);
    JobPartitionQuery partitionQuery = JobPartitionQuery.ofJobInstance(TENANT, jobInstanceId);
    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(partitionQuery);
    JobPartitionEntity partition = partitions.get(0);

    String insertForeignOutputFileSql = """
        insert into batch.file_record(
          tenant_id, file_category, file_name, file_format_type, storage_type,
          storage_path, source_type, file_status
        ) values ('t2', 'OUTPUT', 'foreign-output.bin', 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED')
        returning id
        """;
    String foreignOutputStoragePath = "cross-tenant-output/" + seed.requestId();
    Long foreignFileId = jdbcTemplate.queryForObject(
        insertForeignOutputFileSql, Long.class, foreignOutputStoragePath);

    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
    taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    JobPartitionEntity runningPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
    String resultSummary = "{\"records\":1,\"relatedFileId\":" + foreignFileId + "}";
    TaskOutcomeCommand outcome = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(true)
        .resultSummary(resultSummary)
        .partitionInvocationId(runningPartition.getCurrentInvocationId())
        .build();

    assertThatThrownBy(() -> taskExecutionService.applyTaskOutcome(outcome))
        .isInstanceOf(BizException.class);

    assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());
    assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.RUNNING.code());
    assertThat(jobInstanceMapper.selectById(TENANT, jobInstanceId).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.RUNNING.code());
    String relatedStepFileCountSql = """
        select count(*) from batch.job_step_instance
        where job_task_id = ? and related_file_id is not null
        """;
    Integer relatedStepFileCount =
        jdbcTemplate.queryForObject(relatedStepFileCountSql, Integer.class, task.getId());
    assertThat(relatedStepFileCount).isZero();
  }

  @Test
  @DisplayName("结果回报的小数文件号不会关联整数部分对应的文件")
  void report_doesNotAssociateFractionalFileId() {
    final String insertFileSql = """
        insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
          storage_type, storage_path, source_type, file_status)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
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
    Long jobId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobTaskEntity task =
        jobTaskMapper.selectByQuery(JobTaskQuery.ofJobInstance(TENANT, jobId)).getFirst();
    Long fileId = jdbcTemplate.queryForObject(
        insertFileSql,
        Long.class,
        TENANT,
        "fractional-" + seed.requestId(),
        "/fractional/" + seed.requestId());
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
    taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, task.getJobPartitionId());
    TaskOutcomeCommand successfulReport = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(true)
        .resultSummary("{\"relatedFileId\":" + fileId + ".9}")
        .partitionInvocationId(partition.getCurrentInvocationId())
        .build();
    taskExecutionService.applyTaskOutcome(successfulReport);

    assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jdbcTemplate.queryForObject(selectStepFileSql, Long.class, TENANT, task.getId()))
        .isNull();
  }

  @Test
  @DisplayName("已有认领者时另一工作节点再次认领,应返回同一任务行且原认领者保持不变,不产生第二个运行中任务")
  void secondWorkerClaim_afterFirstClaim_returnsSameTask_notASecondRunning() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest launchRequest2 = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-wk2-" + seed.requestId())
        .params(Map.of())
        .build();
    var response = workflowRunLaunchService.launch(launchRequest2);

    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    Long taskInstanceId = jobInstance.getId();
    JobTaskQuery taskQuery = JobTaskQuery.ofJobInstance(TENANT, taskInstanceId);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(taskQuery);
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);

    // 第一次认领成功
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
    JobTaskEntity first =
        taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    assertThat(first.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());

    // 不同 Worker 的第二次认领：应返回任务行，但 worker code
    // 应保持为第一个认领者（不会被覆盖）
    JobTaskEntity second = taskExecutionService.assignWorker(TENANT, task.getId(), "other-worker");
    assertThat(second).isNotNull();
    assertThat(second.getAssignedWorkerCode()).isEqualTo(seed.workerCode());
  }
}
