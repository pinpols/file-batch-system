package io.github.pinpols.batch.common.health;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 注册 batch-common 自定义健康探针。仅当目标 bean / 类可见时才生效,避免污染不依赖该组件的模块(例如 worker 不引 S3 时,S3HealthIndicator
 * 不注册)。
 */
@AutoConfiguration
@EnableConfigurationProperties(HikariSaturationProperties.class)
public class BatchHealthAutoConfiguration {

  @Bean
  @ConditionalOnClass(HikariDataSource.class)
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(
      name = "batch.health.hikari.enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(name = "hikariSaturationHealthIndicator")
  public HikariSaturationHealthIndicator hikariSaturationHealthIndicator(
      DataSource dataSource, HikariSaturationProperties properties) {
    return new HikariSaturationHealthIndicator(dataSource, properties);
  }
}
