package com.example.batch.orchestrator.infrastructure;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
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

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    startDraining("context-closed");
  }

  public void startDraining(String source) {
    if (draining.compareAndSet(false, true)) {
      drainingSince = BatchDateTimeSupport.utcNow();
      reason = source;
      log.info("Orchestrator graceful shutdown initiated: draining=true, source={}", source);
    }
  }

  public void stopDraining(String source) {
    if (draining.compareAndSet(true, false)) {
      log.info("Orchestrator drain cancelled: source={}", source);
    }
    drainingSince = null;
    reason = source;
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
