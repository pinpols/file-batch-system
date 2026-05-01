package com.example.batch.console.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * P2-4: 仅在 {@code batch.console.read-replica.enabled=true} 时生效——为 console-api 装配主/从两套 HikariPool，由
 * {@link ReadReplicaRoutingDataSource} 按事务 readOnly 标志 + force-primary hint 路由，并在从库连接失败时 fail-open
 * 降级到主库。
 *
 * <p>外层包 {@link LazyConnectionDataSourceProxy}：让 Spring 真正调 {@code getConnection()} 之前 {@code
 * TransactionSynchronizationManager} 的 readOnly 标志已被设置（标注的事务进入时机）， 否则按 currentLookupKey
 * 取连接池时拿不到正确的标志。
 *
 * <p>未启用时本类完全不创建 bean，回退到 Spring Boot 默认主 DataSource 自动配置，行为同历史。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "batch.console.read-replica.enabled", havingValue = "true")
@EnableConfigurationProperties(ReadReplicaProperties.class)
public class ReadReplicaDataSourceConfiguration {

  /** 单独暴露 primary HikariPool：让 {@link ReplicaLagMonitor} 等需要"显式走主库"的组件直接注入。 */
  @Bean(name = "consolePrimaryDataSource")
  public DataSource consolePrimaryDataSource(ReadReplicaProperties props) {
    if (props.getPrimary().getUrl() == null) {
      throw new IllegalStateException(
          "batch.console.read-replica.enabled=true requires primary.url");
    }
    return buildPool(props.getPrimary(), "console-primary");
  }

  @Bean(name = "consoleReplicaDataSource")
  public DataSource consoleReplicaDataSource(ReadReplicaProperties props) {
    if (props.getReplica().getUrl() == null) {
      throw new IllegalStateException(
          "batch.console.read-replica.enabled=true requires replica.url");
    }
    return buildPool(props.getReplica(), "console-replica");
  }

  @Bean
  @Primary
  public DataSource consoleRoutingDataSource(
      ReadReplicaProperties props,
      @Qualifier("consolePrimaryDataSource") DataSource primary,
      @Qualifier("consoleReplicaDataSource") DataSource replica,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    Map<Object, Object> routes = new HashMap<>();
    routes.put(ReadReplicaRoutingDataSource.Route.PRIMARY, primary);
    routes.put(ReadReplicaRoutingDataSource.Route.REPLICA, replica);

    ReadReplicaRoutingDataSource routing =
        new ReadReplicaRoutingDataSource(
            primary,
            props.getFailureThreshold(),
            props.getQuarantineSeconds() * 1_000L,
            meterRegistryProvider);
    routing.setTargetDataSources(routes);
    routing.setDefaultTargetDataSource(primary);
    routing.afterPropertiesSet();
    log.info(
        "console read-replica enabled: primary={}, replica={}, failureThreshold={},"
            + " quarantineSeconds={}",
        props.getPrimary().getUrl(),
        props.getReplica().getUrl(),
        props.getFailureThreshold(),
        props.getQuarantineSeconds());
    return new LazyConnectionDataSourceProxy(routing);
  }

  @Bean
  public ReplicaLagMonitor replicaLagMonitor(
      @Qualifier("consolePrimaryDataSource") DataSource primary,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    return new ReplicaLagMonitor(primary, meterRegistryProvider);
  }

  /** 共用 buildPool（DRY 之前 Primary/Replica 两份重复方法）。 */
  private static HikariDataSource buildPool(ReadReplicaProperties.Pool cfg, String poolName) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(cfg.getUrl());
    ds.setUsername(cfg.getUsername());
    ds.setPassword(cfg.getPassword());
    ds.setDriverClassName(cfg.getDriverClassName());
    ds.setMaximumPoolSize(cfg.getMaximumPoolSize());
    ds.setMinimumIdle(cfg.getMinimumIdle());
    ds.setConnectionTimeout(cfg.getConnectionTimeoutMillis());
    ds.setValidationTimeout(cfg.getValidationTimeoutMillis());
    ds.setIdleTimeout(cfg.getIdleTimeoutMillis());
    ds.setMaxLifetime(cfg.getMaxLifetimeMillis());
    if (cfg.getLeakDetectionThresholdMillis() > 0) {
      ds.setLeakDetectionThreshold(cfg.getLeakDetectionThresholdMillis());
    }
    ds.setPoolName(poolName);
    return ds;
  }
}
