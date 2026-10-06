package io.github.pinpols.batch.console.shared.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.config.ConsoleAtomicWorkerClientProperties;
import io.github.pinpols.batch.console.config.ConsoleOrchestratorClientProperties;
import io.github.pinpols.batch.console.config.ConsoleTriggerClientProperties;
import io.github.pinpols.batch.console.domain.ops.infrastructure.AtomicWorkerInternalRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

class InternalRestClientLazyInitializationTest {

  private static final String RANDOM_PORT_URL =
      "http://127.0.0.1:" + ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_PLACEHOLDER;

  @Test
  void orchestratorClientWaitsForRandomPortAndThenReusesClient() {
    MockEnvironment environment = new MockEnvironment();
    ConsoleOrchestratorClientProperties properties = new ConsoleOrchestratorClientProperties();
    properties.setBaseUrl(RANDOM_PORT_URL);

    OrchestratorInternalRestClient client = new OrchestratorInternalRestClient(
        restClientBuilders(), properties, new BatchSecurityProperties(), environment);
    environment.setProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39123");

    assertThat(client.client()).isSameAs(client.client());
  }

  @Test
  void triggerClientWaitsForRandomPortAndThenReusesClient() {
    MockEnvironment environment = new MockEnvironment();
    ConsoleTriggerClientProperties properties = new ConsoleTriggerClientProperties();
    properties.setBaseUrl(RANDOM_PORT_URL);

    TriggerInternalRestClient client = new TriggerInternalRestClient(
        restClientBuilders(), properties, new BatchSecurityProperties(), environment);
    environment.setProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39124");

    assertThat(client.client()).isSameAs(client.client());
  }

  @Test
  void disabledAtomicClientDoesNotRequireUrlDuringApplicationStartup() {
    MockEnvironment environment = new MockEnvironment();
    ConsoleAtomicWorkerClientProperties properties = new ConsoleAtomicWorkerClientProperties();
    properties.setEnabled(false);

    AtomicWorkerInternalRestClient client =
        new AtomicWorkerInternalRestClient(restClientBuilders(), properties, environment);
    properties.setBaseUrl("http://atomic-worker:18087");

    assertThat(client.client()).isSameAs(client.client());
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<RestClient.Builder> restClientBuilders() {
    ObjectProvider<RestClient.Builder> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenAnswer(ignored -> RestClient.builder());
    return provider;
  }
}
