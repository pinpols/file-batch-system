package io.github.pinpols.batch.console.domain.ops.infrastructure;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleTriggerClientProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 统一构造调用 batch-trigger {@code /api/**} 的 {@link RestClient}。
 *
 * <p>batch-trigger 的 {@code InternalSecretFilter} 对非 actuator 端点强制要求 {@code X-Internal-Secret}
 * header,生产 profile 关 bypass-mode 后缺失 header → 401。本类是唯一构造 trigger client 的入口,
 * 业务类(ConsoleJobOpsSupport / DefaultConsoleJobApprovalService 等)统一注入本类, 禁止自行
 * `restClientBuilder.baseUrl(...).build()` 后直接发请求。
 *
 * <p>2026-05-16 P0-1 整改:之前两条 trigger 调用直接 build client 没注入 secret, 生产 bypass=false 下手工触发 / Catch-Up
 * 审批回调稳定 401。
 */
@Component
@RequiredArgsConstructor
public class TriggerInternalRestClient {

  /** batch-trigger {@code InternalSecretFilter} 期望的鉴权 header 名,保持单一字面量来源。 */
  public static final String X_INTERNAL_SECRET_HEADER = "X-Internal-Secret";

  /** P2-1:见 OrchestratorInternalRestClient 同名字段注释。 */
  private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;

  private final ConsoleTriggerClientProperties triggerClientProperties;
  private final BatchSecurityProperties batchSecurityProperties;
  private final Environment environment;

  /** 与 OrchestratorInternalRestClient 同源的保守超时;trigger 抖动时不让 Tomcat worker 永久阻塞。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  /** 构造一个新的 {@link RestClient},已绑定 baseUrl + internal-secret + 5s/30s 超时。 */
  public RestClient build() {
    String baseUrl = environment.resolvePlaceholders(triggerClientProperties.getBaseUrl());
    String secret = batchSecurityProperties.getInternalSecret();
    RestClient.Builder builder =
        restClientBuilderProvider
            .getObject()
            .baseUrl(baseUrl)
            .requestFactory(
                ClientHttpRequestFactoryBuilder.detect()
                    .build(
                        HttpClientSettings.defaults()
                            .withConnectTimeout(CONNECT_TIMEOUT)
                            .withReadTimeout(READ_TIMEOUT)));
    if (Texts.hasText(secret)) {
      builder = builder.defaultHeader(X_INTERNAL_SECRET_HEADER, secret);
    }
    return builder.build();
  }
}
