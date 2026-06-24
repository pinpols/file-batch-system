package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 防护测试：限流默认必须开启且各动作阈值为正。
 *
 * <p>历史上 {@code batch.rate-limit} 默认 {@code enabled=false} 且阈值全 0，导致 launch/register/release
 * 无任何硬保护，api_key 泄漏即可被打爆（防接口盗刷审计 P0 缺口）。本测试钉死"默认即生效"，防止后续改动悄悄关回去； 阈值设在远高于任何合法单租速率的高水位，只拦截 runaway
 * 滥用，不误伤正常高吞吐。
 */
class RateLimitPropertiesTest {

  @Test
  @DisplayName("限流默认开启，launch/register/release 阈值均为正高水位")
  void defaultsEnabledWithPositiveHighWatermarks() {
    RateLimitProperties props = new RateLimitProperties();

    assertThat(props.isEnabled()).as("限流默认应开启").isTrue();
    assertThat(props.getMaxNewRequestsPerTenantPerMinute()).as("launch 阈值应为正").isGreaterThan(0L);
    assertThat(props.getMaxRegisterRequestsPerTenantPerMinute())
        .as("worker register 阈值应为正")
        .isGreaterThan(0L);
    assertThat(props.getMaxReleaseRequestsPerTenantPerMinute())
        .as("dispatch release 阈值应为正")
        .isGreaterThan(0L);
  }
}
