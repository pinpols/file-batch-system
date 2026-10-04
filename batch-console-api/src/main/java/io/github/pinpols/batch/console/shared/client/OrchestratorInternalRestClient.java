package io.github.pinpols.batch.console.shared.client;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleOrchestratorClientProperties;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 统一构造调用 orchestrator {@code /internal/**} 的 {@link RestClient}：
 *
 * <ul>
 *   <li>baseUrl 来自 {@link ConsoleOrchestratorClientProperties#getBaseUrl()}（支持 {@code @Value} 占位符 /
 *       Spring profile 变量解析）
 *   <li>defaultHeader {@code X-Internal-Secret} 注入 {@link
 *       BatchSecurityProperties#getInternalSecret()} —— 生产环境关闭 bypass-mode 后必须的鉴权 header
 * </ul>
 *
 * <p>P0-3 (ADR audit 2026-05-14)：本类是唯一构造 orchestrator internal client 的入口；业务类不再自己 拼
 * baseUrl/header。新的代理 service 必须注入本类，禁止重新声明 {@code restClientBuilder.baseUrl(...).build()}。
 */
@Component
public class OrchestratorInternalRestClient {

  /** orchestrator-side {@code InternalAuthFilter} 期望的鉴权 header 名（保持单一字面量来源）。 */
  public static final String X_INTERNAL_SECRET_HEADER = CommonConstants.INTERNAL_SECRET_HEADER;

  // 构造期只消费一次 prototype builder；请求期复用已冻结配置的线程安全 client，避免连接池抖动。
  private final RestClient client;

  public OrchestratorInternalRestClient(
      ObjectProvider<RestClient.Builder> restClientBuilderProvider,
      ConsoleOrchestratorClientProperties orchestratorClientProperties,
      BatchSecurityProperties batchSecurityProperties,
      Environment environment) {
    String baseUrl = resolveUrl(environment, orchestratorClientProperties.getBaseUrl());
    Guard.requireText(baseUrl, "orchestrator base url is not configured");
    String secret = batchSecurityProperties.getInternalSecret();
    RestClient.Builder builder = restClientBuilderProvider
        .getObject()
        .baseUrl(baseUrl)
        .requestFactory(ClientHttpRequestFactoryBuilder.detect()
            .build(HttpClientSettings.defaults()
                .withConnectTimeout(
                    Duration.ofMillis(orchestratorClientProperties.getConnectTimeoutMillis()))
                .withReadTimeout(
                    Duration.ofMillis(orchestratorClientProperties.getReadTimeoutMillis()))));
    if (Texts.hasText(secret)) {
      builder = builder.defaultHeader(X_INTERNAL_SECRET_HEADER, secret);
    }
    this.client = builder.build();
  }

  /** 返回组件生命周期内复用的线程安全客户端，保留底层连接池和 keep-alive。 */
  public RestClient client() {
    return client;
  }

  private static String resolveUrl(Environment environment, String raw) {
    if (raw == null) {
      return null;
    }
    return environment.resolvePlaceholders(raw);
  }
}
