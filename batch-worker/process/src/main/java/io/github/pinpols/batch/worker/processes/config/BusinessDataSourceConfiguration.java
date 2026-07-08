package io.github.pinpols.batch.worker.processes.config;

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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 业务数据源配置，提供 PROCESS 配置驱动 SQL 加工访问业务库的连接池。装配逻辑委托 {@link WorkerDataSourceSupport}（三个 worker 模块共用）。
 */
@Configuration("processWorkerBusinessDataSourceConfiguration")
@EnableConfigurationProperties({
  BusinessDataSourceProperties.class,
  BusinessRoutingProperties.class,
  RlsProperties.class
})
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
      BusinessRoutingProperties routingProperties,
      BusinessTenantPlacementMapper placementMapper,
      @Qualifier("processBusinessHikariConfig") HikariConfig hikariConfig) {
    return WorkerDataSourceSupport.buildBusinessDataSource(
        hikariConfig,
        properties,
        pgSessionProperties,
        routingProperties,
        placementMapper,
        environment,
        "batch-worker-process");
  }

  @Bean(name = "processBusinessSqlSessionFactory")
  public SqlSessionFactory processBusinessSqlSessionFactory(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource)
      throws Exception {
    return WorkerDataSourceSupport.buildBusinessSqlSessionFactory(processBusinessDataSource);
  }

  @Bean(name = "processBusinessSqlSessionTemplate")
  public SqlSessionTemplate processBusinessSqlSessionTemplate(
      @Qualifier("processBusinessSqlSessionFactory")
          SqlSessionFactory processBusinessSqlSessionFactory) {
    return new SqlSessionTemplate(processBusinessSqlSessionFactory);
  }

  /**
   * 业务库 TransactionManager —— 被 {@code SqlTransformComputePlugin} 通过
   * {@code @Transactional(transactionManager = "processBusinessTransactionManager")} 显式引用，process
   * 特有，import/export 无对应调用点，保留为模块差异（详见任务报告）。
   */
  @Bean(name = "processBusinessTransactionManager")
  public DataSourceTransactionManager processBusinessTransactionManager(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource) {
    return WorkerDataSourceSupport.buildTransactionManager(processBusinessDataSource);
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
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource,
      RlsProperties rlsProperties) {
    return WorkerDataSourceSupport.buildRlsPolicyHealthIndicator(
        processBusinessDataSource, rlsProperties);
  }

  /**
   * 启动期 RLS 闭世界 fail-fast 守门 —— opt-in。仅当 {@code batch.rls.startup-fail-fast=true} 装配(默认
   * false=不阻断启动,只靠 health DOWN 可见),且上下文里有 business datasource。复用 health indicator 同一套闭世界检查逻辑。
   *
   * <p>{@code @ConditionalOnBean(processBusinessDataSource)} 守门:无 biz datasource 的上下文不装配本副作用
   * bean,避免牵连其他 worker 启动失败。
   */
  @Bean(name = "rlsStartupFailFastCheck")
  @ConditionalOnProperty(name = "batch.rls.startup-fail-fast", havingValue = "true")
  @ConditionalOnBean(name = "processBusinessDataSource")
  public RlsStartupFailFastCheck rlsStartupFailFastCheck(
      @Qualifier("processBusinessDataSource") DataSource processBusinessDataSource,
      RlsProperties rlsProperties) {
    return WorkerDataSourceSupport.buildRlsStartupFailFastCheck(
        processBusinessDataSource, rlsProperties);
  }
}
