package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorStateMachine;
import io.github.pinpols.batch.orchestrator.config.SensorProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code batch.sensor.enabled} 开关装配测试：默认（matchIfMissing）装配
 * {@link SensorPollScheduler}，显式 {@code false} 时不装配（ADR-028 总开关）。
 */
class SensorPollSchedulerWiringTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(SensorPollScheduler.class, SensorDeps.class);

  @Test
  void schedulerPresentByDefault() {
    runner.run(context -> assertThat(context).hasSingleBean(SensorPollScheduler.class));
  }

  @Test
  void schedulerAbsentWhenExplicitlyDisabled() {
    runner
        .withPropertyValues("batch.sensor.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(SensorPollScheduler.class));
  }

  /** 仅提供 {@link SensorPollScheduler} 构造器所需的最小依赖（其余用 mock）。 */
  @Configuration(proxyBeanMethods = false)
  static class SensorDeps {

    @Bean
    SensorProperties sensorProperties() {
      return new SensorProperties();
    }

    @Bean
    WorkflowNodeRunMapper nodeRunMapper() {
      return mock(WorkflowNodeRunMapper.class);
    }

    @Bean
    WorkflowRunMapper workflowRunMapper() {
      return mock(WorkflowRunMapper.class);
    }

    @Bean
    SensorStateMachine stateMachine() {
      return mock(SensorStateMachine.class);
    }

    @Bean
    OrchestratorGracefulShutdown gracefulShutdown() {
      return mock(OrchestratorGracefulShutdown.class);
    }
  }
}
