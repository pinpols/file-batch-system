package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.param.CustomTaskTypeUpsertParam;
import io.github.pinpols.batch.orchestrator.mapper.ForensicExportLogMapper.CompletionParam;
import io.github.pinpols.batch.orchestrator.mapper.ForensicExportLogMapper.FailureParam;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper.ArchiveParam;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper.SupersedeParam;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper.TransitionParam;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Orchestrator Mapper 状态默认方法:固定状态与调用方参数保持一致")
class MapperStatusDefaultsTest {

  private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

  @Test
  @DisplayName("告警与批次日入口装配完整的状态参数")
  void shouldDelegateAlertAndBatchDayStatuses() {
    AlertEventMapper alert = mock(AlertEventMapper.class, CALLS_REAL_METHODS);
    AlertEventEntity alertEntity = mock(AlertEventEntity.class);
    doReturn(1).when(alert).upsert(alertEntity, "OPEN");
    doReturn(List.of()).when(alert).selectOverdueByStatus(10, 3, 15, "OPEN");
    doReturn(1).when(alert).incrementEscalationTier(5L, "tenant-a", 2, "OPEN");
    doReturn(List.of()).when(alert).selectByStatusForReemit("OPEN", 15);

    assertThat(alert.insertOrMerge(alertEntity)).isEqualTo(1);
    assertThat(alert.selectOverdueForEscalation(10, 3, 15)).isEmpty();
    assertThat(alert.markEscalated(5L, "tenant-a", 2)).isEqualTo(1);
    assertThat(alert.selectOpenForReemit(15)).isEmpty();

    BatchDayReplayEntryMapper replay = mock(BatchDayReplayEntryMapper.class, CALLS_REAL_METHODS);
    doReturn(List.of()).when(replay).selectBySessionAndStatus(4L, null, "PENDING", 15);
    doReturn(1).when(replay).claimIfCurrent(7L, "tenant-a", 4L, NOW, "PENDING", "RUNNING");
    doReturn(1).when(replay).resetStaleUnbound(4L, NOW.minusSeconds(60), NOW, "RUNNING", "PENDING");

    assertThat(replay.selectBySessionAndStatus(4L, "PENDING", 15)).isEmpty();
    assertThat(replay.claimPending(7L, "tenant-a", 4L, NOW)).isEqualTo(1);
    assertThat(replay.resetStaleUnboundRunning(4L, NOW.minusSeconds(60), NOW)).isEqualTo(1);

    BatchDayWaitingLaunchMapper waiting =
        mock(BatchDayWaitingLaunchMapper.class, CALLS_REAL_METHODS);
    BatchDayWaitingLaunchEntity waitingEntity = BatchDayWaitingLaunchEntity.builder()
        .tenantId("tenant-a")
        .requestId("request-a")
        .build();
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    doReturn(1).when(waiting).insertWithStatus(waitingEntity, "WAITING");
    doReturn(List.of()).when(waiting).selectByStatus("tenant-a", "WAITING", 15);
    doReturn(List.of())
        .when(waiting)
        .selectByCalendarBizDateAndStatus("tenant-a", "calendar-a", bizDate, "WAITING", 15);
    doReturn(1)
        .when(waiting)
        .transitionStatus("tenant-a", "request-a", "operator-a", "WAITING", "RELEASED");

    assertThat(waiting.insert(waitingEntity)).isEqualTo(1);
    assertThat(waiting.selectWaiting("tenant-a", 15)).isEmpty();
    assertThat(waiting.selectWaitingByCalendarBizDate("tenant-a", "calendar-a", bizDate, 15))
        .isEmpty();
    assertThat(waiting.markReleased("tenant-a", "request-a", "operator-a")).isEqualTo(1);
  }

  @Test
  @DisplayName("注册表、死信、取证与 outbox 入口使用领域状态")
  void shouldDelegateRegistryDeadLetterForensicAndOutboxStatuses() {
    CustomTaskTypeRegistryMapper registry =
        mock(CustomTaskTypeRegistryMapper.class, CALLS_REAL_METHODS);
    CustomTaskTypeUpsertParam upsertParam = mock(CustomTaskTypeUpsertParam.class);
    doReturn(1).when(registry).upsertDeclaredWithStatus(upsertParam, "ACTIVE");
    assertThat(registry.upsertDeclared(upsertParam)).isEqualTo(1);

    DeadLetterTaskMapper deadLetter = mock(DeadLetterTaskMapper.class, CALLS_REAL_METHODS);
    doReturn(List.of())
        .when(deadLetter)
        .selectDueAutoRetriesByStatuses(15, List.of("NEW", "FAILED"));
    doReturn(1).when(deadLetter).updateGiveUp("tenant-a", 3L, "GIVE_UP");
    assertThat(deadLetter.selectDueAutoRetries(15)).isEmpty();
    assertThat(deadLetter.markGiveUp("tenant-a", 3L)).isEqualTo(1);

    ForensicExportLogMapper forensic = mock(ForensicExportLogMapper.class, CALLS_REAL_METHODS);
    CompletionParam completion = new CompletionParam(
        "tenant-a", "export-a", "s3://bucket/key", 12L, "sha", "{}", NOW, "COMPLETED");
    FailureParam failure = new FailureParam("tenant-a", "export-a", "failed", NOW, "FAILED");
    doReturn(1).when(forensic).complete(completion);
    doReturn(1).when(forensic).fail(failure);
    assertThat(forensic.markCompleted(
            "tenant-a", "export-a", "s3://bucket/key", 12L, "sha", "{}", NOW))
        .isEqualTo(1);
    assertThat(forensic.markFailed("tenant-a", "export-a", "failed", NOW)).isEqualTo(1);

    OutboxEventMapper outbox = mock(OutboxEventMapper.class, CALLS_REAL_METHODS);
    doReturn(1).when(outbox).resetStatus("tenant-a", List.of(1L), List.of("FAILED"), "NEW");
    assertThat(outbox.resetToNew("tenant-a", List.of(1L), List.of("FAILED"))).isEqualTo(1);
  }

