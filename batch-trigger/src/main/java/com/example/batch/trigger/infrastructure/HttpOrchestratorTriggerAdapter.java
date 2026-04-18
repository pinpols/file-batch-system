package com.example.batch.trigger.infrastructure;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 基于 HTTP 协议的 Orchestrator 触发适配器，通过 {@code RestClient} 向 Orchestrator
 * 内部接口 {@code /internal/orchestrator/launch} 发送 POST 请求。
 * 连接超时、重试等策略由注入的 {@code orchestratorRestClient} Bean 统一配置；
 * HTTP 非 2xx 响应将由 RestClient 默认错误处理器抛出异常，上层服务需捕获并处理。
 */
@Component
@RequiredArgsConstructor
public class HttpOrchestratorTriggerAdapter implements OrchestratorTriggerAdapter {

  private final RestClient orchestratorRestClient;

  @Override
  public LaunchResponse sendTrigger(LaunchRequest request) {
    return orchestratorRestClient
        .post()
        .uri("/internal/orchestrator/launch")
        .body(request)
        .retrieve()
        .body(LaunchResponse.class);
  }
}
