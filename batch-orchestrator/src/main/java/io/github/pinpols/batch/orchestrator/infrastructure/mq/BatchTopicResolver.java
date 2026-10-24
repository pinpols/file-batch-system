package io.github.pinpols.batch.orchestrator.infrastructure.mq;

import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.BatchMqTopicsProperties;
import io.github.pinpols.batch.orchestrator.config.MqRoutingProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * P2-5: 派发 topic 路由器。在 {@link BatchMqTopicsProperties#resolveDispatchTopic(String)} 决定的 基 topic 上，按
 * {@link MqRoutingProperties} 配置追加 tenant / priority 后缀。
 *
 * <p>失败回退：当模式要求的字段（tenantId / priorityBand）缺失时，回退到基 topic（不分流）； 不让 routing 配置错误打断派发。
 */
@Component
@RequiredArgsConstructor
public class BatchTopicResolver {

  private final BatchMqTopicsProperties topicsProperties;
  private final MqRoutingProperties routingProperties;
  private final BatchKafkaTopicNamespace topicNamespace;

  /**
   * 解析派发目标 topic。
   *
   * @param workerType IMPORT / EXPORT / DISPATCH（决定 base topic）
   * @param dispatch task 派发消息（取 tenantId / priorityBand）；可为 null（fallback 到 base）
   * @return 解析后的 topic name；workerType 无效时返回 null（调用方走 fallback 路径）
   */
  public String resolve(String workerType, TaskDispatchMessage dispatch) {
    String base = resolveBaseTopic(workerType);
    if (base == null) {
      return null;
    }
    if (routingProperties.getMode() == MqRoutingProperties.Mode.SINGLE || dispatch == null) {
      return base;
    }
    return switch (routingProperties.getMode()) {
      case TENANT -> {
        if (!Texts.hasText(dispatch.tenantId())) {
          yield base;
        }
        String tenantId = dispatch.tenantId();
        String safeTenantId = safe(tenantId);
        yield base + "." + safeTenantId;
      }
      case PRIORITY -> {
        if (!Texts.hasText(dispatch.priorityBand())) {
          yield base;
        }
        String priorityBand = dispatch.priorityBand();
        String safePriorityBand = safe(priorityBand);
        String normalizedPriorityBand = safePriorityBand.toLowerCase(Locale.ROOT);
        yield base + "." + normalizedPriorityBand;
      }
      default -> base;
    };
  }

  public String resolveBaseTopic(String workerType) {
    String baseTopic = topicsProperties.resolveDispatchTopic(workerType);
    return EmptyChecks.isNull(baseTopic) ? null : topicNamespace.topic(baseTopic);
  }

  private static String safe(String s) {
    // Kafka topic 合法字符：[a-zA-Z0-9._-]；其他字符替换为 _ 防止订阅失败
    return s.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
