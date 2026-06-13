package com.example.batch.worker.processes.config;

import com.example.batch.common.config.BatchPgSessionProperties;
import com.example.batch.common.config.BusinessDataSourceProperties;
import com.example.batch.common.config.HikariPgSessionSupport;
import com.example.batch.common.rls.RlsPolicyHealthIndicator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 业务数据源配置，提供 PROCESS 配置驱动 SQL 加工访问业务库的连接池。 */
@Configuration("processWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
@RequiredArgsConstructor
public class BusinessDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "processBusinessHikariConfig")
  @ConfigurationProperties("batch.datasource.business.hikari")
  public HikariConfig processBusinessHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "processBusinessDataSource")
  public DataSource processBusinessDataSource(
      BusinessDataSourceProperties properties,
      @Qualifier("processBusinessHikariConfig") HikariConfig hikariConfig) {
    hikariConfig.setJdbcUrl(properties.getUrl());
    hikariConfig.setUsername(properties.getUsername());
    hikariConfig.setPassword(properties.getPassword());
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName("org.postgresql.Driver");
    }
    if (hikariConfig.getMaximumPoolSize() <= 1) {
      hikariConfig.setMaximumPoolSize(properties.getMaximumPoolSize());
    }
    if (hikariConfig.getMinimumIdle() < 0) {
      hikariConfig.setMinimumIdle(properties.getMinimumIdle());
    }
    if (hikariConfig.getConnectionTimeout() <= 0) {
      hikariConfig.setConnectionTimeout(properties.getConnectionTimeoutMs());
    }
    if (hikariConfig.getLeakDetectionThreshold() <= 0) {
      hikariConfig.setLeakDetectionThreshold(properties.getLeakDetectionThresholdMs());
    }
    // HA:主备切换硬化——max-lifetime 主动回收旧连接(keepalive 由下方 applyBusiness 兜底)
    if (properties.getMaxLifetimeMs() > 0) {
      hikariConfig.setMaxLifetime(properties.getMaxLifetimeMs());
    }
    String appName = environment.getProperty("spring.application.name", "batch-worker-process");
    HikariPgSessionSupport.applyBusiness(hikariConfig, pgSessionProperties, appName + "-business");
    return new HikariDataSource(hikariConfig);
  }

  @Bean(name = "processBusinessSqlSessionFactory")
  public SqlSessionFactory processBusinessSqlSessionFactory(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource)
      throws Exception {
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(processBusinessDataSource);
    Resource[] businessMappers =
        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/business/*.xml");
    if (businessMappers.length > 0) {
      factoryBean.setMapperLocations(businessMappers);
    }
    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factoryBean.setConfiguration(configuration);
    return factoryBean.getObject();
  }

  @Bean(name = "processBusinessSqlSessionTemplate")
  public SqlSessionTemplate processBusinessSqlSessionTemplate(
      @Qualifier("processBusinessSqlSessionFactory")
          SqlSessionFactory processBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(processBusinessSqlSessionFactory);
  }

  @Bean(name = "processBusinessTransactionManager")
  public DataSourceTransactionManager processBusinessTransactionManager(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource) {
    return new DataSourceTransactionManager(processBusinessDataSource);
  }

  /**
   * 注册 RLS 健康探针到 actuator/health。worker-process 是持有 business 数据源的运行时,缺 ENABLE/FORCE/policy 即报 DOWN,
   * 让平台运维加新 biz 表时漏配 RLS 立刻可见。默认开启;e2e/单库测试经 {@code batch.business.rls.health-check.enabled=false}
   * 关闭(测试库未跑 rls-phase-a 脚本)。
   */
  @Bean(name = "rlsPolicyHealthIndicator")
  @ConditionalOnProperty(
      name = "batch.business.rls.health-check.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RlsPolicyHealthIndicator rlsPolicyHealthIndicator(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource) {
    return new RlsPolicyHealthIndicator(processBusinessDataSource);
  }
}
