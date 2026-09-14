package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.config.BatchProfileSupport;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 生产准入硬门禁：禁止以“无限全局活跃作业”配置启动控制面。 */
@Component
@RequiredArgsConstructor
public class ResourceAdmissionStartupGuard implements ApplicationRunner {

  private final Environment environment;
  private final ResourceSchedulerProperties properties;

  @Override
  public void run(ApplicationArguments args) {
    if (!BatchProfileSupport.isProductionProfile(environment)) {
      return;
    }
    if (properties.getGlobalMaxRunningJobs() <= 0) {
      throw new IllegalStateException(
          "production requires batch.resource-scheduler.global-max-running-jobs > 0; "
              + "an unbounded control plane can move an overload directly into PostgreSQL/Kafka");
    }
  }
}
