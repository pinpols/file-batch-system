package io.github.pinpols.batch.testing;

import java.util.List;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * 仅在设置 {@link PlatformWorkerRoleDrill#WORKER_PROPERTY} 或 {@link
 * PlatformWorkerRoleDrill#CONTROL_PLANE_PROPERTY} 时为集成测试上下文挂接受限角色演练。
 */
public class PlatformWorkerRoleDrillContextCustomizerFactory implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    if (!AbstractIntegrationTest.class.isAssignableFrom(testClass)) {
      return null;
    }
    return PlatformWorkerRoleDrill.configuredTarget().map(DrillCustomizer::new).orElse(null);
  }

  /** record 的 equals/hashCode 让同一演练对象的上下文缓存仍可复用。 */
  private record DrillCustomizer(PlatformWorkerRoleDrill.Target target)
      implements ContextCustomizer {

    @Override
    public void customizeContext(
        ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
      context.getBeanFactory().addBeanPostProcessor(PlatformWorkerRoleDrill.postProcessor(target));
      // 没有 Flyway 初始化器时无法授权，演练会退化为 owner 执行，必须直接失败而不是给出空报告。
      context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event -> {
        if (!PlatformWorkerRoleDrill.isPrepared()) {
          throw new IllegalStateException("受限角色演练未完成授权：上下文中没有 Flyway 迁移初始化器");
        }
      });
    }
  }
}
