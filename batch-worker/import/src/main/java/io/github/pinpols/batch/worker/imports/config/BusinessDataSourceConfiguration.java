package io.github.pinpols.batch.worker.imports.config;

import com.zaxxer.hikari.HikariConfig;
import io.github.pinpols.batch.common.config.BatchPgSessionProperties;
import io.github.pinpols.batch.common.config.BusinessDataSourceProperties;
import io.github.pinpols.batch.common.config.BusinessRoutingProperties;
import io.github.pinpols.batch.common.mapper.BusinessTenantPlacementMapper;
import io.github.pinpols.batch.common.rls.RlsPolicyHealthIndicator;
import io.github.pinpols.batch.common.rls.RlsProperties;
import io.github.pinpols.batch.common.rls.RlsStartupFailFastCheck;
import io.github.pinpols.batch.worker.core.config.WorkerDataSourceSupport;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 业务数据源配置，装配逻辑委托 {@link WorkerDataSourceSupport}（三个 worker 模块共用）。 */
@Configuration("importWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties({
  BusinessDataSourceProperties.class,
  BusinessRoutingProperties.class,
  RlsProperties.class
})
@RequiredArgsConstructor
public class BusinessDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "importBusinessHikariConfig")
  @ConfigurationProperties("batch.datasource.business.hikari")
  public HikariConfig importBusinessHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "importBusinessDataSource")
  public DataSource importBusinessDataSource(
      BusinessDataSourceProperties properties,
      BusinessRoutingProperties routingProperties,
      BusinessTenantPlacementMapper placementMapper,
      @Qualifier("importBusinessHikariConfig") HikariConfig hikariConfig) {
    return WorkerDataSourceSupport.buildBusinessDataSource(
        hikariConfig,
        properties,
        pgSessionProperties,
        routingProperties,
        placementMapper,
        environment,
        "batch-worker-import");
  }

  @Bean(name = "importBusinessSqlSessionFactory")
  public SqlSessionFactory importBusinessSqlSessionFactory(
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource) throws Exception {
    return WorkerDataSourceSupport.buildBusinessSqlSessionFactory(importBusinessDataSource);
  }

  @Bean(name = "importBusinessSqlSessionTemplate")
  public SqlSessionTemplate importBusinessSqlSessionTemplate(
      @Qualifier("importBusinessSqlSessionFactory")
          SqlSessionFactory importBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(importBusinessSqlSessionFactory);
  }

  /**
   * RLS 闭世界健康探针 —— import worker 也直接读写 biz 库，与 process 同等守护（缺 ENABLE/FORCE/合规 policy 即 DOWN）。默认开，经
   * {@code batch.business.rls.health-check.enabled=false} 关（测试库未跑 rls-phase-a）。
   */
  @Bean(name = "importRlsPolicyHealthIndicator")
  @ConditionalOnProperty(
      name = "batch.business.rls.health-check.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RlsPolicyHealthIndicator importRlsPolicyHealthIndicator(
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource,
      RlsProperties rlsProperties) {
    return WorkerDataSourceSupport.buildRlsPolicyHealthIndicator(
        importBusinessDataSource, rlsProperties);
  }

  /** 启动期 RLS 闭世界 fail-fast 守门 —— opt-in(默认 false 不阻断),双守门避免牵连无 biz datasource 的上下文。 */
  @Bean(name = "importRlsStartupFailFastCheck")
  @ConditionalOnProperty(name = "batch.rls.startup-fail-fast", havingValue = "true")
  @ConditionalOnBean(name = "importBusinessDataSource")
  public RlsStartupFailFastCheck importRlsStartupFailFastCheck(
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource,
      RlsProperties rlsProperties) {
    return WorkerDataSourceSupport.buildRlsStartupFailFastCheck(
        importBusinessDataSource, rlsProperties);
  }
}
