package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
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
class WorkerClaimProgressCompleteIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;

  @Autowired private TaskExecutionService taskExecutionService;

  @Autowired private JobInstanceMapper jobInstanceMapper;

  @Autowired private JobPartitionMapper jobPartitionMapper;

  @Autowired private JobTaskMapper jobTaskMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void worker_claim_renewLease_reportSuccess_taskAndPartitionAndInstanceReachSuccess() {
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
                "trace-wk-" + seed.requestId(),
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

    List<JobPartitionEntity> partitions =
        jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(TENANT, jobInstance.getId(), null, null));
    assertThat(partitions).isNotEmpty();
    JobPartitionEntity partition = partitions.get(0);

    // 2) Worker claims the task
    JobTaskEntity claimed =
        taskExecutionService.assignWorker(TENANT, task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(claimed.getAssignedWorkerCode()).isEqualTo(seed.workerCode());

    // 分区也应为 RUNNING 状态
    JobPartitionEntity runningPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
    assertThat(runningPartition.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());

    // 3) Worker renews lease (simulates heartbeat / progress ping)
    boolean renewed = taskExecutionService.renewTaskLease(TENANT, task.getId(), seed.workerCode());
    assertThat(renewed).isTrue();

    // A different worker should not be able to steal the lease
    boolean stolenByRogue =
        taskExecutionService.renewTaskLease(TENANT, task.getId(), "rogue-worker");
    assertThat(stolenByRogue).isFalse();

    // 4) Worker reports success (progress complete)
    taskExecutionService.applyTaskOutcome(
        new TaskOutcomeCommand(
            TENANT,
            task.getId(),
            null,
            true,
            "{\"records\":100,\"status\":\"processed\"}",
            null,
            null,
            null,
            null,
            null,
            null));

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
  void secondWorkerClaim_afterFirstClaim_returnsSameTask_notASecondRunning() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    launchService.launch(
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            BIZ_DATE,
            TriggerType.API,
            seed.requestId(),
            "trace-wk2-" + seed.requestId(),
            Map.of()));

    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(
            new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
    assertThat(tasks).isNotEmpty();
    JobTaskEntity task = tasks.get(0);

    // 第一次认领成功
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
