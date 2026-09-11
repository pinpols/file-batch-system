package io.github.pinpols.batch.orchestrator.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 记录 launch 控制面关键阶段耗时。
 *
 * <p>整体 {@code orch.launch} 只能说明请求慢，无法区分配置校验、T1 准备事务和 T2 派发事务。固定枚举标签避免动态
 * tag 基数失控，也让压测报告可以直接定位应优化的数据库事务。
 */
@Component
public class LaunchPhaseMetrics {

  private static final String METRIC_NAME = "batch.orchestrator.launch.phase.duration";

  private final Map<Phase, Timer> timers;

  public LaunchPhaseMetrics(MeterRegistry meterRegistry) {
    EnumMap<Phase, Timer> configuredTimers = new EnumMap<>(Phase.class);
    for (Phase phase : Phase.values()) {
      configuredTimers.put(
          phase,
          Timer.builder(METRIC_NAME)
              .description("Orchestrator launch phase duration")
              .tag("phase", phase.metricTag())
              .publishPercentileHistogram()
              .register(meterRegistry));
    }
    this.timers = Map.copyOf(configuredTimers);
  }

  public <T> T record(Phase phase, Supplier<T> action) {
    return timers.get(phase).record(action);
  }

  public void record(Phase phase, Runnable action) {
    timers.get(phase).record(action);
  }

  public enum Phase {
    VALIDATION("validation"),
    PREPARE_TRANSACTION("prepare_transaction"),
    DISPATCH_TRANSACTION("dispatch_transaction"),
    PLAN_BUILD("plan_build"),
    RESOURCE_SCHEDULE("resource_schedule"),
    DISPATCH_MATERIALIZE("dispatch_materialize"),
    INSTANCE_TRANSITION("instance_transition");

    private final String metricTag;

    Phase(String metricTag) {
      this.metricTag = metricTag;
    }

    public String metricTag() {
      return metricTag;
    }
  }
}
