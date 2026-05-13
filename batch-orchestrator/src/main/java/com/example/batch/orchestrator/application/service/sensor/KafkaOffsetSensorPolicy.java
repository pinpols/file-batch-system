package com.example.batch.orchestrator.application.service.sensor;

import com.example.batch.common.enums.SensorType;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.config.SensorProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * KAFKA_OFFSET sensor：等待指定 topic-partition 的 endOffset &gt;= minOffset。
 *
 * <p>sensor_spec：
 *
 * <pre>{@code
 * {
 *   "topic":     "upstream.settle.v1",  // 必填
 *   "partition": 0,                     // 必填
 *   "minOffset": 123456                 // 必填
 * }
 * }</pre>
 *
 * <p>output：{@code currentOffset / topicPartition}。
 *
 * <p>不消费消息：使用 AdminClient.listOffsets(LATEST) 仅读 LEO（log-end-offset），无 group/commit 副作用。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "batch.sensor.kafka-offset", name = "enabled", havingValue = "true")
@ConditionalOnBean(KafkaAdmin.class)
public class KafkaOffsetSensorPolicy implements SensorPolicy {

  private final KafkaAdmin kafkaAdmin;
  private final SensorProperties props;

  public KafkaOffsetSensorPolicy(KafkaAdmin kafkaAdmin, SensorProperties props) {
    this.kafkaAdmin = kafkaAdmin;
    this.props = props;
  }

  @Override
  public SensorType type() {
    return SensorType.KAFKA_OFFSET;
  }

  @Override
  public SensorProbeResult probe(SensorContext ctx) {
    Map<String, Object> spec = ctx.sensorSpec();
    String topic = SensorSpecs.string(spec, "topic");
    Integer partition = SensorSpecs.intValue(spec, "partition");
    Long minOffset = SensorSpecs.longValue(spec, "minOffset");
    if (!Texts.hasText(topic) || partition == null || minOffset == null) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid",
          List.of("KAFKA_OFFSET", "topic/partition/minOffset required"));
    }

    TopicPartition tp = new TopicPartition(topic, partition);
    long timeoutMs = props.getKafkaAdminTimeout().toMillis();

    try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
      ListOffsetsResult result = client.listOffsets(Map.of(tp, OffsetSpec.latest()));
      long endOffset = result.partitionResult(tp).get(timeoutMs, TimeUnit.MILLISECONDS).offset();
      if (endOffset >= minOffset) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("currentOffset", endOffset);
        output.put("topicPartition", topic + "-" + partition);
        return SensorProbeResult.matched(output);
      }
      return SensorProbeResult.notYet();
    } catch (TimeoutException e) {
      log.warn("KAFKA_OFFSET admin timeout topic={} partition={}", topic, partition);
      return SensorProbeResult.error(
          "error.workflow.sensor_probe_failed", List.of("KAFKA_OFFSET", "admin timeout"));
    } catch (ExecutionException | InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
          "KAFKA_OFFSET probe error topic={} partition={} err={}",
          topic,
          partition,
          e.getMessage());
      return SensorProbeResult.error(
          "error.workflow.sensor_probe_failed", List.of("KAFKA_OFFSET", e.getMessage()));
    } catch (Exception e) {
      log.warn("KAFKA_OFFSET unexpected error topic={} err={}", topic, e.toString());
      return SensorProbeResult.error(
          "error.workflow.sensor_probe_failed", List.of("KAFKA_OFFSET", e.getMessage()));
    }
  }
}
