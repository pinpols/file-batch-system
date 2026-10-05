package io.github.pinpols.batch.console.domain.ops.infrastructure;

import io.github.pinpols.batch.console.config.ConsoleAtomicWorkerClientProperties;
import io.github.pinpols.batch.console.shared.client.ConsoleInternalBaseUrlResolver;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.client.RestClient;

/**
 * 统一构造调用 batch-worker-atomic Actuator {@code /actuator/atomicruntime} 端点的 {@link
 * RestClient}(Round-3 #8)。
 *
 * <p>不注 {@code X-Internal-Secret} —— atomic worker 当前只暴露 actuator,鉴权走 management-port 隔离 / actuator
 * 现有链。若未来在 atomic 加 InternalAuthFilter,本类可平移注 secret(不影响调用方)。
 *
 * <p>P0-3 单一入口约束:业务类禁止自行 {@code RestClient.create(baseUrl)};新增依赖请注入本类。
 */
@Component
public class AtomicWorkerInternalRestClient {

  private final ConsoleAtomicWorkerClientProperties properties;
  private final Supplier<RestClient> clientSupplier;

  public AtomicWorkerInternalRestClient(
      ObjectProvider<RestClient.Builder> restClientBuilderProvider,
      ConsoleAtomicWorkerClientProperties properties,
      Environment environment) {
    this.properties = properties;
    this.clientSupplier =
        SingletonSupplier.of(() -> buildClient(restClientBuilderProvider, properties, environment));
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  /** 返回组件生命周期内复用的线程安全客户端。 */
  public RestClient client() {
    return clientSupplier.get();
  }

  private static RestClient buildClient(
      ObjectProvider<RestClient.Builder> restClientBuilderProvider,
      ConsoleAtomicWorkerClientProperties properties,
      Environment environment) {
    String baseUrl = ConsoleInternalBaseUrlResolver.resolve(
        environment, properties.getBaseUrl(), "batch.console.atomic-worker.base-url");
    return restClientBuilderProvider
        .getObject()
        .baseUrl(baseUrl)
        .requestFactory(ClientHttpRequestFactoryBuilder.detect()
            .build(HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .withReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()))))
        .build();
  }
}
