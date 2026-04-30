package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
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
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    // ADR-010: 默认 true 切异步路径后,orchestrator 内的 TriggerLaunchConsumer 会启 Kafka listener,
    // 跑同 JVM 的真 Kafka 容器引入 race(本测试只走 LaunchService 同步生命周期);显式关掉。
    properties = {"batch.trigger.async-launch.enabled=false"})
class JobLaunchToFinishLifecycleIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;

  @Autowired private TaskExecutionService taskExecutionService;

  @Autowired private JobInstanceMapper jobInstanceMapper;

  @Autowired private JobTaskMapper jobTaskMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void launchThenClaimThenReport_jobInstanceReachesSuccess() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    // 1) Launch
    LaunchResponse response =
        launchService.launch(
            new LaunchRequest(
                TENANT,
                seed.jobCode(),
                BIZ_DATE,
                TriggerType.API,
                seed.requestId(),
                "trace-lifecycle-" + seed.requestId(),
                Map.of()));

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

    JobTaskEntity claimed =
        taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(seed.workerCode());

    // 3) Report success
    taskExecutionService.applyTaskOutcome(
        new TaskOutcomeCommand(
            TENANT,
            task.getId(),
            null,
            true,
            "{\"status\":\"processed ok\"}",
            null,
            null,
            null,
            null,
            null,
            null));

    // 4) Verify final task status
    JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, task.getId());
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

    LaunchResponse response =
        launchService.launch(
            new LaunchRequest(
                TENANT,
                seed.jobCode(),
                BIZ_DATE,
                TriggerType.API,
                seed.requestId(),
                "trace-fail-" + seed.requestId(),
                Map.of()));

    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    assertThat(jobInstance).isNotNull();

    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(
            new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);

    taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());

    // 上报失败（测试夹具中未配置重试策略：retry_max_count = 0）
    taskExecutionService.applyTaskOutcome(
        new TaskOutcomeCommand(
            TENANT,
            task.getId(),
            null,
            false,
            null,
            "TEST_FAILURE",
            "simulated error",
            null,
            null,
            null,
            null));

    JobTaskEntity finishedTask = jobTaskMapper.selectById(TENANT, task.getId());
    assertThat(finishedTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED.code());

    JobInstanceEntity finishedInstance = jobInstanceMapper.selectById(TENANT, jobInstance.getId());
    assertThat(finishedInstance.getInstanceStatus()).isEqualTo(JobInstanceStatus.FAILED.code());
  }
}
