package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("Trigger 调度任务不得承担批量日状态推进")
class TriggerSchedulingOwnershipTest {

  @Test
  @DisplayName("真实组件扫描只注册触发侧任务，批量日生命周期由 Orchestrator 推进")
  void shouldKeepBatchDayLifecycleSchedulingOutOfTriggerComponents() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(true);
    List<String> scheduledTasks = new ArrayList<>();
    List<String> batchDayTasks = new ArrayList<>();
    for (BeanDefinition bean : scanner.findCandidateComponents("io.github.pinpols.batch.trigger")) {
      Class<?> component =
          Class.forName(bean.getBeanClassName(), false, getClass().getClassLoader());
      for (Method method : component.getDeclaredMethods()) {
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class);
        if (scheduled == null) {
          continue;
        }
        String task = component.getSimpleName() + "#" + method.getName();
        scheduledTasks.add(task);
        if (scheduled.fixedDelayString().contains("batch.batch-day.")) {
          batchDayTasks.add(task);
        }
      }
    }

    assertThat(scheduledTasks).as("扫描必须覆盖实际注册的 Trigger 定时任务").isNotEmpty();
    assertThat(batchDayTasks).as("批量日状态机仅允许 Orchestrator 注册").isEmpty();
  }
}
