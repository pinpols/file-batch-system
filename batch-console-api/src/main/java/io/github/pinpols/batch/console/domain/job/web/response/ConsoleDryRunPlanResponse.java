package io.github.pinpols.batch.console.domain.job.web.response;

import java.util.List;
import java.util.Map;

/**
 * ADR-026 演练计划响应（console 透传 orchestrator {@code DryRunPlanResult} 的 JSON 投影）。
 *
 * <p>{@code summary} 为真动态负载（counts / scheduledJobs / estimatedPartitions 等按 level 与命中规则变化的键集）， 保留
 * {@code Map<String,Object>} + OpenAPI {@code additionalProperties}，不强行 schema 化。
 */
public record ConsoleDryRunPlanResponse(
    String level,
    boolean success,
    List<ConsoleDryRunFindingResponse> findings,
    Map<String, Object> summary) {

  /** 单条 finding；{@code detail} 为任意结构的附加上下文，保持 Object 透传。 */
  public record ConsoleDryRunFindingResponse(
      String code, String severity, String scope, String message, Object detail) {}
}
