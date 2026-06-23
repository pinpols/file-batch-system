package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({
  OrchestratorClientProperties.class,
  TriggerRuntimeProperties.class,
  TriggerOutboxRelayProperties.class
})
public class QuartzTriggerConfiguration {

  /**
   * P0 修复：TriggerOutboxRelay 专用 Spring 托管 scheduler。
   *
   * <p>替换原 {@code Executors.newSingleThreadScheduledExecutor} 自建线程池 （默认 unbounded queue，DB 慢时提交堆积可
   * OOM；且游离于 Spring 生命周期外，无 Actuator 可见性）。
   *
   * <p>共享 {@code taskScheduler} 不直接复用：本 relay 是 IO 密集型固定间隔 poll，单独 1 线程避免与 其它 @Scheduled 任务互相阻塞；同时给
   * Actuator 留专属 thread name 便于排查。
   */
  @Bean(name = "triggerOutboxRelayScheduler", destroyMethod = "shutdown")
  public ThreadPoolTaskScheduler triggerOutboxRelayScheduler(
      TriggerOutboxRelayProperties properties) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("trigger-outbox-relay-");
    scheduler.setPhase(properties.getSchedulerPhase());
    scheduler.setWaitForTasksToCompleteOnShutdown(properties.isWaitForTasksToCompleteOnShutdown());
    scheduler.setAwaitTerminationSeconds(Math.max(0, properties.getShutdownAwaitSeconds()));
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  @Bean
  public RestClient orchestratorRestClient(
      RestClient.Builder builder,
      OrchestratorClientProperties properties,
      BatchSecurityProperties securityProperties) {
    return builder
        .baseUrl(properties.getBaseUrl())
        .defaultHeader("X-Internal-Secret", securityProperties.getInternalSecret())
        .build();
  }
}
