package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R3-P1-10 一致性回归(对抗审查 #6):report 纳入 partition invocation fence。
 *
 * <p>场景:task 被 worker#1 以 invocation I1 认领(CLAIM)→ 租约被回收(reclaim)后同一 task/partition 被重新以 invocation
 * I2 派发(double-executor:task 仍处 RUNNING,partition.current_invocation_id 从 I1 换成 I2)。此时 worker#1
 * 的**迟到 report** 携带旧 I1(或根本不带 invocationId)。
 *
 * <p>此前 report 是唯一没强制 fence 的租约操作("带 invocationId 才校验,缺就跳过"),迟到 report 会绕过守卫、经 {@code finishTask}
 * CAS(RUNNING + 报告时重读的当前 version)命中已被 I2 重领的行,用陈旧结果把任务终结,丢掉 I2 的真实结果。
 *
 * <p>本测试断言收紧后的行为:
 *
 * <ol>
 *   <li>携带旧 I1 的迟到 report → 拒绝(CONFLICT / error.task.invocation_mismatch),且不写任何状态(task 仍 RUNNING,
 *       partition 仍 RUNNING 且 invocation 仍为 I2)。
 *   <li>缺失 invocationId 的迟到 report(镜像未回填的陈旧 worker)→ 同样被拒。
 *   <li>I2 的真实 report → 正常推进(task→SUCCESS、partition→SUCCESS),I2 的结果不丢。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReportInvocationFenceIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private JobInstanceMapper jobInstanceMapper;
  @Autowired private JobPartitionMapper jobPartitionMapper;
  @Autowired private JobTaskMapper jobTaskMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void refreshWorkersForClaim() {
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  @DisplayName("reclaim 重派后旧 invocation(及缺失)的迟到 report 被 fence 拒,I2 真实结果不丢")
  void staleReportAfterReclaim_isRejectedByInvocationFence_andI2ResultWins() {
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
            .traceId("trace-fence-" + seed.requestId())
            .params(Map.of())
            .build();
    launchService.launch(launchRequest);

    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    assertThat(jobInstance).isNotNull();

    JobTaskEntity task =
        jobTaskMapper
            .selectByQuery(new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null))
            .get(0);
    JobPartitionEntity partition =
        jobPartitionMapper
            .selectByQuery(new JobPartitionQuery(TENANT, jobInstance.getId(), null, null))
            .get(0);

    // 1) worker#1 认领 → partition.current_invocation_id = I1
    JobTaskEntity claimed = assignWorkerWithRetry(task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    String invocationI1 =
        jobPartitionMapper.selectById(TENANT, partition.getId()).getCurrentInvocationId();
    assertThat(invocationI1).isNotBlank();

    // 2) 模拟 reclaim 重派:同一 task/partition 被重新以 I2 领取(task 保持 RUNNING = double-executor 窗口)。
    //    直接改写 current_invocation_id 复现"新 invocation 已就位、旧 worker 仍在跑"的竞态,无需真起第二个 worker。
    String invocationI2 = "inv-reclaim-" + System.nanoTime();
    int moved =
        jdbcTemplate.update(
            "update batch.job_partition set current_invocation_id = ? where tenant_id = ? and id ="
                + " ?",
            invocationI2,
            TENANT,
            partition.getId());
    assertThat(moved).isEqualTo(1);

    // 3) worker#1 的迟到 report 带旧 I1 → 必须被 fence 拒,且不写任何状态。
    TaskOutcomeCommand staleWithOldInvocation =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(task.getId())
            .success(true)
            .resultSummary("{\"stale\":\"I1 result must be dropped\"}")
            .partitionInvocationId(invocationI1)
            .build();
    assertThatThrownBy(() -> taskExecutionService.applyTaskOutcome(staleWithOldInvocation))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> {
              assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT);
              assertThat(ex.getMessageKey()).isEqualTo("error.task.invocation_mismatch");
            });

    // 4) 迟到 report 缺失 invocationId(镜像未回填的陈旧 worker)→ 同样被拒。
    TaskOutcomeCommand staleMissingInvocation =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(task.getId())
            .success(true)
            .resultSummary("{\"stale\":\"missing invocation must be dropped\"}")
            .build();
    assertThatThrownBy(() -> taskExecutionService.applyTaskOutcome(staleMissingInvocation))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> {
              assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT);
              assertThat(ex.getMessageKey()).isEqualTo("error.task.invocation_mismatch");
            });

    // 两次被拒后:task 仍 RUNNING、partition 仍 RUNNING 且 invocation 仍是 I2(旧结果没污染状态)。
    assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());
    JobPartitionEntity afterStale = jobPartitionMapper.selectById(TENANT, partition.getId());
    assertThat(afterStale.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());
    assertThat(afterStale.getCurrentInvocationId()).isEqualTo(invocationI2);

    // 5) I2 的真实 report → 正常推进,I2 结果落地(task→SUCCESS、partition→SUCCESS)。
    TaskOutcomeCommand i2Report =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(task.getId())
            .success(true)
            .resultSummary("{\"records\":42,\"invocation\":\"I2 real result\"}")
            .partitionInvocationId(invocationI2)
            .build();
    taskExecutionService.applyTaskOutcome(i2Report);

    assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
  }

  /** 复刻 lifecycle 测试的 CAS 抖动重试:延长重试 + 每拍刷新 worker 可见性,直至 task RUNNING。 */
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
