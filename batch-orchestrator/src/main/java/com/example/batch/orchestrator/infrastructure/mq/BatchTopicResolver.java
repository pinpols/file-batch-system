package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.config.MqRoutingProperties;
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

  /**
   * 解析派发目标 topic。
   *
   * @param workerType IMPORT / EXPORT / DISPATCH（决定 base topic）
   * @param dispatch task 派发消息（取 tenantId / priorityBand）；可为 null（fallback 到 base）
   * @return 解析后的 topic name；workerType 无效时返回 null（调用方走 fallback 路径）
   */
  public String resolve(String workerType, TaskDispatchMessage dispatch) {
    String base = topicsProperties.resolveDispatchTopic(workerType);
    if (base == null) {
      return null;
    }
    if (routingProperties.getMode() == MqRoutingProperties.Mode.SINGLE || dispatch == null) {
      return base;
    }
    return switch (routingProperties.getMode()) {
      case TENANT ->
          Texts.hasText(dispatch.tenantId()) ? base + "." + safe(dispatch.tenantId()) : base;
      case PRIORITY ->
          Texts.hasText(dispatch.priorityBand())
              ? base + "." + safe(dispatch.priorityBand()).toLowerCase(Locale.ROOT)
              : base;
      default -> base;
    };
  }

  private static String safe(String s) {
    // Kafka topic 合法字符：[a-zA-Z0-9._-]；其他字符替换为 _ 防止订阅失败
    return s.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
