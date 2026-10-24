package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.FinishTaskParam;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 验证 {@code JobTaskMapper.finishTask} 带有 {@code WHERE task_status = expectedStatus} 的 CAS 守卫：
 * 当两个并发线程竞争完成同一个 RUNNING 任务时，恰好一个获得最终实体，另一个因 CAS 未命中返回 {@code null}。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("任务完成并发防护:两个线程同时完成同一运行中任务时仅一个更新成功,最终任务状态收敛为成功")
class ConcurrentTaskFinishIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 12, 19);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("两个线程并发完成同一运行中任务时只有一个成功,另一个因状态校验未命中而失败")
  void finishTask_onlyOneWins_whenTwoThreadsRaceConcurrently() throws Exception {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "GENERAL", "DEFAULT", TriggerType.MANUAL, BIZ_DATE);
    LaunchResponse response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.MANUAL)
        .requestId(seed.requestId())
        .traceId("trace-concurrent-finish")
        .params(Map.of())
        .build());
    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    String firstJobTaskIdSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ? order by id limit 1
        """;
    Long taskId = jdbcTemplate.queryForObject(firstJobTaskIdSql, Long.class, TENANT, jobInstanceId);
    assertThat(taskId).isNotNull();
    String markJobInstanceRunningSql = """
        update batch.job_instance set instance_status = 'RUNNING' where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(markJobInstanceRunningSql, TENANT, jobInstanceId);
    String markJobTaskRunningSql = """
        update batch.job_task set task_status = 'RUNNING' where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(markJobTaskRunningSql, TENANT, taskId);

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);

    List<Future<JobTaskEntity>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(pool.submit(() -> {
        startGate.await();
        return transactionTemplate.execute(
            status -> jobTaskMapper.finishTask(FinishTaskParam.builder()
                .tenantId(TENANT)
                .id(taskId)
                .taskStatus(TaskStatus.SUCCESS.code())
                .expectedStatus(TaskStatus.RUNNING.code())
                .resultSummary(null)
                .errorCode(null)
                .errorMessage(null)
                .expectedVersion(0L)
                .build()));
      }));
    }

    startGate.countDown();

    int totalUpdated = 0;
    for (Future<JobTaskEntity> f : futures) {
      if (f.get() != null) {
        totalUpdated++;
      }
    }
    pool.shutdown();

    assertThat(totalUpdated).as("exactly one thread must win the CAS update").isEqualTo(1);

    String finalTaskStatusSql = """
        select task_status from batch.job_task where id = ?
    """;
    String finalStatus = jdbcTemplate.queryForObject(finalTaskStatusSql, String.class, taskId);
    assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS.code());
  }
}
