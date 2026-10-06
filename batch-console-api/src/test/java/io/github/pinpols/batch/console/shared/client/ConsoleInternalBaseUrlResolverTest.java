package io.github.pinpols.batch.console.shared.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConsoleInternalBaseUrlResolverTest {

  @Test
  void usesConfiguredUrlWhenItHasNoUnresolvedPlaceholder() {
    MockEnvironment environment = new MockEnvironment();

    String resolved = ConsoleInternalBaseUrlResolver.resolve(
        environment, " http://orchestrator:18080/ ", "batch.console.orchestrator.base-url");

    assertThat(resolved).isEqualTo("http://orchestrator:18080/");
  }

  @Test
  void fallsBackToLocalServerPortWhenRandomPortPlaceholderIsStillUnresolved() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39123");

    String resolved = ConsoleInternalBaseUrlResolver.resolve(
        environment,
        "http://127.0.0.1:" + ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_PLACEHOLDER,
        "batch.console.orchestrator.base-url");

    assertThat(resolved).isEqualTo("http://127.0.0.1:39123");
  }

  @Test
  void failsFastWhenPlaceholderIsUnresolvedAndLocalPortIsUnavailable() {
    MockEnvironment environment = new MockEnvironment();

    assertThatThrownBy(() -> ConsoleInternalBaseUrlResolver.resolve(
            environment,
            "http://127.0.0.1:" + ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_PLACEHOLDER,
            "batch.console.orchestrator.base-url"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("batch.console.orchestrator.base-url is required but not configured");
  }

  @Test
  void doesNotRouteMissingExternalServiceUrlBackToConsole() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39123");

    assertThatThrownBy(() -> ConsoleInternalBaseUrlResolver.resolve(
            environment, null, "batch.console.atomic-worker.base-url"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("batch.console.atomic-worker.base-url is required but not configured");
  }
}
