package com.example.batch.orchestrator.infrastructure;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Orchestrator 优雅停机状态。
 *
 * <p>用于两类场景：
 *
 * <ul>
 *   <li>应用关闭时自动进入 drain，拒绝新 launch / 新 dispatch。
 *   <li>运维手工先切 drain，再由网关摘流量。
 * </ul>
 */
@Slf4j
@Component
public class OrchestratorGracefulShutdown implements ApplicationListener<ContextClosedEvent> {

  private final AtomicBoolean draining = new AtomicBoolean(false);
  private volatile Instant drainingSince;
  private volatile String reason;

  // R3-P1-1：可选注入；Spring 测试场景可能不在容器内构造该类。
  @Autowired(required = false)
  private ApplicationEventPublisher eventPublisher;

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    startDraining("context-closed");
  }

  public void startDraining(String source) {
    if (draining.compareAndSet(false, true)) {
      drainingSince = BatchDateTimeSupport.utcNow();
      reason = source;
      log.info("Orchestrator graceful shutdown initiated: draining=true, source={}", source);
      // R3-P1-1：发 AvailabilityChangeEvent(REFUSING_TRAFFIC) → Spring Boot 自动把
      // /actuator/health/readiness 翻 DOWN → K8s 在一个 readinessProbe.period 内把 pod
      // 从 Service endpoints 摘除。否则 manual pre-drain 期间 readiness 仍绿，client 看到 503。
      if (eventPublisher != null) {
        try {
          AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        } catch (RuntimeException ex) {
          log.warn("publish REFUSING_TRAFFIC failed (non-fatal): {}", ex.getMessage());
        }
      }
    }
  }

  public void stopDraining(String source) {
    // 诊断字段只在 CAS 赢家分支里更新:并发 start/stop 时,输家不得覆盖 reason/drainingSince,
    // 否则 status() 展示与 draining 实际状态对不上。
    if (draining.compareAndSet(true, false)) {
      drainingSince = null;
      reason = source;
      log.info("Orchestrator drain cancelled: source={}", source);
      // 取消 drain → 重新接受流量
      if (eventPublisher != null) {
        try {
          AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        } catch (RuntimeException ex) {
          log.warn("publish ACCEPTING_TRAFFIC failed (non-fatal): {}", ex.getMessage());
        }
      }
    }
  }

  public boolean isDraining() {
    return draining.get();
  }

  public Map<String, Object> status() {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("draining", draining.get());
    status.put("drainingSince", drainingSince);
    status.put("reason", reason);
    return status;
  }
}
