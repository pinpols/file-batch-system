package com.example.batch.common.health;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Hikari 连接池饱和度健康探针。
 *
 * <p>Spring Boot 自带 `DataSourceHealthIndicator` 只测连接可用(SELECT 1),不知道 active /
 * max。生产事故里"连接没挂但池子打满"才是常态,SaaS 多租路径分分钟把 50 个连接吃光阻塞所有请求 —— 用这个探针在 90% 时 DOWN 让 k8s 把流量调走,比业务超时更早暴露。
 *
 * <p>UP/DOWN 阈值通过 {@link HikariSaturationProperties} 配置,默认 0.9(90% active)。
 */
public class HikariSaturationHealthIndicator implements HealthIndicator {

  private final DataSource dataSource;
  private final double saturationThreshold;

  public HikariSaturationHealthIndicator(
      DataSource dataSource, HikariSaturationProperties properties) {
    this.dataSource = dataSource;
    this.saturationThreshold = properties.getThreshold();
  }

  @Override
  public Health health() {
    if (!(dataSource instanceof HikariDataSource hikari)) {
      return Health.unknown().withDetail("reason", "not-a-hikari-datasource").build();
    }
    HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
    if (pool == null) {
      return Health.unknown().withDetail("reason", "pool-mxbean-unavailable").build();
    }
    int active = pool.getActiveConnections();
    int total = hikari.getMaximumPoolSize();
    int idle = pool.getIdleConnections();
    int waiting = pool.getThreadsAwaitingConnection();
    double saturation = total > 0 ? (double) active / total : 0.0;
    Health.Builder builder =
        (saturation >= saturationThreshold ? Health.down() : Health.up())
            .withDetail("active", active)
            .withDetail("idle", idle)
            .withDetail("max", total)
            .withDetail("waiting", waiting)
            .withDetail("saturation", saturation)
            .withDetail("threshold", saturationThreshold);
    if (waiting > 0) {
      // 有线程在排队等连接 = 池子已经事实饱和,即使 active < threshold 也下沉
      builder = Health.down().withDetails(builder.build().getDetails());
    }
    return builder.build();
  }
}
