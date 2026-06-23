package io.github.pinpols.batch.console.domain.ops.infrastructure;

import io.github.pinpols.batch.console.config.ConsoleAtomicWorkerClientProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
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
@RequiredArgsConstructor
public class AtomicWorkerInternalRestClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
  private final ConsoleAtomicWorkerClientProperties properties;
  private final Environment environment;

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  /** 构造一个新的 {@link RestClient},baseUrl 已绑定 + 短超时(actuator 调用应是毫秒级)。 */
  public RestClient build() {
    String baseUrl = environment.resolvePlaceholders(properties.getBaseUrl());
    return restClientBuilderProvider
        .getObject()
        .baseUrl(baseUrl)
        .requestFactory(
            ClientHttpRequestFactoryBuilder.detect()
                .build(
                    HttpClientSettings.defaults()
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)))
        .build();
  }
}
