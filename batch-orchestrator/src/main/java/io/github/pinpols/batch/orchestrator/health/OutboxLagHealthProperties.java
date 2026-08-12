package io.github.pinpols.batch.orchestrator.health;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@ConfigurationProperties(prefix = "batch.orchestrator.health.outbox")
@Validated
public class OutboxLagHealthProperties {

  /** NEW + FAILED 状态事件总数达到该阈值,健康端点 DOWN。 */
  @Min(1)
  private long backlogThreshold = 1000;

  /** PUBLISHING 状态卡住超过该秒数视为 stale,任何一条都触发 DOWN。 */
  @Min(10)
  private long stalePublishingSeconds = 300;

  private boolean enabled = true;
}
