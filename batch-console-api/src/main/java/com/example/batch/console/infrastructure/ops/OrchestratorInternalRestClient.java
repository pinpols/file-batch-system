package com.example.batch.console.infrastructure.ops;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class OrchestratorInternalRestClient {

  /** orchestrator-side {@code InternalAuthFilter} 期望的鉴权 header 名（保持单一字面量来源）。 */
  public static final String X_INTERNAL_SECRET_HEADER = "X-Internal-Secret";

  private final RestClient.Builder restClientBuilder;
  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final BatchSecurityProperties batchSecurityProperties;
  private final Environment environment;

  /** 构造一个新的 {@link RestClient}，已绑定 baseUrl 与 internal-secret header。 */
  public RestClient build() {
    String baseUrl = resolveUrl(orchestratorClientProperties.getBaseUrl());
    String secret = batchSecurityProperties.getInternalSecret();
    RestClient.Builder builder = restClientBuilder.baseUrl(baseUrl);
    if (Texts.hasText(secret)) {
      builder = builder.defaultHeader(X_INTERNAL_SECRET_HEADER, secret);
    }
    return builder.build();
  }

  private String resolveUrl(String raw) {
    if (raw == null) {
      return null;
    }
    return environment.resolvePlaceholders(raw);
  }
}