  @Test
  @DisplayName("结果版本入口装配预期状态与目标状态")
  void shouldDelegateResultVersionStatuses() {
    ResultVersionMapper mapper = mock(ResultVersionMapper.class, CALLS_REAL_METHODS);
    SupersedeParam supersede =
        new SupersedeParam("tenant-a", "business-a", NOW, "EFFECTIVE", "SUPERSEDED");
    TransitionParam promote = new TransitionParam("tenant-a", 7L, NOW, "PENDING", "EFFECTIVE");
    TransitionParam reject = new TransitionParam("tenant-a", 7L, NOW, "PENDING", "ARCHIVED");
    ArchiveParam archive = new ArchiveParam("tenant-a", 7L, NOW, true, "SUPERSEDED", "ARCHIVED");
    doReturn(1).when(mapper).supersede(supersede);
    doReturn(1).when(mapper).promote(promote);
    doReturn(1).when(mapper).reject(reject);
    doReturn(1).when(mapper).archive(archive);

    assertThat(mapper.supersedePriorEffective("tenant-a", "business-a", NOW)).isEqualTo(1);
    assertThat(mapper.promoteToEffective("tenant-a", 7L, NOW)).isEqualTo(1);
    assertThat(mapper.rejectPending("tenant-a", 7L, NOW)).isEqualTo(1);
    assertThat(mapper.archiveSuperseded("tenant-a", 7L, NOW, true)).isEqualTo(1);
  }

  @Test
  @DisplayName("Trigger 请求与 Worker 入口装配 CAS 状态集合")
  void shouldDelegateTriggerRequestAndWorkerStatuses() {
    TriggerRequestMapper trigger = mock(TriggerRequestMapper.class, CALLS_REAL_METHODS);
    doReturn(1)
        .when(trigger)
        .updateAcceptanceGuarded(
            "tenant-a",
            "request-a",
            "ACCEPTED",
            7L,
            List.of("LAUNCHED", "REJECTED", "DUPLICATE"),
            "LAUNCHED");
    doReturn(List.of())
        .when(trigger)
        .selectStaleByRequestAndExcludedInstanceStatus(NOW, 15, "ACCEPTED", "CREATED");
    doReturn(1)
        .when(trigger)
        .reconcileStatus("tenant-a", "request-a", 7L, "LAUNCHED", List.of("ACCEPTED"), true);
    doReturn(1)
        .when(trigger)
        .reconcileStatus(
            "tenant-a", "request-a", 7L, "LAUNCHED", List.of("ACCEPTED", "DUPLICATE"), false);

    assertThat(trigger.updateAcceptance("tenant-a", "request-a", "ACCEPTED", 7L))
        .isEqualTo(1);
    assertThat(trigger.selectStaleAcceptedWithJobInstance(NOW, 15)).isEmpty();
    assertThat(trigger.reconcileLaunched("tenant-a", "request-a", 7L)).isEqualTo(1);
    assertThat(trigger.reconcileRecoveredCreatedLaunch("tenant-a", "request-a", 7L))
        .isEqualTo(1);

    WorkerRegistryMapper worker = mock(WorkerRegistryMapper.class, CALLS_REAL_METHODS);
    doReturn(1).when(worker).updateStatusAndResetDrain("tenant-a", "worker-a", "DECOMMISSIONED");
    doReturn(1)
        .when(worker)
        .updateStaleHeartbeatsStatus(60L, "OFFLINE", List.of("ONLINE", "DRAINING"));
    doReturn(1).when(worker).startDrainTransition("tenant-a", "worker-a", "ONLINE", "DRAINING", 30);
    doReturn(1).when(worker).warmupTransition("tenant-a", "worker-a", "OFFLINE", "ONLINE");

    assertThat(worker.markDecommissioned("tenant-a", "worker-a")).isEqualTo(1);
    assertThat(worker.markStaleHeartbeatsOffline(60L)).isEqualTo(1);
    assertThat(worker.startDrainIfCurrent("tenant-a", "worker-a", "ONLINE", 30)).isEqualTo(1);
    assertThat(worker.warmupIfOffline("tenant-a", "worker-a")).isEqualTo(1);
  }
}
