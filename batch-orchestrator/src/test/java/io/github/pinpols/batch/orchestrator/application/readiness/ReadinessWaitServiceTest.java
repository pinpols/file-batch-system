package io.github.pinpols.batch.orchestrator.application.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessResult;
import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessService;
import io.github.pinpols.batch.orchestrator.domain.command.ReadinessWaitHandoverCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.ReadinessWaitEntity;
import io.github.pinpols.batch.orchestrator.mapper.ReadinessWaitMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@DisplayName("Orchestrator 接管 readiness 等待: 幂等移交、就绪恢复、超窗终结")
class ReadinessWaitServiceTest {

  private ReadinessWaitMapper readinessWaitMapper;
  private ReadinessService readinessService;
  private TriggerReadinessRecoveryClient recoveryClient;
  private SimpleMeterRegistry meterRegistry;
  private ReadinessWaitService service;

  @ParameterizedTest
  @ValueSource(strings = {"released", "occurrence-terminal", "deadline"})
  @DisplayName("并发推进导致条件更新零行时不计本轮释放或超时")
  void shouldNotCountConcurrentTransitionAsOwnSuccess(String scenario) {
    boolean deadline = "deadline".equals(scenario);
    ReadinessWaitEntity wait = wait(BatchDateTimeSupport.utcNow().plusSeconds(deadline ? -5 : 120));
    when(readinessWaitMapper.selectDue(anyInt())).thenReturn(List.of(wait));
    when(readinessService.checkJobReady(anyString(), anyString(), any()))
        .thenReturn(readyResult(!deadline));
    if (!deadline) {
      when(recoveryClient.recover(wait))
          .thenReturn(
              "released".equals(scenario)
                  ? TriggerReadinessRecoveryClient.RecoveryOutcome.LAUNCHED
                  : TriggerReadinessRecoveryClient.RecoveryOutcome.ALREADY_TERMINATED);
    }
    ReadinessWaitService.SweepOutcome outcome = service.sweep(100);
    assertThat(outcome.released()).isZero();
    assertThat(outcome.timedOut()).isZero();
    assertThat(meterRegistry.counter("batch.readiness.wait.released.total").count())
        .isZero();
    assertThat(meterRegistry.counter("batch.readiness.wait.timeout.total").count())
        .isZero();
  }

  @BeforeEach
  void setUp() {
    readinessWaitMapper = mock(ReadinessWaitMapper.class);
    readinessService = mock(ReadinessService.class);
    recoveryClient = mock(TriggerReadinessRecoveryClient.class);
    meterRegistry = new SimpleMeterRegistry();
    service = new ReadinessWaitService(
        readinessWaitMapper, readinessService, recoveryClient, meterRegistry);
  }

  @Test
  @DisplayName("移交走幂等登记，并对缺省字段补原 fire 身份")
  void shouldHandoverIdempotentlyWithDerivedDefaults() {
    ReadinessWaitHandoverCommand command = command(Instant.now().plusSeconds(120), null, null);
    when(readinessWaitMapper.insertIgnore(any())).thenReturn(1);

    assertThat(service.handover(command)).isTrue();

    ArgumentCaptor<ReadinessWaitEntity> captor = ArgumentCaptor.forClass(ReadinessWaitEntity.class);
    verify(readinessWaitMapper).insertIgnore(captor.capture());
    assertThat(captor.getValue().getDeferredSince()).isEqualTo(command.scheduledFireTime());
    assertThat(captor.getValue().getScheduleVersion()).isZero();
  }

  @Test
  @DisplayName("身份不完整的移交被拒绝，不写库")
  void shouldRejectIncompleteHandover() {
    ReadinessWaitHandoverCommand command = new ReadinessWaitHandoverCommand(
        "tenant-rw",
        7L,
        "WF_READINESS",
        "CAL",
        3,
        null,
        LocalDate.parse("2026-10-24"),
        Instant.parse("2026-10-24T00:00:00Z"),
        Instant.parse("2026-10-24T00:00:00Z"),
        Instant.now().plusSeconds(120),
        "trace-1");

    assertThat(service.handover(command)).isFalse();
    verify(readinessWaitMapper, never()).insertIgnore(any());
  }

