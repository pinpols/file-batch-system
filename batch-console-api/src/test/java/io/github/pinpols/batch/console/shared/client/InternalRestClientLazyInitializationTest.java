package io.github.pinpols.batch.console.shared.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.config.ConsoleAtomicWorkerClientProperties;
import io.github.pinpols.batch.console.config.ConsoleOrchestratorClientProperties;
import io.github.pinpols.batch.console.config.ConsoleTriggerClientProperties;
import io.github.pinpols.batch.console.domain.ops.infrastructure.AtomicWorkerInternalRestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

@DisplayName("内部 REST 客户端懒初始化: 随机端口等待与实例复用")
class InternalRestClientLazyInitializationTest {

  private static final String RANDOM_PORT_URL =
      "http://127.0.0.1:" + ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_PLACEHOLDER;

  @Test
  @DisplayName("编排服务客户端在随机端口就绪后才初始化,并复用同一实例")
  void shouldWaitForRandomPortThenReuseClient_whenOrchestratorClient() {
    MockEnvironment environment = new MockEnvironment();
    ConsoleOrchestratorClientProperties properties = new ConsoleOrchestratorClientProperties();
    properties.setBaseUrl(RANDOM_PORT_URL);

    OrchestratorInternalRestClient client = new OrchestratorInternalRestClient(
        restClientBuilders(), properties, new BatchSecurityProperties(), environment);
    environment.setProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39123");

    assertThat(client.client()).isSameAs(client.client());
  }

  @Test
  @DisplayName("触发服务客户端在随机端口就绪后才初始化,并复用同一实例")
  void shouldWaitForRandomPortThenReuseClient_whenTriggerClient() {
    MockEnvironment environment = new MockEnvironment();
    ConsoleTriggerClientProperties properties = new ConsoleTriggerClientProperties();
    properties.setBaseUrl(RANDOM_PORT_URL);

    TriggerInternalRestClient client = new TriggerInternalRestClient(
        restClientBuilders(), properties, new BatchSecurityProperties(), environment);
    environment.setProperty(ConsoleInternalBaseUrlResolver.LOCAL_SERVER_PORT_KEY, "39124");

    assertThat(client.client()).isSameAs(client.client());
  }

  @Test
  @DisplayName("原子任务客户端处于禁用状态时,启动阶段不应因缺少服务地址失败且实例保持复用")
  void shouldNotRequireUrlAtStartup_whenAtomicClientDisabled() {
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
