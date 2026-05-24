package com.example.batch.trigger.wheel;

import com.example.batch.trigger.config.WheelSchedulerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 时间轮 scheduler 的 bean 装配 — 仅在 {@code batch.trigger.scheduler-impl=wheel} 时生效。
 *
 * <p>Quartz 模式(默认)下整个 wheel 子系统不创建,无任何资源占用。
 */
@Configuration
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "wheel")
@EnableConfigurationProperties(WheelSchedulerProperties.class)
public class WheelSchedulerConfiguration {

  @Bean
  public WheelMetrics wheelMetrics(MeterRegistry meterRegistry) {
    return new WheelMetrics(meterRegistry);
  }

  @Bean
  public CatchUpThrottle catchUpThrottle(WheelSchedulerProperties props) {
    return new CatchUpThrottle(props.getCatchUpRatePerSecond());
  }

  /**
   * P0 (audit 2026-05-23):wheel fire() 异步执行池。
   *
   * <p>原 fire() 在 Netty {@code HashedWheelTimer} 单 worker 线程同步跑 loadDescriptor + launchScheduled +
   * advanceAfterFire(合计 100-500ms),大批量触发时 wheel tick callback 堆积,fire lag 超过 misfire 阈值 → misfire
   * 雪崩。
   *
   * <p>改为:wheel worker 仅 submit,真正的 fire 逻辑跑在本池。{@code CallerRunsPolicy} 让 wheel worker
   * 兜底执行,提供背压而非丢 fire(关键 — 丢 fire 后 next_fire_time 不推进会反复重试)。
   *
   * <p>WheelMetrics 注册 {@code batch.trigger.wheel.fire.queue.size} gauge 监控队列堆积。
   */
  @Bean(name = "triggerFireExecutor", destroyMethod = "shutdown")
  public ThreadPoolTaskExecutor triggerFireExecutor(
      WheelSchedulerProperties props, WheelMetrics metrics) {
    WheelSchedulerProperties.FireAsync cfg = props.getFireAsync();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(cfg.getCoreSize());
    executor.setMaxPoolSize(cfg.getMaxSize());
    executor.setQueueCapacity(cfg.getQueueCapacity());
    executor.setKeepAliveSeconds(cfg.getKeepAliveSeconds());
    executor.setThreadNamePrefix("trigger-fire-async-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(cfg.getAwaitTerminationSeconds());
    executor.initialize();
    metrics.registerFireQueueSizeGauge(() -> executor.getThreadPoolExecutor().getQueue().size());
    return executor;
  }
}
