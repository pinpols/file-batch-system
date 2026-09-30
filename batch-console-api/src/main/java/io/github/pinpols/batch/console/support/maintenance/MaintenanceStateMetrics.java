package io.github.pinpols.batch.console.support.maintenance;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 暴露跨副本维护状态，供 Prometheus 判断维护公告是否已收敛。 */
@Component
@RequiredArgsConstructor
public class MaintenanceStateMetrics {

  private final MaintenanceStateHolder stateHolder;
  private final MeterRegistry meterRegistry;

  /** 维护拦截结果计数，标签仅使用固定低基数结果。 */
  public void recordRequest(String result) {
    meterRegistry
        .counter("batch.console.maintenance.requests", "result", result)
        .increment();
  }

  /** 注册低基数 gauge；状态值从持有者实时读取，不在指标标签中放租户或实例号。 */
  @jakarta.annotation.PostConstruct
  void register() {
    Gauge.builder(
            "batch.console.maintenance.enabled",
            stateHolder,
            holder -> holder.current().enabled() ? 1.0 : 0.0)
        .description("Whether Console maintenance mode is enabled")
        .register(meterRegistry);
    Gauge.builder(
            "batch.console.maintenance.read_only",
            stateHolder,
            holder -> holder.current().readOnly() ? 1.0 : 0.0)
        .description("Whether Console maintenance mode is read-only")
        .register(meterRegistry);
    Gauge.builder(
            "batch.console.maintenance.shared_state_available",
            stateHolder,
            holder -> holder.current().sharedStateAvailable() ? 1.0 : 0.0)
        .description("Whether this Console replica confirmed the shared maintenance state")
        .register(meterRegistry);
    Gauge.builder(
            "batch.console.maintenance.version",
            stateHolder,
            holder -> holder.current().version())
        .description("Shared maintenance state version observed by this Console replica")
        .register(meterRegistry);
  }
}
