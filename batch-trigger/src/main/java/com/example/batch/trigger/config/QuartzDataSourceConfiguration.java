package com.example.batch.trigger.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.quartz.autoconfigure.QuartzDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仅在 {@code batch.trigger.quartz-datasource.enabled=true} 时生效：为 Quartz JobStore 装配独占
 * DataSource（{@link QuartzDataSource} qualifier 自动被 Spring Boot {@code QuartzAutoConfiguration}
 * 识别，覆盖默认的主库 DataSource）。
 *
 * <p>未启用时本配置不创建任何 bean，Spring Boot 自动配置回退到使用主 DataSource，行为与历史一致。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "batch.trigger.quartz-datasource.enabled", havingValue = "true")
@EnableConfigurationProperties(QuartzDataSourceProperties.class)
public class QuartzDataSourceConfiguration {

  @Bean
  @QuartzDataSource
  public DataSource quartzDataSource(QuartzDataSourceProperties props) {
    if (props.getUrl() == null || props.getUrl().isBlank()) {
      throw new IllegalStateException(
          "batch.trigger.quartz-datasource.enabled=true but url is not configured");
    }
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(props.getUrl());
    ds.setUsername(props.getUsername());
    ds.setPassword(props.getPassword());
    ds.setDriverClassName(props.getDriverClassName());
    ds.setMaximumPoolSize(props.getMaximumPoolSize());
    ds.setMinimumIdle(props.getMinimumIdle());
    ds.setConnectionTimeout(props.getConnectionTimeoutMillis());
    ds.setIdleTimeout(props.getIdleTimeoutMillis());
    ds.setMaxLifetime(props.getMaxLifetimeMillis());
    ds.setPoolName("batch-trigger-quartz");
    log.info(
        "Quartz JobStore using dedicated DataSource: url={}, poolSize={}",
        props.getUrl(),
        props.getMaximumPoolSize());
    return ds;
  }
}
