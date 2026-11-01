package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import io.github.pinpols.batch.orchestrator.application.service.dq.DataQualityRuleApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeOperationService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.controller.request.DataQualityRuleRequest;
import io.github.pinpols.batch.orchestrator.controller.request.WorkerDrainRequest;
import io.github.pinpols.batch.orchestrator.controller.request.WorkerTenantRequest;
import io.github.pinpols.batch.orchestrator.domain.command.DataQualityRuleWriteCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.service.WorkerRegistryServerService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Controller 在 HTTP 边界将持久化 Entity 映射为稳定响应 DTO")
class ControllerEntityBoundaryBehaviorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  @DisplayName("批次日重放的写入、查询和明细端点都返回响应 DTO")
  void shouldMapEveryBatchDayReplayEndpoint() {
    BatchDayReplayService replayService = mock(BatchDayReplayService.class);
    BatchDayReplayController controller = new BatchDayReplayController(
        replayService, mock(BatchDayReplayRangeOperationService.class));
    BatchDayReplaySessionEntity session = replaySession();
    BatchDayReplayEntryEntity entry = replayEntry();

    when(replayService.submit(null)).thenReturn(session);
    when(replayService.listSessions("tenant", "RUNNING", 10)).thenReturn(List.of(session));
    when(replayService.approve("tenant", 1L, "approver")).thenReturn(session);
    when(replayService.cancel("tenant", 1L)).thenReturn(session);
    when(replayService.getSession("tenant", 1L)).thenReturn(session);
    when(replayService.listEntries("tenant", 1L, "RUNNING", 10)).thenReturn(List.of(entry));

    assertThat(controller.submit(null).data().id()).isEqualTo(1L);
    assertThat(controller.list("tenant", "RUNNING", 10).data())
        .extracting("id")
        .containsExactly(1L);
    assertThat(controller.approve(1L, "tenant", "approver").data().id()).isEqualTo(1L);
    assertThat(controller.cancel(1L, "tenant").data().id()).isEqualTo(1L);
    assertThat(controller.detail(1L, "tenant").data().id()).isEqualTo(1L);
    assertThat(controller.entries(1L, "tenant", "RUNNING", 10).data())
        .extracting("id")
        .containsExactly(2L);
  }

  @Test
  @DisplayName("数据质量规则端点隔离请求 DTO、持久化 Entity 与响应 DTO")
  void shouldMapEveryDataQualityRuleEndpoint() {
    DataQualityRuleApplicationService ruleService = mock(DataQualityRuleApplicationService.class);
    DataQualityRuleController controller = new DataQualityRuleController(ruleService);
    DataQualityRuleEntity entity = dataQualityRule();
    DataQualityRuleRequest request = dataQualityRequest(entity);

    when(ruleService.listEnabledByBusinessKey("tenant", "job:")).thenReturn(List.of(entity));
    when(ruleService.get("tenant", 3L)).thenReturn(entity);
    when(ruleService.create(eq("tenant"), any(DataQualityRuleWriteCommand.class)))
        .thenReturn(entity);
    when(ruleService.update(eq("tenant"), eq(3L), any(DataQualityRuleWriteCommand.class)))
        .thenReturn(entity);

    assertThat(controller.list("tenant", "job:").data()).extracting("id").containsExactly(3L);
    assertThat(controller.get(3L, "tenant").data().id()).isEqualTo(3L);
    assertThat(controller.create("tenant", request).data().id()).isEqualTo(3L);
    assertThat(controller.update(3L, "tenant", request).data().id()).isEqualTo(3L);
  }

  @Test
  @DisplayName("结果版本端点覆盖列表、有效版本、详情及状态操作")
  void shouldMapEveryResultVersionEndpoint() {
    ResultVersionQueryService queryService = mock(ResultVersionQueryService.class);
    ResultVersionPromoteService promoteService = mock(ResultVersionPromoteService.class);
    ResultVersionController controller = new ResultVersionController(queryService, promoteService);
    ResultVersionEntity entity = resultVersion();

    when(queryService.listVersions("tenant", "job:JOB", 10)).thenReturn(List.of(entity));
    when(queryService.findEffective("tenant", "job:JOB")).thenReturn(Optional.of(entity));
    when(queryService.findEffective("tenant", "job:MISSING")).thenReturn(Optional.empty());
    when(queryService.getByIdOrThrow("tenant", 4L)).thenReturn(entity);
    when(promoteService.promote("tenant", 4L)).thenReturn(entity);
    when(promoteService.rejectPending("tenant", 4L)).thenReturn(entity);

    assertThat(controller.list("tenant", "job:JOB", 10).data())
        .extracting("id")
        .containsExactly(4L);
    assertThat(controller.effective("tenant", "job:JOB").data().id()).isEqualTo(4L);
    assertThat(controller.effective("tenant", "job:MISSING").data()).isNull();
    assertThat(controller.detail(4L, "tenant").data().id()).isEqualTo(4L);
    assertThat(controller.promote(4L, "tenant").data().id()).isEqualTo(4L);
    assertThat(controller.reject(4L, "tenant").data().id()).isEqualTo(4L);
  }

  @Test
  @DisplayName("调度历史与 Worker 治理端点返回独立响应投影")
  void shouldMapSchedulerHistoryAndWorkerGovernanceEndpoints() {
    TenantSchedulerSnapshotService snapshotService = mock(TenantSchedulerSnapshotService.class);
    SchedulerSnapshotController snapshotController =
        new SchedulerSnapshotController(snapshotService);
    when(snapshotService.history("tenant", 10)).thenReturn(List.of(schedulerSnapshot()));
    assertThat(snapshotController.history("tenant", 10)).extracting("id").containsExactly(5L);

    WorkerRegistryServerService registryService = mock(WorkerRegistryServerService.class);
    WorkerDrainGovernanceService governanceService = mock(WorkerDrainGovernanceService.class);
    WorkerController workerController = new WorkerController(registryService, governanceService);
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    WorkerRegistryEntity worker = worker();
    WorkerHeartbeatDto heartbeat =
        WorkerHeartbeatDto.builder().tenantId("tenant").status("ONLINE").build();
    WorkerTenantRequest tenantRequest = new WorkerTenantRequest("tenant");

    when(registryService.register(any(WorkerHeartbeatDto.class))).thenReturn(worker);
    when(registryService.updateStatus("tenant", "worker", "ONLINE")).thenReturn(worker);
    when(governanceService.startDrain("tenant", "worker", 30)).thenReturn(worker);
    when(governanceService.forceOffline("tenant", "worker")).thenReturn(worker);
    when(governanceService.takeover("tenant", "worker")).thenReturn(worker);
    when(governanceService.warmup("tenant", "worker")).thenReturn(worker);
    JobTaskEntity task = new JobTaskEntity();
    task.setId(7L);
    task.setTenantId("tenant");
    when(governanceService.listClaimedTasks("tenant", "worker")).thenReturn(List.of(task));

    assertThat(workerController.register(heartbeat, httpRequest).id()).isEqualTo(6L);
    assertThat(workerController.updateStatus("worker", heartbeat, httpRequest).id())
        .isEqualTo(6L);
    assertThat(workerController
            .drain("worker", new WorkerDrainRequest("tenant", 30), httpRequest)
            .id())
        .isEqualTo(6L);
    assertThat(
            workerController.forceOffline("worker", tenantRequest, httpRequest).id())
        .isEqualTo(6L);
    assertThat(workerController.takeover("worker", tenantRequest, httpRequest).id())
        .isEqualTo(6L);
    assertThat(workerController.warmup("worker", tenantRequest, httpRequest).id())
        .isEqualTo(6L);
    assertThat(workerController.claimedTasks("worker", "tenant", httpRequest))
        .extracting("id")
        .containsExactly(7L);
  }

  private static BatchDayReplaySessionEntity replaySession() {
    return BatchDayReplaySessionEntity.builder()
        .id(1L)
        .tenantId("tenant")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, 1, 1))
        .status("RUNNING")
        .createdAt(NOW)
        .updatedAt(NOW)
        .build();
  }

  private static BatchDayReplayEntryEntity replayEntry() {
    return BatchDayReplayEntryEntity.builder()
        .id(2L)
        .sessionId(1L)
        .tenantId("tenant")
        .jobCode("JOB")
        .status("RUNNING")
        .createdAt(NOW)
        .updatedAt(NOW)
        .build();
  }

  private static DataQualityRuleEntity dataQualityRule() {
    DataQualityRuleEntity entity = new DataQualityRuleEntity();
    entity.setId(3L);
    entity.setTenantId("tenant");
    entity.setRuleCode("RULE");
    entity.setEnabled(true);
    entity.setCreatedAt(NOW);
    return entity;
  }

  private static DataQualityRuleRequest dataQualityRequest(DataQualityRuleEntity entity) {
    return new DataQualityRuleRequest(
        entity.getId(),
        entity.getTenantId(),
        entity.getRuleCode(),
        entity.getRuleName(),
        entity.getRuleType(),
        entity.getScopeBusinessKey(),
        entity.getExpression(),
        entity.getThresholdJson(),
        entity.getSeverity(),
        entity.getEnabled(),
        entity.getDescription(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static ResultVersionEntity resultVersion() {
    return ResultVersionEntity.builder()
        .id(4L)
        .tenantId("tenant")
        .businessKey("job:JOB")
        .versionNo(1)
        .status("EFFECTIVE")
        .generatedAt(NOW)
        .build();
  }

  private static TenantSchedulerSnapshotEntity schedulerSnapshot() {
    return new TenantSchedulerSnapshotEntity(
        5L,
        "tenant",
        NOW,
        "GROUP",
        "POLICY",
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        "DAILY",
        8,
        JsonbString.of("{\"ready\":true}"));
  }

  private static WorkerRegistryEntity worker() {
    return new WorkerRegistryEntity(
        6L, "tenant", "worker", "group", null, null, "ONLINE", NOW, 0, 10, null, null);
  }
}
