package com.example.batch.orchestrator.infrastructure.governance;

import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.config.AlertEscalationProperties;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 告警升级阶梯调度器(运维告警闭环)。
 *
 * <p>默认每 60 秒 sweep 一次,委托 {@link AlertEventService#escalateOverdue} 把长期停留在 {@code OPEN}(无人 ack)
 * 且超过 ack-SLA 的告警逐级抬升 {@code escalation_tier},每升一级打 ERROR 日志 + {@code batch.alert.escalations}
 * 计数,让卡住的告警在日志/指标侧持续放大可见度,而不是静默淹没。
 *
 * <p>ShedLock 锁名 {@code alert_escalation_sweep},单节点执行避免重复升级;orchestrator 优雅停机时跳过;
 * {@code batch.alert.escalation.enabled=false} 可整体关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEscalationScheduler {

  private final AlertEventService alertEventService;
  private final AlertEscalationProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.alert.escalation.poll-interval-millis:60000}")
  @SchedulerLock(
      name = "alert_escalation_sweep",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT10S")
  public void sweep() {
    if (!properties.isEnabled() || gracefulShutdown.isDraining()) {
      return;
    }
    int escalated =
        alertEventService.escalateOverdue(
            properties.getSlaMinutes(), properties.getMaxTier(), properties.getBatchLimit());
    if (escalated > 0) {
      log.warn("Alert escalation sweep raised {} overdue alert(s) by one tier", escalated);
    }
  }
}
