package com.example.batch.common.config;

import com.example.batch.common.tenant.ActiveTenantRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把 {@link ActiveTenantRegistry} 注册为 AutoConfiguration bean。
 *
 * <p>batch-common 跨模块共享 bean 一律走 AutoConfiguration.imports(本类已登记),不靠下游 {@code @Component} 扫描——精简
 * application(e2e 专用 app 等)的 scanBasePackages 不保证覆盖 {@code com.example.batch.common.tenant},曾导致
 * console WebhookDeliveryRelay 注入失败、 e2e 上下文崩溃(2026-06-12 双栈回归实测)。
 */
@AutoConfiguration
public class BatchTenantRoutingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ActiveTenantRegistry activeTenantRegistry(JdbcTemplate jdbcTemplate) {
    return new ActiveTenantRegistry(jdbcTemplate);
  }
}
