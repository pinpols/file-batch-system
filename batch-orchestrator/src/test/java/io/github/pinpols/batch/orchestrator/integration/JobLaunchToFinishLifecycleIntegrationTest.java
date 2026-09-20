package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
 *   <li>{@link LaunchService#launch} 创建 job_instance + 分区 + 任务 + outbox_event。
 *   <li>{@link TaskExecutionService#assignWorker} 将任务转为 RUNNING。
 *   <li>{@link TaskExecutionService#applyTaskOutcome} 以 success=true 推进任务， 最终使 job_instance 达到
 *       SUCCESS。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobLaunchToFinishLifecycleIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private LaunchService launchService;

  @Autowired
  private TaskExecutionService taskExecutionService;

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
  void launchThenClaimThenReport_jobInstanceReachesSuccess() {
    LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    // 1) Launch
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .jobCode(seed.jobCode())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-lifecycle-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = launchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    assertThat(jobInstance).isNotNull();
    assertThat(jobInstance.getInstanceStatus())
        .isIn(
            JobInstanceStatus.READY.code(),
            JobInstanceStatus.RUNNING.code(),
            JobInstanceStatus.WAITING.code());

    // 2) Claim the task
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
        "select current_invocation_id from batch.job_partition where id = ?",
        String.class,
        claimed.getJobPartitionId());
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
  void launchThenClaimThenReport_failureTransitionsTaskToFailed() {
    LaunchSeed seed = LaunchIntegrationFixture.prepareLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .jobCode(seed.jobCode())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-fail-" + seed.requestId())
        .params(Map.of())
        .build();
    LaunchResponse response = launchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
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
        "select current_invocation_id from batch.job_partition where id = ?",
        String.class,
        claimed.getJobPartitionId());
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

  /**
   * 长 IT 套件会保留同组的多个在线 worker，launch 可能选择早先用例留下的 worker，而不一定是本用例刚 seed 的实例。因此调用方必须使用
   * task 快照中的 assignedWorkerCode 认领，不能假定 seed worker 就是最终路由结果。
   *
   * <p>认领前同时等待 partition.status==READY，减少前置状态推进与 lease CAS 的瞬时竞争。最长等待 5 秒，超时仍返回当前 task，
   * 由调用方断言输出真实状态。
   */
  private JobTaskEntity assignWorkerWithRetry(JobTaskEntity task, String workerCode) {
    JobTaskEntity claimed = task;
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      String partitionStatus = jdbcTemplate.queryForObject(
          "select partition_status from batch.job_partition where tenant_id = ? and id = ?",
          String.class,
          TENANT,
          task.getJobPartitionId());
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
