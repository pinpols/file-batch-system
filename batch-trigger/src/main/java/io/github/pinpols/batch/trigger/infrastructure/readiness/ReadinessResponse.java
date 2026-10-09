package io.github.pinpols.batch.trigger.infrastructure.readiness;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * orchestrator {@code /internal/readiness/job} 响应体(ADR-043)。
 *
 * @param ready 上游是否就绪
 * @param reason 未就绪原因码(就绪时为 null)
 * @param readyAt 上游 EFFECTIVE 结果的生效时刻
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReadinessResponse(boolean ready, String reason, Instant readyAt) {

  public ReadinessResponse(boolean ready, String reason) {
    this(ready, reason, null);
  }
}
