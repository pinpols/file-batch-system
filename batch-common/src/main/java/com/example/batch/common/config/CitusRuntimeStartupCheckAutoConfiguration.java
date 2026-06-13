package com.example.batch.common.config;

import com.example.batch.common.health.CitusRuntimeStartupCheck;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 注册 {@link CitusRuntimeStartupCheck},仅当 {@code batch.citus.enabled=true} 时生效。 普通 PG 部署(默认)不加载,零影响。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "batch.citus", name = "enabled", havingValue = "true")
public class CitusRuntimeStartupCheckAutoConfiguration {

  @Bean
  public CitusRuntimeStartupCheck citusRuntimeStartupCheck(
      ObjectProvider<DataSource> dataSourceProvider) {
    return new CitusRuntimeStartupCheck(dataSourceProvider);
  }
}
