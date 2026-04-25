package com.example.batch.trigger.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.quartz.autoconfigure.QuartzDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 仅在 {@code batch.trigger.quartz-datasource.enabled=true} 时生效：为 Quartz JobStore 装配独占
 * DataSource，避免和业务表争 WAL/锁。
 *
 * <p><b>关键修复（v2）</b>：仅声明 {@code @QuartzDataSource} bean 会触发 Spring Boot
 * {@code DataSourceAutoConfiguration} 的 {@code @ConditionalOnMissingBean(DataSource.class)} 跳过——
 * 主 DataSource 不再创建，整个 app 只剩 Quartz DS，MyBatis 业务查询全部错位（没有 batch schema）。
 * 所以这里同时显式声明 {@code @Primary} 主 DataSource，绑定 {@code spring.datasource.*}，确保
 * 业务路径仍走主库。
 *
 * <p>未启用时本类完全不创建 bean，回退到 Spring Boot 默认主 DataSource 自动配置，行为同历史。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "batch.trigger.quartz-datasource.enabled", havingValue = "true")
public class QuartzDataSourceConfiguration {

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource")
  public DataSourceProperties primaryDataSourceProperties() {
    return new DataSourceProperties();
  }

  /**
   * 主 DataSource：业务表（batch / quartz schema），由 MyBatis 等组件默认使用。
   * 必须显式声明，否则与 Quartz DS bean 共存会让 Spring Boot 自动配置跳过主 DS。
   */
  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  public DataSource primaryDataSource() {
    return primaryDataSourceProperties()
        .initializeDataSourceBuilder()
        .type(HikariDataSource.class)
        .build();
  }

  @Bean
  @ConfigurationProperties("batch.trigger.quartz-datasource")
  public QuartzDataSourceProperties quartzDataSourceProperties() {
    return new QuartzDataSourceProperties();
  }

  /**
   * Quartz 独占 DataSource。{@code @QuartzDataSource} qualifier 让 Spring Boot
   * {@code QuartzAutoConfiguration} 把它注入 {@code SchedulerFactoryBean.dataSource}。
   */
  @Bean
  @QuartzDataSource
  public DataSource quartzDataSource(QuartzDataSourceProperties props) {
    if (props.getUrl() == null || props.getUrl().isBlank()) {
      throw new IllegalStateException(
          "batch.trigger.quartz-datasource.enabled=true but url is not configured");
    }
    HikariDataSource ds =
        DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .url(props.getUrl())
            .username(props.getUsername())
            .password(props.getPassword())
            .driverClassName(props.getDriverClassName())
            .build();
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
