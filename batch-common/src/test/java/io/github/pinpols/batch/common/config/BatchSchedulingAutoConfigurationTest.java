package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.lifecycle.BatchLifecyclePhases;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

@DisplayName("调度自动配置:托管阶段与停机等待默认值,以及线程池参数越界时的校验失败")
class BatchSchedulingAutoConfigurationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("创建任务调度器时采用托管调度器阶段,停机等待任务完成并保留两分钟终止等待")
  void shouldApplyManagedPhaseAndShutdownDrainDefaults_whenSchedulerCreated() {
    BatchSchedulingProperties properties = new BatchSchedulingProperties();
    @SuppressWarnings("unchecked")
    ObjectProvider<ErrorHandler> errorHandlerProvider = mock(ObjectProvider.class);

    TaskScheduler taskScheduler =
        new BatchSchedulingAutoConfiguration().taskScheduler(properties, errorHandlerProvider);

    assertThat(properties.getPhase()).isEqualTo(BatchLifecyclePhases.MANAGED_SCHEDULER);
    assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isTrue();
    assertThat(properties.getAwaitTerminationSeconds()).isEqualTo(120);
    assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
    assertThat(((ThreadPoolTaskScheduler) taskScheduler).getPhase())
        .isEqualTo(properties.getPhase());
  }

  @Test
  @DisplayName("线程池大小为零、线程名前缀为空白、终止等待为负数时逐项给出校验失败提示")
  void shouldRejectInvalidSchedulingBounds_whenPoolAndTimeoutOutOfRange() {
    BatchSchedulingProperties properties = new BatchSchedulingProperties();
    properties.setPoolSize(0);
    properties.setThreadNamePrefix(" ");
    properties.setAwaitTerminationSeconds(-1);

    assertThat(validator.validate(properties))
        .extracting(Object::toString)
        .anyMatch(message -> message.contains("pool-size"))
        .anyMatch(message -> message.contains("thread-name-prefix"))
        .anyMatch(message -> message.contains("await-termination-seconds"));
  }
}
