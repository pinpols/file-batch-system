package com.example.batch.common.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 覆盖 Spring Boot 自动配置的<strong>唯一</strong>平台库 {@link DataSource}（bean 名通常为 {@code dataSource}）：写入
 * JDBC {@code ApplicationName} 与 {@code statement_timeout} / {@code
 * idle_in_transaction_session_timeout}。
 *
 * <p>Spring Boot 4 不再提供 {@code HikariConfigCustomizer}；若对所有 {@link HikariDataSource} bean 统一后处理会与
 * Worker 双池（platform/business）打架，因此此处<strong>仅</strong>匹配 beanName={@code dataSource}。
 *
 * <p>显式装配路径：Worker 双 {@link HikariDataSource}、Console 读写分离双池——须在对应 Configuration 里调用 {@link
 * HikariPgSessionSupport}。
 */
@AutoConfiguration
@ConditionalOnClass(HikariDataSource.class)
@EnableConfigurationProperties(BatchPgSessionProperties.class)
public class BatchPgSessionAutoConfiguration {

  /**
   * Boot 默认单数据源注册的 bean 名稳定为 {@code dataSource}；双数据源 Worker 的平台池 bean 名为 {@code
   * *PlatformDataSource} 等，不会被此处理器改写。
   */
  public static final String BOOT_SINGLE_DATASOURCE_BEAN_NAME = "dataSource";

  @Bean
  @ConditionalOnProperty(
      prefix = "batch.datasource.pg-session",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public BeanPostProcessor batchPgSessionBootSingleDataSourceCustomizer(
      Environment environment, BatchPgSessionProperties pgSessionProperties) {
    return new BeanPostProcessor() {

      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!(bean instanceof HikariDataSource ds)) {
          return bean;
        }
        if (!BOOT_SINGLE_DATASOURCE_BEAN_NAME.equals(beanName)) {
          return bean;
        }
        String applicationName =
            environment.getProperty("spring.application.name", "batch-application");
        HikariPgSessionSupport.applyPlatform(ds, pgSessionProperties, applicationName);
        return ds;
      }
    };
  }
}
