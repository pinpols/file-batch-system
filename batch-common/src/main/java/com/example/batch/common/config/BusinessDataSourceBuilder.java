package com.example.batch.common.config;

import com.example.batch.common.tenant.routing.BusinessRoutingDataSourceFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/**
 * 统一装配 worker 的 biz 数据源,消除 import/export/process 三处重复(原本各自一份近乎相同的 Hikari 构造 + applyBusiness +
 * 路由包裹,改一处要 3×)。
 *
 * <p>逐字保留原 per-worker 构造语义(条件兜底 + pg-session 应用),**行为等价抽取前**;末尾包成 单片路由 DS(P1-2,全租户落
 * shard-0=现库,无损)。各 worker 的 @Bean 仅保留薄声明(bean 名 / 模块专属 bean 如 process 的 txManager/RLS
 * health),方法体收缩为一行调用本 builder。
 */
public final class BusinessDataSourceBuilder {

  private BusinessDataSourceBuilder() {}

  /**
   * @param hikariConfig 各 worker 的 @ConfigurationProperties("batch.datasource.business.hikari")
   *     bean (已注入 yml 显式配置),本方法只补未显式设的兜底
   * @param properties biz 数据源属性(url/账密/池参数,凭据来源后续接 secrets)
   * @param pgSessionProperties pg-session(RLS/keepalive 兜底)配置
   * @param appName 解析后的应用名(用于 application_name 标识,各 worker 传自己的默认)
   * @return 单片路由 DataSource(已 afterPropertiesSet)
   */
  public static DataSource build(
      HikariConfig hikariConfig,
      BusinessDataSourceProperties properties,
      BatchPgSessionProperties pgSessionProperties,
      String appName) {
    hikariConfig.setJdbcUrl(properties.getUrl());
    hikariConfig.setUsername(properties.getUsername());
    hikariConfig.setPassword(properties.getPassword());
    if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
      hikariConfig.setDriverClassName("org.postgresql.Driver");
    }
    // 业务库连接池显式兜底,避免默认值导致连接耗尽
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
    // HA:主备切换硬化——主动回收旧连接 + 校验快速失败;keepalive 由 applyBusiness 统一兜底
    if (properties.getMaxLifetimeMs() > 0) {
      hikariConfig.setMaxLifetime(properties.getMaxLifetimeMs());
    }
    if (properties.getValidationTimeoutMs() > 0) {
      hikariConfig.setValidationTimeout(properties.getValidationTimeoutMs());
    }
    HikariPgSessionSupport.applyBusiness(hikariConfig, pgSessionProperties, appName + "-business");
    // P1-2:单片路由包裹(shard-0=现库,零行为变更);P2 扩多片只改装配
    return BusinessRoutingDataSourceFactory.singleShard(new HikariDataSource(hikariConfig));
  }
}
