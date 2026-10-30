package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 任务 SLA 违约扫描调度器。
 *
 * <p>默认每 30 秒扫描一次尚未标记告警、但已超过截止时间（{@code deadlineAt}）或 预期执行时长（{@code
 * expectedDurationSeconds}）的运行中任务实例，通过 CAS 更新 {@code sla_alerted_at} 字段防止重复告警，并写入 WARN 级告警日志与 INFO
 * 级审计日志， 同时向 {@link AlertEventService} 发送 {@code JOB_SLA_VIOLATION} 事件。 通过 Micrometer 指标 {@code
 * batch.job.sla.violation.count} 暴露当前违约候选总数。 ShedLock 锁名 {@code job_sla_scan}，最长持锁 2 分钟，最短持锁 15 秒；
 * 可通过 {@code batch.sla.enabled=false} 完全关闭扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobSlaScheduler {

  private final JobMonitoringMapper jobMonitoringMapper;
  private final BatchOrchestratorGovernanceProperties governance;
  private final MeterRegistry meterRegistry;
  private final JobSlaViolationProcessor violationProcessor;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AtomicLong violationCount = new AtomicLong();
  private final AtomicLong escalationCount = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.job.sla.violation.count", violationCount);
    meterRegistry.gauge("batch.job.sla.escalation.count", escalationCount);
  }

  @Scheduled(fixedDelayString = "${batch.sla.poll-interval-millis:30000}")
  @SchedulerLock(name = "job_sla_scan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void scanViolations() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!governance.sla().isEnabled()) {
      return;
    }
    violationCount.set(jobMonitoringMapper.countSlaViolationCandidates());
    List<JobInstanceEntity> candidates =
        jobMonitoringMapper.selectSlaViolationCandidates(governance.sla().getBatchSize());
    Instant now = BatchDateTimeSupport.utcNow();
    for (JobInstanceEntity candidate : candidates) {
      if (candidate == null || candidate.getId() == null || candidate.getTenantId() == null) {
        continue;
      }
      try {
        RlsTenantContextHolder.runWithTenant(
            candidate.getTenantId(), () -> violationProcessor.process(candidate, now));
      } catch (RuntimeException e) {
        log.error(
            "failed to persist job SLA violation; candidate will be retried: tenantId={}, jobInstanceId={}",
            candidate.getTenantId(),
            candidate.getId(),
            e);
      }
    }
    scanEscalations(now);
  }

  /**
   * 升级再触发：首次告警后 instance 仍非终态、sla_alerted_at 早于 {@code now - escalationDelay} 的实例 → 以 ERROR 级再发一条
   * {@code JOB_SLA_VIOLATION_ESCALATED}。alert_event 表按 fingerprint 去重（{@code tenant + alertType +
   * resourceKey}），重复扫描会 merge 到同一行而非产生日志噪音；本地不写 execution log，避免日志量过大。
   */
  private void scanEscalations(Instant now) {
    long delaySeconds = governance.sla().getEscalationDelaySeconds();
    if (delaySeconds <= 0L) {
      escalationCount.set(0L);
      return;
    }
    Instant escalationBefore = now.minusSeconds(delaySeconds);
    escalationCount.set(jobMonitoringMapper.countSlaEscalationCandidates(escalationBefore));
    List<JobInstanceEntity> escalations = jobMonitoringMapper.selectSlaEscalationCandidates(
        escalationBefore, governance.sla().getBatchSize());
    if (escalations == null || escalations.isEmpty()) {
      return;
    }
    String severity = governance.sla().getEscalationSeverity();
    String resolvedSeverity = severity == null || severity.isBlank() ? "ERROR" : severity;
    for (JobInstanceEntity candidate : escalations) {
      if (candidate == null || candidate.getId() == null || candidate.getTenantId() == null) {
        continue;
      }
      try {
        RlsTenantContextHolder.runWithTenant(
            candidate.getTenantId(),
            () -> violationProcessor.processEscalation(
                candidate, now, delaySeconds, resolvedSeverity));
      } catch (RuntimeException failure) {
        log.error(
            "failed to persist job SLA escalation; candidate will be retried: tenantId={}, jobInstanceId={}",
            candidate.getTenantId(),
            candidate.getId(),
            failure);
      }
    }
  }
}
