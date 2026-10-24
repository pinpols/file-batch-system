package io.github.pinpols.batch.e2e.apps;

import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayOpenScheduler;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the explicit settlement child isolated from automatic open scans. */
@Configuration(proxyBeanMethods = false)
public class E2eSettlementProcessConfiguration {

  @Bean
  static BeanDefinitionRegistryPostProcessor disableAutomaticBatchDayOpen() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
          throws BeansException {
        for (String beanName : registry.getBeanDefinitionNames()) {
          if (BatchDayOpenScheduler.class.getName()
              .equals(registry.getBeanDefinition(beanName).getBeanClassName())) {
            registry.removeBeanDefinition(beanName);
          }
        }
      }

      @Override
      public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
          throws BeansException {
        // No bean instances need mutation; the scheduler definition was removed above.
      }
    };
  }
}
