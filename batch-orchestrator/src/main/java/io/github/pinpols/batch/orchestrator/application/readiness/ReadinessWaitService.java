package io.github.pinpols.batch.orchestrator.application.readiness;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessService;
import io.github.pinpols.batch.orchestrator.domain.command.ReadinessWaitHandoverCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.ReadinessWaitEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ReadinessWaitReleaseParam;
import io.github.pinpols.batch.orchestrator.domain.param.ReadinessWaitTerminalParam;
import io.github.pinpols.batch.orchestrator.mapper.ReadinessWaitMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrator 接管的 readiness 等待：保存原依赖/版本/业务日/截止时间，并在无 Quartz 回调时自行推进。
 *
 * <p>每轮扫描 PENDING 等待：
 *
 * <ul>
 *   <li>上游已就绪 → 调 Trigger 内部恢复端点重建原 fire 身份并启动，成功后标记 RELEASED；
 *   <li>未就绪且已过 deadline → 标记 TIMED_OUT（终结等待，批日结算不再被阻塞）；
 *   <li>恢复调用失败 → 保持 PENDING，下一轮重试（不吞错、不误判成功）。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadinessWaitService {

  static final String STATUS_PENDING = "PENDING";
  static final String STATUS_RELEASED = "RELEASED";
  static final String STATUS_TIMED_OUT = "TIMED_OUT";
  static final String STATUS_CANCELLED = "CANCELLED";
  private static final String METRIC_RELEASED = "batch.readiness.wait.released.total";
  private static final String METRIC_TIMED_OUT = "batch.readiness.wait.timeout.total";

  private final ReadinessWaitMapper readinessWaitMapper;
  private final ReadinessService readinessService;
  private final TriggerReadinessRecoveryClient recoveryClient;
  private final MeterRegistry meterRegistry;

  /** 接管一条 readiness 等待；幂等（同一 fire 身份只登记一次）。 */
  public boolean handover(ReadinessWaitHandoverCommand command) {
    boolean identityIsIncomplete = EmptyChecks.isNull(command)
        || !Texts.hasText(command.tenantId())
        || EmptyChecks.isNull(command.workflowDefinitionId())
        || EmptyChecks.isEmpty(command.dependsOnJobCodes())
        || EmptyChecks.isNull(command.bizDate())
        || EmptyChecks.isNull(command.scheduledFireTime())
        || EmptyChecks.isNull(command.deadline());
    if (identityIsIncomplete) {
      return false;
    }
    ReadinessWaitEntity wait = new ReadinessWaitEntity();
    wait.setTenantId(command.tenantId());
    wait.setWorkflowDefinitionId(command.workflowDefinitionId());
    wait.setWorkflowCode(command.workflowCode());
    wait.setCalendarCode(command.calendarCode());
    wait.setScheduleVersion(command.scheduleVersion());
    wait.setDependsOnJobCodes(normalizeDependencies(command.dependsOnJobCodes()));
    wait.setBizDate(command.bizDate());
    wait.setScheduledFireTime(command.scheduledFireTime());
    wait.setDeferredSince(command.deferredSince());
    wait.setDeadline(command.deadline());
    wait.setTraceId(command.traceId());
    if (EmptyChecks.isNull(wait.getDeferredSince())) {
      wait.setDeferredSince(wait.getScheduledFireTime());
    }
    if (EmptyChecks.isNull(wait.getScheduleVersion())) {
      wait.setScheduleVersion(0);
    }
    return readinessWaitMapper.insertIgnore(wait) == 1;
  }

  /** 单轮扫描；返回本轮释放/超窗计数。 */
  public SweepOutcome sweep(int limit) {
    List<ReadinessWaitEntity> pendingWaits = readinessWaitMapper.selectDue(Math.max(1, limit));
    if (EmptyChecks.isEmpty(pendingWaits)) {
      return new SweepOutcome(0, 0, 0);
    }
    Instant now = BatchDateTimeSupport.utcNow();
    int released = 0;
    int timedOut = 0;
    int deferred = 0;
    for (ReadinessWaitEntity pendingWait : pendingWaits) {
      readinessWaitMapper.recordProbe(pendingWait.getTenantId(), pendingWait.getId());
      if (allDependenciesReady(pendingWait)) {
        TriggerReadinessRecoveryClient.RecoveryOutcome outcome =
            recoveryClient.recover(pendingWait);
        if (outcome == TriggerReadinessRecoveryClient.RecoveryOutcome.LAUNCHED) {
          int updated = readinessWaitMapper.markReleased(ReadinessWaitReleaseParam.builder()
              .tenantId(pendingWait.getTenantId())
              .id(pendingWait.getId())
              .releasedAt(now)
              .status(STATUS_RELEASED)
              .build());
          if (updated != 1) {
            continue;
          }
          meterRegistry.counter(METRIC_RELEASED).increment();
          released++;
          log.info(
              "readiness wait released and recovery driven: tenantId={}, workflowDefinitionId={},"
                  + " scheduledFireTime={}",
              pendingWait.getTenantId(),
              pendingWait.getWorkflowDefinitionId(),
              pendingWait.getScheduledFireTime());
          continue;
        }
        if (outcome == TriggerReadinessRecoveryClient.RecoveryOutcome.ALREADY_TERMINATED) {
          // occurrence 已是终态（如被 Trigger 扫尾器超时终结）：fire 永远不会发生，
          // 等待直接终结，不再空转到 deadline 才解除批日结算阻塞。
          int updated = readinessWaitMapper.markTerminal(ReadinessWaitTerminalParam.builder()
              .tenantId(pendingWait.getTenantId())
              .id(pendingWait.getId())
              .status(STATUS_TIMED_OUT)
              .errorCode("occurrence-already-terminated")
              .occurredAt(now)
              .build());
          if (updated != 1) {
            continue;
          }
          meterRegistry.counter(METRIC_TIMED_OUT).increment();
          timedOut++;
          log.info(
              "readiness wait terminated because the occurrence is already settled:"
                  + " tenantId={}, workflowDefinitionId={}, scheduledFireTime={}",
              pendingWait.getTenantId(),
              pendingWait.getWorkflowDefinitionId(),
              pendingWait.getScheduledFireTime());
          continue;
        }
        // NOT_READY / FAILED：保持 PENDING，避免把未启动误记为已释放。
        deferred++;
        continue;
      }
      if (!now.isBefore(pendingWait.getDeadline())) {
        int updated = readinessWaitMapper.markTerminal(ReadinessWaitTerminalParam.builder()
            .tenantId(pendingWait.getTenantId())
            .id(pendingWait.getId())
            .status(STATUS_TIMED_OUT)
            .errorCode("readiness-window-exceeded")
            .occurredAt(now)
            .build());
        if (updated != 1) {
          continue;
        }
        meterRegistry.counter(METRIC_TIMED_OUT).increment();
        timedOut++;
        log.error(
            "readiness wait exceeded its window; wait terminated and settlement unblocked:"
                + " tenantId={}, workflowDefinitionId={}, scheduledFireTime={}, deadline={}",
            pendingWait.getTenantId(),
            pendingWait.getWorkflowDefinitionId(),
            pendingWait.getScheduledFireTime(),
            pendingWait.getDeadline());
      } else {
        deferred++;
      }
    }
    return new SweepOutcome(released, timedOut, deferred);
  }

  /** 正常 fire 成功后由 Trigger 回调取消等待，避免重复恢复启动。 */
  public boolean cancel(String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {
    if (!Texts.hasText(tenantId)
        || EmptyChecks.isNull(workflowDefinitionId)
        || EmptyChecks.isNull(scheduledFireTime)) {
      return false;
    }
    List<ReadinessWaitEntity> matches =
        readinessWaitMapper.selectByFireIdentity(tenantId, workflowDefinitionId, scheduledFireTime);
    boolean cancelled = false;
    for (ReadinessWaitEntity match : matches) {
      cancelled |= readinessWaitMapper.markTerminal(ReadinessWaitTerminalParam.builder()
              .tenantId(match.getTenantId())
              .id(match.getId())
              .status(STATUS_CANCELLED)
              .errorCode("fired-by-trigger")
              .occurredAt(BatchDateTimeSupport.utcNow())
              .build())
          == 1;
    }
    return cancelled;
  }

  /**
   * 等待粒度是"依赖集合"：只有全部上游同批次日就绪才驱动恢复。
   *
   * <p>Trigger 主就绪闸同样遍历全部依赖；这里少查任一依赖都会提前放行，故逐项判定。
   */
  private boolean allDependenciesReady(ReadinessWaitEntity wait) {
    List<String> dependencies = splitDependencies(wait.getDependsOnJobCodes());
    for (String dependency : dependencies) {
      if (!readinessService
          .checkJobReady(wait.getTenantId(), dependency, wait.getBizDate())
          .ready()) {
        return false;
      }
    }
    return EmptyChecks.isNotEmpty(dependencies);
  }

  private List<String> splitDependencies(String joinedDependencies) {
    if (!Texts.hasText(joinedDependencies)) {
      return List.of();
    }
    return Arrays.stream(joinedDependencies.split(","))
        .map(String::trim)
        .filter(Texts::hasText)
        .distinct()
        .toList();
  }

  private String normalizeDependencies(List<String> dependencies) {
    return dependencies.stream()
        .map(String::trim)
        .filter(Texts::hasText)
        .distinct()
        .collect(Collectors.joining(","));
  }

  /** 单轮扫描结果。 */
  public record SweepOutcome(int released, int timedOut, int deferred) {}
}
