package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimBatchResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimItemPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimResultPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskExecutionReportDto;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportBatchResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportResultPayload;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.integration.support.WorkerRegistryCacheTestSupport;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-046 Phase 2 端点级集成测试(真 PG):{@code claim-batch}(2.1)+ {@code report-batch}(2.2)。
 *
 * <p>区别于 {@code TaskControllerApplicationServiceTest}(mock service 的单测),本测试真起 orchestrator +
 * Testcontainers PG,走 {@link TaskControllerApplicationService#claimBatch}/{@link
 * TaskControllerApplicationService#reportBatch} 的完整路径(真 {@code assignWorker} CAS、{@code
 * loadEffectiveConfig}、{@code self.report} 逐项独立事务),验证此前只手动真栈验过的两件事:
 *
 * <ol>
 *   <li><b>2.1 批量认领</b>:一次 claim-batch 认领 K 个独立 task,逐项结果;已被领走的项 {@code claimed=false} 而非抛异常。
 *   <li><b>2.2 批内部分失败隔离</b>:report-batch 里某项失败只标记该项、不回滚整批,成功项照常落 SUCCESS。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TaskBatchClaimReportIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;
  @Autowired private TaskControllerApplicationService taskControllerApplicationService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private JobInstanceMapper jobInstanceMapper;
  @Autowired private JobPartitionMapper jobPartitionMapper;
  @Autowired private JobTaskMapper jobTaskMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkerRegistryCache workerRegistryCache;

  @BeforeEach
  void refreshWorkers() {
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, TENANT);
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  /**
   * 启动一个 IMPORT 单作业,返回其唯一 task(及其 partition)。
   *
   * <p>每个作业用**独立 worker_group**(group 内仅 1 个 worker)—— 多个同组 ONLINE worker 时 orchestrator 的 worker
   * 选择会按负载挑定某一个,用别的同组 worker 去 claim 会落空(claimed=false)。 独立 group 保证每个 task 只有唯一可选 worker,claim
   * 结果确定(本机偶发对齐、CI 必现该非确定性)。
   */
  private LaunchedTask launchOne(int idx) {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate,
            TENANT,
            "IMPORT",
            "ITGRP_" + idx + "_" + System.nanoTime(),
            TriggerType.API);
    LaunchRequest req =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(seed.jobCode())
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.API)
            .requestId(seed.requestId())
            .traceId("trace-batch-" + idx + "-" + seed.requestId())
            .params(Map.of())
            .build();
    launchService.launch(req);

    JobInstanceEntity instance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    JobTaskEntity task =
        jobTaskMapper
            .selectByQuery(new JobTaskQuery(TENANT, instance.getId(), null, null, null))
            .get(0);
    JobPartitionEntity partition =
        jobPartitionMapper
            .selectByQuery(new JobPartitionQuery(TENANT, instance.getId(), null, null))
            .get(0);
    return new LaunchedTask(seed, instance.getId(), task.getId(), partition.getId());
  }

  @Test
  @DisplayName("2.1+2.2:批量认领 K 个独立 task,report-batch 混合成功/失败逐项独立(真 PG)")
  void claimBatch_thenReportBatch_mixedOutcome_appliesPerItemIndependently() {
    // arrange:启动 3 个独立 IMPORT 作业 → 3 个独立 task
    List<LaunchedTask> launched = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      launched.add(launchOne(i));
    }
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);

    // act 1:一次 claim-batch 认领全部 3 个(各用各自 worker)
    List<TaskClaimItemPayload> claimItems = new ArrayList<>();
    for (LaunchedTask lt : launched) {
      claimItems.add(new TaskClaimItemPayload(TENANT, lt.taskId(), lt.seed().workerCode(), null));
    }
    TaskClaimBatchResponse claimResp =
        taskControllerApplicationService.claimBatch(new TaskClaimBatchRequest(claimItems));

    // assert 1:逐项 claimed=true + config 非空,partition 全 RUNNING
    assertThat(claimResp.results()).hasSize(3);
    assertThat(claimResp.results()).allMatch(TaskClaimResultPayload::claimed);
    assertThat(claimResp.results()).allMatch(r -> r.config() != null);
    for (LaunchedTask lt : launched) {
      JobPartitionEntity p = jobPartitionMapper.selectById(TENANT, lt.partitionId());
      assertThat(p.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());
      assertThat(p.getCurrentInvocationId()).isNotBlank();
    }

    // act 2:report-batch —— 前 2 个成功,第 3 个失败(测批内部分失败隔离)
    List<TaskExecutionReportDto> reportItems = new ArrayList<>();
    for (int i = 0; i < launched.size(); i++) {
      LaunchedTask lt = launched.get(i);
      JobPartitionEntity p = jobPartitionMapper.selectById(TENANT, lt.partitionId());
      TaskExecutionReportDto dto = new TaskExecutionReportDto();
      dto.setTaskId(lt.taskId());
      dto.setTenantId(TENANT);
      dto.setWorkerId(lt.seed().workerCode());
      dto.setPartitionInvocationId(p.getCurrentInvocationId());
      if (i < 2) {
        dto.setSuccess(true);
        dto.setResultSummary("{\"records\":" + (i + 1) + "}");
      } else {
        dto.setSuccess(false);
        dto.setCode("E_IT_FAIL");
        dto.setMessage("intentional failure for per-item isolation");
      }
      reportItems.add(dto);
    }
    TaskReportBatchResponse reportResp =
        taskControllerApplicationService.reportBatch(new TaskReportBatchRequest(reportItems));

    // assert 2:三项 outcome 都成功推进(ok=true,含失败上报本身也是合法推进)
    assertThat(reportResp.results()).hasSize(3);
    assertThat(reportResp.results()).allMatch(TaskReportResultPayload::ok);

    // assert 3:成功项 partition→SUCCESS、失败项 partition→FAILED(逐项独立、互不影响)
    assertThat(
            jobPartitionMapper
                .selectById(TENANT, launched.get(0).partitionId())
                .getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
    assertThat(
            jobPartitionMapper
                .selectById(TENANT, launched.get(1).partitionId())
                .getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
    assertThat(
            jobPartitionMapper
                .selectById(TENANT, launched.get(2).partitionId())
                .getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());
  }

  @Test
  @DisplayName("2.1:已被并发对手领走的项在 claim-batch 里记 claimed=false,其余项正常认领(真 PG)")
  void claimBatch_skipsAlreadyClaimedItem_withoutFailingOthers() {
    LaunchedTask a = launchOne(0);
    LaunchedTask b = launchOne(1);
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);

    // 先让 a 被它自己的 worker 单条认领(模拟并发对手已领走)
    JobTaskEntity claimedA =
        taskExecutionService.assignWorker(TENANT, a.taskId(), a.seed().workerCode());
    assertThat(claimedA).isNotNull();

    // claim-batch 用「另一个 worker」尝试认领 a + b:a 已被领走 → claimed=false;b 正常 → claimed=true
    List<TaskClaimItemPayload> items =
        List.of(
            new TaskClaimItemPayload(TENANT, a.taskId(), b.seed().workerCode(), null),
            new TaskClaimItemPayload(TENANT, b.taskId(), b.seed().workerCode(), null));
    TaskClaimBatchResponse resp =
        taskControllerApplicationService.claimBatch(new TaskClaimBatchRequest(items));

    assertThat(resp.results()).hasSize(2);
    TaskClaimResultPayload ra =
        resp.results().stream()
            .filter(r -> r.taskId().equals(a.taskId()))
            .findFirst()
            .orElseThrow();
    TaskClaimResultPayload rb =
        resp.results().stream()
            .filter(r -> r.taskId().equals(b.taskId()))
            .findFirst()
            .orElseThrow();
    assertThat(ra.claimed()).isFalse(); // a 被对手领走,逐项 skip 不抛异常
    assertThat(rb.claimed()).isTrue(); // b 正常领到
    assertThat(rb.config()).isNotNull();
  }

  private record LaunchedTask(LaunchSeed seed, Long instanceId, Long taskId, Long partitionId) {}
}
