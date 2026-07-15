package io.github.pinpols.batch.orchestrator.infrastructure.governance;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AM firing 状态维持器(迁移方案 §6.1 的 re-emitter)。
 *
 * <p>emit 直连只推一次;要维持 AM 侧 firing(否则 {@code resolve_timeout} 5m 后 AM 误判 resolved 提前停通知), 需对仍 OPEN
 * 的告警周期重发。周期取 {@code batch.alert.am-emit.resend-interval-seconds}(默认 60s,须 &lt; AM
 * resolve_timeout)。
 *
 * <p>ShedLock 单节点执行避免多实例重复推;优雅停机 draining 时跳过;{@code am-emit.enabled=false} 时 publisher
 * 未启用,本调度器直接短路(回滚路径)。AM 端按 label 集合幂等,重复 fire 无副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertmanagerReemitScheduler {

  private final AlertmanagerEmitProperties properties;
  private final AlertmanagerEmitPublisher publisher;
  private final AlertEventMapper alertEventMapper;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "#{${batch.alert.am-emit.resend-interval-seconds:60} * 1000}")
  @SchedulerLock(name = "am_alert_reemit", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
  public void reemit() {
    if (!publisher.isEnabled()
        || !properties.isEnabled()
        || properties.getResendIntervalSeconds() <= 0
        || gracefulShutdown.isDraining()) {
      return;
    }
    List<AlertEventEntity> open =
        alertEventMapper.selectOpenForReemit(Math.max(1, properties.getReemitBatchSize()));
    if (open.isEmpty()) {
      return;
    }
    for (AlertEventEntity alert : open) {
      publisher.publishFiring(alert);
    }
    log.debug("AM re-emit refreshed {} OPEN alert(s) as firing", open.size());
  }
}
