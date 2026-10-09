package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("OIDC 配置环境守卫:限制回环 HTTP 仅用于本地测试")
class ConsoleOidcProfileGuardTest {

  @Test
  @DisplayName("本地与测试环境允许回环 HTTP 覆盖")
  void shouldPermitLoopbackHttpOverride_whenProfileIsLocalOrTest() {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    properties.setAllowLoopbackHttp(true);
    MockEnvironment local = new MockEnvironment();
    local.setActiveProfiles("local");
    MockEnvironment test = new MockEnvironment();
    test.setActiveProfiles("test");

    assertThatCode(() -> new ConsoleOidcProfileGuard(properties, local).validate())
        .doesNotThrowAnyException();
    assertThatCode(() -> new ConsoleOidcProfileGuard(properties, test).validate())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("生产环境拒绝回环 HTTP 覆盖")
  void shouldRejectLoopbackHttpOverride_whenProfileIsProduction() {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    properties.setAllowLoopbackHttp(true);

    MockEnvironment production = new MockEnvironment();
    production.setActiveProfiles("prod");

    assertThatThrownBy(() -> new ConsoleOidcProfileGuard(properties, production).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed only in local/test profiles");
  }

  @Test
  @DisplayName("混合生产 profile 时拒绝回环 HTTP 覆盖")
  void shouldRejectLoopbackHttpOverride_whenProductionProfileIsMixedWithLocal() {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    properties.setAllowLoopbackHttp(true);

    MockEnvironment mixed = new MockEnvironment();
    mixed.setActiveProfiles("local", "prod");

    assertThatThrownBy(() -> new ConsoleOidcProfileGuard(properties, mixed).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed only in local/test profiles");
  }

  @Test
  @DisplayName("本地压测叠加 profile 时仍允许回环 HTTP 覆盖")
  void shouldPermitLoopbackHttpOverride_whenLocalProfileIsCombinedWithBenchmark() {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    properties.setAllowLoopbackHttp(true);
    MockEnvironment localBenchmark = new MockEnvironment();
    localBenchmark.setActiveProfiles("local", "benchmark");

    assertThatCode(() -> new ConsoleOidcProfileGuard(properties, localBenchmark).validate())
        .doesNotThrowAnyException();
  }
}
