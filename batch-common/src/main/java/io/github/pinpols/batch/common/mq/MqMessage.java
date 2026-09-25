package io.github.pinpols.batch.common.mq;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 跨模块消息发布请求，不暴露 KafkaTemplate / ProducerRecord 等具体客户端类型。 */
public record MqMessage(String topic, String key, String payload, Map<String, String> headers) {

  public MqMessage {
    headers = EmptyChecks.isNull(headers)
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
  }

  public static MqMessage of(String topic, String key, String payload) {
    return new MqMessage(topic, key, payload, Map.of());
  }
}
