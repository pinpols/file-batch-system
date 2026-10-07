package io.github.pinpols.batch.console.shared.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("控制台内部基础地址解析: 占位符处理与失败快速返回")
class ConsoleInternalBaseUrlResolverTest {

  @Test
  @DisplayName("配置地址不含未解析占位符时,应返回去除首尾空白后的地址")
  void shouldUseConfiguredUrl_whenNoUnresolvedPlaceholder() {
    MockEnvironment environment = new MockEnvironment();

    String resolved = ConsoleInternalBaseUrlResolver.resolve(
        environment, " http://orchestrator:18080/ ", "batch.console.orchestrator.base-url");

    assertThat(resolved).isEqualTo("http://orchestrator:18080/");
  }

  @Test
  @DisplayName("随机端口占位符仍未解析时,应回退到本机服务端口")
  void shouldFallBackToLocalServerPort_whenRandomPortPlaceholderUnresolved() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39123");

    String resolved = ConsoleInternalBaseUrlResolver.resolve(
        environment,
        "http://127.0.0.1:" + ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_PLACEHOLDER,
        "batch.console.orchestrator.base-url");

    assertThat(resolved).isEqualTo("http://127.0.0.1:39123");
  }

  @Test
  @DisplayName("占位符未解析且本机端口不可用时,应快速失败并提示配置项缺失")
  void shouldFailFast_whenPlaceholderUnresolvedAndLocalPortUnavailable() {
    MockEnvironment environment = new MockEnvironment();

    assertThatThrownBy(() -> ConsoleInternalBaseUrlResolver.resolve(
            environment,
            "http://127.0.0.1:" + ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_PLACEHOLDER,
            "batch.console.orchestrator.base-url"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("batch.console.orchestrator.base-url is required but not configured");
  }

  @Test
  @DisplayName("外部服务地址缺失时,不应回退路由到控制台自身地址")
  void shouldNotRouteBackToConsole_whenExternalServiceUrlMissing() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39123");

    assertThatThrownBy(() -> ConsoleInternalBaseUrlResolver.resolve(
            environment, null, "batch.console.atomic-worker.base-url"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("batch.console.atomic-worker.base-url is required but not configured");
  }
}
