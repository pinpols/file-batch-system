package io.github.pinpols.batch.worker.exports.config;

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

/**
 * 业务数据源配置，提供导出任务所需的业务库 MyBatis SqlSession。装配逻辑委托 {@link WorkerDataSourceSupport}（三个 worker 模块共用）。
 */
@Configuration("exportWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties({
  BusinessDataSourceProperties.class,
  BusinessRoutingProperties.class,
  RlsProperties.class
})
@RequiredArgsConstructor
public class BusinessDataSourceConfiguration {

  private final BatchPgSessionProperties pgSessionProperties;
  private final Environment environment;

  @Bean(name = "exportBusinessHikariConfig")
  @ConfigurationProperties("batch.datasource.business.hikari")
  public HikariConfig exportBusinessHikariConfig() {
    return new HikariConfig();
  }

  @Bean(name = "exportBusinessDataSource")
  public DataSource exportBusinessDataSource(
      BusinessDataSourceProperties properties,
      BusinessRoutingProperties routingProperties,
      BusinessTenantPlacementMapper placementMapper,
      @Qualifier("exportBusinessHikariConfig") HikariConfig hikariConfig) {
    return WorkerDataSourceSupport.buildBusinessDataSource(
        hikariConfig,
        properties,
        pgSessionProperties,
        routingProperties,
        placementMapper,
        environment,
        "batch-worker-export");
  }

  @Bean(name = "exportBusinessSqlSessionFactory")
  public SqlSessionFactory exportBusinessSqlSessionFactory(
      @Qualifier("exportBusinessDataSource") DataSource exportBusinessDataSource) throws Exception {
    return WorkerDataSourceSupport.buildBusinessSqlSessionFactory(exportBusinessDataSource);
  }

  @Bean(name = "exportBusinessSqlSessionTemplate")
  public SqlSessionTemplate exportBusinessSqlSessionTemplate(
      @Qualifier("exportBusinessSqlSessionFactory")
          SqlSessionFactory exportBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(exportBusinessSqlSessionFactory);
  }

  /**
   * RLS 闭世界健康探针 —— export worker 也读 biz 库,与 process 同等守护(缺 ENABLE/FORCE/合规 policy 即 DOWN)。 默认开,经
   * {@code batch.business.rls.health-check.enabled=false} 关(测试库未跑 rls-phase-a)。
   */
  @Bean(name = "exportRlsPolicyHealthIndicator")
  @ConditionalOnProperty(
      name = "batch.business.rls.health-check.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RlsPolicyHealthIndicator exportRlsPolicyHealthIndicator(
      @Qualifier("exportBusinessDataSource") DataSource exportBusinessDataSource,
      RlsProperties rlsProperties) {
    return WorkerDataSourceSupport.buildRlsPolicyHealthIndicator(
        exportBusinessDataSource, rlsProperties);
  }

  /** 启动期 RLS 闭世界 fail-fast 守门 —— opt-in(默认 false 不阻断),双守门避免牵连无 biz datasource 的上下文。 */
  @Bean(name = "exportRlsStartupFailFastCheck")
  @ConditionalOnProperty(name = "batch.rls.startup-fail-fast", havingValue = "true")
  @ConditionalOnBean(name = "exportBusinessDataSource")
  public RlsStartupFailFastCheck exportRlsStartupFailFastCheck(
      @Qualifier("exportBusinessDataSource") DataSource exportBusinessDataSource,
      RlsProperties rlsProperties) {
    return WorkerDataSourceSupport.buildRlsStartupFailFastCheck(
        exportBusinessDataSource, rlsProperties);
  }
}
