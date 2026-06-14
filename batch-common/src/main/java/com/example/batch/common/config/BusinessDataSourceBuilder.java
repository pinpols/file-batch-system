package com.example.batch.common.config;

import com.example.batch.common.tenant.routing.BusinessRoutingDataSourceFactory;
import com.example.batch.common.tenant.routing.HashAndSiloPlacementResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 统一装配 worker 的 biz 数据源,消除 import/export/process 三处重复(原本各自一份近乎相同的 Hikari 构造 + applyBusiness +
 * 路由包裹,改一处要 3×)。
 *
 * <p>逐字保留原 per-worker 构造语义(条件兜底 + pg-session 应用);末尾按 {@link BusinessRoutingProperties}:关(默认)→ 单片路由
 * DS(全租户落 shard-0=现库,无损);开 → 按 shards 各建一池 + multiShard 路由。各 worker 的 @Bean 仅保留薄声明。
 */
public final class BusinessDataSourceBuilder {

  private BusinessDataSourceBuilder() {}

  /**
   * @param hikariConfig 各 worker 的 @ConfigurationProperties("batch.datasource.business.hikari")
   *     bean (已注入 yml 显式配置),本方法只补未显式设的兜底;多片开启时作为 shard-0(现库)的池配置
   * @param properties biz 数据源属性(url/账密/池参数;单片或多片缺省片的凭据)
   * @param pgSessionProperties pg-session(RLS/keepalive 兜底)配置
   * @param routingProperties 多片路由配置(默认 enabled=false → 单片无损)
   * @param appName 解析后的应用名(用于 application_name 标识)
   * @return 路由 DataSource(已 afterPropertiesSet)
   */
  public static DataSource build(
      HikariConfig hikariConfig,
      BusinessDataSourceProperties properties,
      BatchPgSessionProperties pgSessionProperties,
      BusinessRoutingProperties routingProperties,
      String appName) {
    if (routingProperties != null
        && routingProperties.isEnabled()
        && !routingProperties.getShards().isEmpty()) {
      return buildMultiShard(properties, pgSessionProperties, routingProperties, appName);
    }
    return buildSingleShard(hikariConfig, properties, pgSessionProperties, appName);
  }

  /** 单片(无损):沿用注入的 yml hikari bean,补兜底 + pg-session,包成单片路由 DS。 */
  private static DataSource buildSingleShard(
      HikariConfig hikariConfig,
      BusinessDataSourceProperties properties,
      BatchPgSessionProperties pgSessionProperties,
      String appName) {
    hikariConfig.setJdbcUrl(properties.getUrl());
    hikariConfig.setUsername(properties.getUsername());
    hikariConfig.setPassword(properties.getPassword());
    applyPoolDefaults(hikariConfig, properties);
    HikariPgSessionSupport.applyBusiness(hikariConfig, pgSessionProperties, appName + "-business");
    return BusinessRoutingDataSourceFactory.singleShard(new HikariDataSource(hikariConfig));
  }

  /** 多片:每片各建一个 Hikari 池(凭据来自 routing.shards),按 resolver 路由。 */
  private static DataSource buildMultiShard(
      BusinessDataSourceProperties properties,
      BatchPgSessionProperties pgSessionProperties,
      BusinessRoutingProperties routingProperties,
      String appName) {
    Map<String, DataSource> shards = new LinkedHashMap<>();
    for (BusinessRoutingProperties.Shard shard : routingProperties.getShards()) {
      HikariConfig cfg = new HikariConfig();
      cfg.setJdbcUrl(shard.getUrl());
      cfg.setUsername(shard.getUsername());
      cfg.setPassword(shard.getPassword());
      applyPoolDefaults(cfg, properties);
      HikariPgSessionSupport.applyBusiness(
          cfg, pgSessionProperties, appName + "-business-" + shard.getKey());
      shards.put(shard.getKey(), new HikariDataSource(cfg));
    }
    HashAndSiloPlacementResolver resolver =
        new HashAndSiloPlacementResolver(
            routingProperties.getPooledShardCount(), routingProperties.getSiloOverrides());
    return BusinessRoutingDataSourceFactory.multiShard(shards, resolver);
  }

  /** 业务库连接池兜底,避免默认值导致连接耗尽 + 主备切换硬化(逐字保留原 per-worker 语义)。 */
  private static void applyPoolDefaults(
      HikariConfig hikariConfig, BusinessDataSourceProperties properties) {
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
    // HA:主备切换硬化——主动回收旧连接 + 校验快速失败;keepalive 由 applyBusiness 统一兜底
    if (properties.getMaxLifetimeMs() > 0) {
      hikariConfig.setMaxLifetime(properties.getMaxLifetimeMs());
    }
    if (properties.getValidationTimeoutMs() > 0) {
      hikariConfig.setValidationTimeout(properties.getValidationTimeoutMs());
    }
  }
}
