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

  /** 将失败消息发布到死信 topic。 */
  public void publish(
      String originalPayload, String sourceTopic, String workerType, String errorMessage) {
    try {
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("originalPayload", originalPayload);
      envelope.put("sourceTopic", sourceTopic);
      envelope.put("workerType", workerType);
      envelope.put("errorMessage", truncate(errorMessage, 2000));
      envelope.put("failedAt", Instant.now().toString());
      String value = JsonUtils.toJson(envelope);
      kafkaTemplate.send(BatchTopics.TASK_DEAD_LETTER, value);
      log.info("published to DLQ: sourceTopic={}, workerType={}", sourceTopic, workerType);
    } catch (Exception ex) {
      // DLQ 发布失败不能掩盖原始错误，仅记录日志后继续
      log.error(
          "failed to publish to DLQ: sourceTopic={}, workerType={}, dlqError={}",
          sourceTopic,
          workerType,
          ex.getMessage(),
          ex);
    }
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }
}