  @Test
  @DisplayName("上游就绪且恢复被接受 → 标记 RELEASED 并计指标")
  void shouldReleaseWhenReadyAndRecoveryAccepted() {
    ReadinessWaitEntity wait = wait(Instant.now().plusSeconds(120));
    when(readinessWaitMapper.selectDue(anyInt())).thenReturn(List.of(wait));
    when(readinessService.checkJobReady("tenant-rw", "UPSTREAM_JOB", wait.getBizDate()))
        .thenReturn(readyResult(true));
    when(recoveryClient.recover(wait))
        .thenReturn(TriggerReadinessRecoveryClient.RecoveryOutcome.LAUNCHED);
    when(readinessWaitMapper.markReleased(any())).thenReturn(1);

    ReadinessWaitService.SweepOutcome outcome = service.sweep(100);

    assertThat(outcome.released()).isEqualTo(1);
    verify(readinessWaitMapper).markReleased(any());
    assertThat(meterRegistry.counter("batch.readiness.wait.released.total").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("恢复调用失败 → 保持 PENDING，不误记释放")
  void shouldKeepPendingWhenRecoveryCallFails() {
    ReadinessWaitEntity wait = wait(Instant.now().plusSeconds(120));
    when(readinessWaitMapper.selectDue(anyInt())).thenReturn(List.of(wait));
    when(readinessService.checkJobReady(anyString(), anyString(), any()))
        .thenReturn(readyResult(true));
    when(recoveryClient.recover(wait))
        .thenReturn(TriggerReadinessRecoveryClient.RecoveryOutcome.FAILED);

    ReadinessWaitService.SweepOutcome outcome = service.sweep(100);

    assertThat(outcome.released()).isZero();
    assertThat(outcome.deferred()).isEqualTo(1);
    verify(readinessWaitMapper, never()).markReleased(any());
  }

  @Test
  @DisplayName("occurrence 已终态（409 ALREADY_TERMINATED）→ 当轮终结 TIMED_OUT，不等 deadline")
  void shouldTerminateImmediatelyWhenOccurrenceAlreadySettled() {
    ReadinessWaitEntity wait = wait(Instant.now().plusSeconds(3600));
    when(readinessWaitMapper.selectDue(anyInt())).thenReturn(List.of(wait));
    when(readinessService.checkJobReady(anyString(), anyString(), any()))
        .thenReturn(readyResult(true));
    when(recoveryClient.recover(wait))
        .thenReturn(TriggerReadinessRecoveryClient.RecoveryOutcome.ALREADY_TERMINATED);
    when(readinessWaitMapper.markTerminal(any())).thenReturn(1);

    ReadinessWaitService.SweepOutcome outcome = service.sweep(100);

    assertThat(outcome.timedOut()).isEqualTo(1);
    verify(readinessWaitMapper).markTerminal(any());
    verify(readinessWaitMapper, never()).markReleased(any());
    assertThat(meterRegistry.counter("batch.readiness.wait.timeout.total").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("正常 fire 取消回调按 fire 身份直查，不再全量加载")
  void shouldCancelByFireIdentity() {
    ReadinessWaitEntity wait = wait(Instant.now().plusSeconds(120));
    when(readinessWaitMapper.selectByFireIdentity("tenant-rw", 7L, wait.getScheduledFireTime()))
        .thenReturn(List.of(wait));
    when(readinessWaitMapper.markTerminal(any())).thenReturn(1);

    boolean cancelled = service.cancel("tenant-rw", 7L, wait.getScheduledFireTime());

    assertThat(cancelled).isTrue();
    verify(readinessWaitMapper).selectByFireIdentity("tenant-rw", 7L, wait.getScheduledFireTime());
  }

  @Test
  @DisplayName("未就绪且超过 deadline → 终结 TIMED_OUT，解除批日结算阻塞")
  void shouldTimeOutAfterDeadline() {
    ReadinessWaitEntity wait = wait(BatchDateTimeSupport.utcNow().minusSeconds(5));
    when(readinessWaitMapper.selectDue(anyInt())).thenReturn(List.of(wait));
    when(readinessService.checkJobReady(anyString(), anyString(), any()))
        .thenReturn(readyResult(false));
    when(readinessWaitMapper.markTerminal(any())).thenReturn(1);

    ReadinessWaitService.SweepOutcome outcome = service.sweep(100);

    assertThat(outcome.timedOut()).isEqualTo(1);
    verify(readinessWaitMapper).markTerminal(any());
    assertThat(meterRegistry.counter("batch.readiness.wait.timeout.total").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("未就绪但窗口未到 → 保持 PENDING")
  void shouldKeepPendingBeforeDeadline() {
    ReadinessWaitEntity wait = wait(Instant.now().plusSeconds(120));
    when(readinessWaitMapper.selectDue(anyInt())).thenReturn(List.of(wait));
    when(readinessService.checkJobReady(anyString(), anyString(), any()))
        .thenReturn(readyResult(false));

    ReadinessWaitService.SweepOutcome outcome = service.sweep(100);

    assertThat(outcome.deferred()).isEqualTo(1);
    verify(readinessWaitMapper, never()).markTerminal(any());
  }

  @Test
  @DisplayName("多依赖: 任一依赖未就绪保持 PENDING，全部就绪才释放")
  void shouldRequireAllDependenciesReady() {
    ReadinessWaitEntity wait = wait(Instant.now().plusSeconds(120));
    wait.setDependsOnJobCodes("UPSTREAM_A,UPSTREAM_B");
    when(readinessWaitMapper.selectDue(anyInt())).thenReturn(List.of(wait));
    when(readinessService.checkJobReady("tenant-rw", "UPSTREAM_A", wait.getBizDate()))
        .thenReturn(readyResult(true));
    when(readinessService.checkJobReady("tenant-rw", "UPSTREAM_B", wait.getBizDate()))
        .thenReturn(readyResult(false));

    ReadinessWaitService.SweepOutcome outcome = service.sweep(100);

    assertThat(outcome.deferred()).isEqualTo(1);
    verify(recoveryClient, never()).recover(any());
    verify(readinessWaitMapper, never()).markReleased(any());
  }

  private static ReadinessResult readyResult(boolean ready) {
    return ready
        ? ReadinessResult.ofReady()
        : ReadinessResult.ofNotReady("asset-partition-not-effective");
  }

  private static ReadinessWaitHandoverCommand command(
      Instant deadline, Instant deferredSince, Integer scheduleVersion) {
    return new ReadinessWaitHandoverCommand(
        "tenant-rw",
        7L,
        "WF_READINESS",
        "CAL",
        scheduleVersion,
        List.of("UPSTREAM_JOB"),
        LocalDate.parse("2026-10-24"),
        Instant.parse("2026-10-24T00:00:00Z"),
        deferredSince,
        deadline,
        "trace-1");
  }

  private static ReadinessWaitEntity wait(Instant deadline) {
    ReadinessWaitEntity wait = new ReadinessWaitEntity();
    wait.setId(42L);
    wait.setTenantId("tenant-rw");
    wait.setWorkflowDefinitionId(7L);
    wait.setWorkflowCode("WF_READINESS");
    wait.setCalendarCode("CAL");
    wait.setScheduleVersion(3);
    wait.setDependsOnJobCodes("UPSTREAM_JOB");
    wait.setBizDate(LocalDate.parse("2026-10-24"));
    wait.setScheduledFireTime(Instant.parse("2026-10-24T00:00:00Z"));
    wait.setDeferredSince(Instant.parse("2026-10-24T00:00:00Z"));
    wait.setDeadline(deadline);
    return wait;
  }
}
