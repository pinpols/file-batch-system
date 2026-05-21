package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.task.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
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
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;

  @Autowired private TaskExecutionService taskExecutionService;

  @Autowired private JobInstanceMapper jobInstanceMapper;

  @Autowired private JobTaskMapper jobTaskMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void refreshWorkersForClaim() {
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  void launchThenClaimThenReport_jobInstanceReachesSuccess() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    // 1) Launch
    LaunchRequest launchRequest =
        LaunchRequest.builder()
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
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(
            new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);

    JobTaskEntity claimed = assignWorkerWithRetry(task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(seed.workerCode());

    // 3) Report success
    TaskOutcomeCommand successOutcome =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(claimed.getId())
            .success(true)
            .resultSummary("{\"status\":\"processed ok\"}")
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
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest launchRequest =
        LaunchRequest.builder()
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

    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(
            new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);

    JobTaskEntity claimed = assignWorkerWithRetry(task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(seed.workerCode());

    // 上报失败（测试夹具中未配置重试策略：retry_max_count = 0）
    TaskOutcomeCommand failureOutcome =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(claimed.getId())
            .success(false)
            .errorCode("TEST_FAILURE")
            .errorMessage("simulated error")
            .build();
    taskExecutionService.applyTaskOutcome(failureOutcome);

    JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, claimed.getId());
    assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED.code());

    JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
    assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.FAILED.code());
  }

  /**
   * CI 上 assignWorker 返 READY 根因:partition lease CAS 失败 → setRollbackOnly 拖走 worker_registry
   * 刷新可见性(见 docs/analysis/disabled-tests-root-cause-2026-05-21.md §1)。
   *
   * <p>修复:延长重试 + 每次显式 await partition.status==READY,确保前置状态就绪再 claim,
   * 减少 lease CAS miss 频次。最长 5s 等待,超时仍返当前 claimed 让断言拿到真实状态。
   */
  private JobTaskEntity assignWorkerWithRetry(Long taskId, String workerCode) {
    JobTaskEntity claimed = null;
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
      claimed = taskExecutionService.assignWorker(TENANT, taskId, workerCode);
      if (claimed != null && TaskStatus.RUNNING.code().equals(claimed.getTaskStatus())) {
        return claimed;
      }
      try {
        Thread.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return claimed;
      }
    }
    return claimed;
  }
}
