package io.github.pinpols.batch.trigger.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 校验手工 launch 的并发闸门与业务连接池预算。
 *
 * <p>入口请求、trigger outbox relay 和管理接口共用平台库。若入口许可数大于连接池扣除后台保留后的预算，压力升高时
 * 请求会先占满 Hikari，再以 500 和健康探针抖动的形式失败；这不是可接受的“吞吐配置”。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerApiAdmissionConfigurationGuard implements SmartInitializingSingleton {

  private final TriggerRuntimeProperties properties;
  private final DataSource dataSource;

  @Override
  public void afterSingletonsInstantiated() {
    if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
      log.warn("trigger API admission pool-budget validation skipped: datasource is not Hikari");
      return;
    }
    int poolSize = hikariDataSource.getMaximumPoolSize();
    int reserve = properties.getApiLaunchDbReserveConnections();
    int budget = poolSize - reserve;
    if (budget < 1) {
      throw new IllegalStateException(
          "batch.trigger.runtime.api-launch-db-reserve-connections must leave at least one "
              + "platform DB connection for API launch: pool=" + poolSize + ", reserve=" + reserve);
    }
    if (properties.getApiLaunchMinConcurrency() > properties.getApiLaunchMaxConcurrency()) {
      throw new IllegalStateException(
          "batch.trigger.runtime.api-launch-min-concurrency must not exceed "
              + "api-launch-max-concurrency");
    }
    if (properties.getApiLaunchMaxConcurrency() > budget) {
      throw new IllegalStateException(
          "batch.trigger.runtime.api-launch-max-concurrency exceeds platform DB budget: max="
              + properties.getApiLaunchMaxConcurrency()
              + ", pool=" + poolSize
              + ", reserve=" + reserve
              + ", budget=" + budget);
    }
    log.info(
        "trigger API admission budget validated: maxConcurrency={} minConcurrency={} pool={} "
            + "reservedForBackground={}",
        properties.getApiLaunchMaxConcurrency(),
        properties.getApiLaunchMinConcurrency(),
        poolSize,
        reserve);
  }
}
