package com.example.batch.console.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * P2-4: 仅在 {@code batch.console.read-replica.enabled=true} 时生效——为 console-api
 * 装配主/从两套 HikariPool，由 {@link AbstractRoutingDataSource} 根据当前事务 readOnly
 * 标志路由：
 *
 * <ul>
 *   <li>{@code @Transactional(readOnly = true)} → 从库
 *   <li>读写事务 / 无事务 → 主库
 * </ul>
 *
 * <p>外层包 {@link LazyConnectionDataSourceProxy}：让 Spring 真正调 {@code getConnection()} 之前
 * {@code TransactionSynchronizationManager} 的 readOnly 标志已被设置（标注的事务进入时机），
 * 否则按 currentLookupKey 取连接池时拿不到正确的标志。
 *
 * <p>未启用时本类完全不创建 bean，回退到 Spring Boot 默认主 DataSource 自动配置，行为同历史。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "batch.console.read-replica.enabled", havingValue = "true")
@EnableConfigurationProperties(ReadReplicaProperties.class)
public class ReadReplicaDataSourceConfiguration {

  enum Route {
    PRIMARY,
    REPLICA
  }

  @Bean
  @Primary
  public DataSource consoleRoutingDataSource(ReadReplicaProperties props) {
    if (props.getPrimary().getUrl() == null || props.getReplica().getUrl() == null) {
      throw new IllegalStateException(
          "batch.console.read-replica.enabled=true requires both primary.url and replica.url");
    }
    DataSource primary = buildPool(props.getPrimary(), "console-primary");
    DataSource replica = buildPool(props.getReplica(), "console-replica");

    Map<Object, Object> routes = new HashMap<>();
    routes.put(Route.PRIMARY, primary);
    routes.put(Route.REPLICA, replica);

    AbstractRoutingDataSource routing =
        new AbstractRoutingDataSource() {
          @Override
          protected Object determineCurrentLookupKey() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? Route.REPLICA
                : Route.PRIMARY;
          }
        };
    routing.setTargetDataSources(routes);
    routing.setDefaultTargetDataSource(primary);
    routing.afterPropertiesSet();
    log.info(
        "console read-replica enabled: primary={}, replica={}",
        props.getPrimary().getUrl(),
        props.getReplica().getUrl());
    return new LazyConnectionDataSourceProxy(routing);
  }

  private static HikariDataSource buildPool(ReadReplicaProperties.Primary cfg, String poolName) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(cfg.getUrl());
    ds.setUsername(cfg.getUsername());
    ds.setPassword(cfg.getPassword());
    ds.setDriverClassName(cfg.getDriverClassName());
    ds.setMaximumPoolSize(cfg.getMaximumPoolSize());
    ds.setMinimumIdle(cfg.getMinimumIdle());
    ds.setPoolName(poolName);
    return ds;
  }

  private static HikariDataSource buildPool(ReadReplicaProperties.Replica cfg, String poolName) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(cfg.getUrl());
    ds.setUsername(cfg.getUsername());
    ds.setPassword(cfg.getPassword());
    ds.setDriverClassName(cfg.getDriverClassName());
    ds.setMaximumPoolSize(cfg.getMaximumPoolSize());
    ds.setMinimumIdle(cfg.getMinimumIdle());
    ds.setPoolName(poolName);
    return ds;
  }
}
