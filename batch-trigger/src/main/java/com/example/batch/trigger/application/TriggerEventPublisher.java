package com.example.batch.trigger.application;

import com.example.batch.common.dto.LaunchEnvelope;

/**
 * ADR-010: trigger 端事件发布抽象。
 *
 * <p>当前唯一目标 = Kafka topic {@code batch.trigger.launch.v1}(由 {@code KafkaTriggerEventPublisher}
 * 实现);抽象为接口的目的:
 *
 * <ul>
 *   <li>{@link TriggerOutboxRelay} 与 Kafka 客户端解耦,relay 单测无需 mock 整套 KafkaTemplate
 *   <li>Stage 4 加 Kafka 实现前,relay 可以先编译 + 单测通过(零 Kafka 依赖)
 *   <li>未来扩展(如 Pulsar / 走 HTTP 桥过渡)只需新实现,relay 不变
 * </ul>
 *
 * <p>ADR-010 固化路径，实现 bean 无条件 @Component（2026-05-02 同步 HTTP 路径已删除）。
 */
public interface TriggerEventPublisher {

  /**
   * 阻塞式同步发送(返回时表示 broker 已 ack 或已失败)。relay 在锁内逐条调用,不依赖 Future。
   *
   * @param topic Kafka topic name(envelope.envelopeVersion 决定的版本化 topic)
   * @param messageKey Kafka 分区 key,通常 {@code tenantId:requestId}
   * @param envelope 完整事件载荷
   * @param traceId 链路 trace,作为 message header 写入,便于 Kafka 端日志聚合
   * @return 发送结果(success + 失败时的简短 message,长度内 2KB 截断)
   */
  PublishResult publish(String topic, String messageKey, LaunchEnvelope envelope, String traceId);

  /** 发送结果。 */
  record PublishResult(boolean success, String errorMessage) {

    public static PublishResult ok() {
      return new PublishResult(true, null);
    }

    public static PublishResult fail(String errorMessage) {
      return new PublishResult(false, truncate(errorMessage));
    }

    private static String truncate(String message) {
      if (message == null) {
        return null;
      }
      return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
  }
}
