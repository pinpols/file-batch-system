package io.github.pinpols.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * When worker modules register extra {@link DataSource} beans, JDBC auto-config may not register
 * the usual primary {@code dataSource} for {@code spring.datasource}. Orchestrator and platform
 * MyBatis need that bean.
 */
@Configuration
public class E2ePlatformDataSourceConfiguration {

  @Value("${spring.datasource.url}")
  private String url;

  @Value("${spring.datasource.username}")
  private String username;

  @Value("${spring.datasource.password}")
  private String password;

  @Bean(name = "dataSource")
  @Primary
  @ConditionalOnMissingBean(name = "dataSource")
  public DataSource platformDataSource() {
    return DataSourceBuilder.create()
        .url(url)
        .username(username)
        .password(password)
        .driverClassName("org.postgresql.Driver")
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(JdbcTemplate.class)
  public JdbcTemplate e2ePlatformJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  /**
   * 组合式 E2E 应用必须为标准名称和 Orchestrator 限定名称使用同一个平台连接池，
   * 这样事务绑定的 SQL 夹具才会使用同一连接。
   */
  @Bean
  static BeanDefinitionRegistryPostProcessor aliasOrchestratorPlatformDataSourceWhenPresent() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (registry.containsBeanDefinition("orchestratorPlatformDataSource")
            && registry.containsBeanDefinition("dataSource")) {
          registry.removeBeanDefinition("dataSource");
          registry.registerAlias("orchestratorPlatformDataSource", "dataSource");
        }
      }

      @Override
      public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
    };
  }
}
