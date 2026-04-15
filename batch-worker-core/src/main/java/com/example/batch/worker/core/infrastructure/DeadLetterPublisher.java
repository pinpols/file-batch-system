package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.utils.JsonUtils;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** D-3: 死信队列发布器，将处理失败的消息转发到 {@link BatchTopics#TASK_DEAD_LETTER}， 防止毒丸消息阻塞主派发队列。运维可从 DLQ 查看并重放。 */
@Slf4j
@Component
public class DeadLetterPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;

  public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  /**
   * 将失败消息发布到死信 topic。
   *
   * <p>#4-3: 发送失败时抛出异常（而非静默吞噬），让调用方决定是否提交偏移量。
   */
  public void publish(
      String originalPayload, String sourceTopic, String workerType, String errorMessage) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("originalPayload", originalPayload);
    envelope.put("sourceTopic", sourceTopic);
    envelope.put("workerType", workerType);
    envelope.put("errorMessage", truncate(errorMessage, 2000));
    envelope.put("failedAt", Instant.now().toString());
    String value = JsonUtils.toJson(envelope);
    var future = kafkaTemplate.send(BatchTopics.TASK_DEAD_LETTER, value);
    if (future != null) {
      future.join();
    }
    log.info("published to DLQ: sourceTopic={}, workerType={}", sourceTopic, workerType);
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }
}
