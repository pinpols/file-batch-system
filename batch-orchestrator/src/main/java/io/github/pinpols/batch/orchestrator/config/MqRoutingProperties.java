package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P2-5: Kafka topic 分流策略（{@code batch.mq.routing}）。
 *
 * <ul>
 *   <li>{@code single}（默认）：所有租户 / 优先级共享同一组 base topic，行为同历史
 *   <li>{@code tenant}：基 topic 后追加 {@code .<tenantId>}，按租户分流避免大租户挤占
 *   <li>{@code priority}：基 topic 后追加 {@code .<priorityBand>}（HIGH / NORMAL / LOW），高优 topic 独立
 *       consumer group 防止低优堆积阻塞高优
 * </ul>
 *
 * <p>消费侧需要订阅对应 topic patterns（如 {@code task.dispatch.import.*}）才能接到分流后的消息； worker 配置同步调整。
 */
@Data
@ConfigurationProperties(prefix = "batch.mq.routing")
public class MqRoutingProperties {

  public enum Mode {
    SINGLE,
    TENANT,
    PRIORITY
  }

  private Mode mode = Mode.SINGLE;
}
