package com.example.batch.trigger.infrastructure;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 基于 HTTP 协议的 Orchestrator 触发适配器，通过 {@code RestClient} 向 Orchestrator 内部接口 {@code
 * /internal/orchestrator/launch} 发送 POST 请求。 连接超时、重试等策略由注入的 {@code orchestratorRestClient} Bean
 * 统一配置； HTTP 非 2xx 响应将由 RestClient 默认错误处理器抛出异常，上层服务需捕获并处理。
 *
 * <p><b>ADR-010 Stage 7 deprecation</b>:本适配器是同步 HTTP 桥实现,在 ADR-010 异步解耦完成后将被物理删除。已替代方案:配置 {@code
 * batch.trigger.async-launch.enabled=true} 走 trigger_outbox_event + Kafka 异步路径。本类计划在 ADR-010
 * 灰度全量切换稳定 1 个 minor 版本周期后删除 (见 {@code docs/runbook/trigger-async-launch-rollout.md} §6 Stage 7
 * 物理删除步骤)。
 *
 * <p>过渡期内若 {@code async-launch.enabled=false},仍由 {@code DefaultTriggerService} 通过 {@link
 * OrchestratorTriggerAdapter} 调用本类(deprecation log 在 service 层打印,避免每次构造 bean 都刷)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Deprecated(since = "ADR-010 Stage 6", forRemoval = true)
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
