package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("消息生产者配置构建:集群地址、序列化器、确认与重试默认值,以及自定义属性的透传与可变覆盖")
class BatchKafkaProducerSupportTest {

  @Test
  @DisplayName("构建生产者配置时写入集群地址、字符串序列化器、确认与重试设置,并原样透传全部自定义属性")
  void shouldBuildConfigWithBootstrapSerializersAcksRetriesAndPassthroughProperties() {
    BatchKafkaProducerProperties props = new BatchKafkaProducerProperties();
    props.setAcks("all");
    props.setRetries(5);
    Map<String, String> extra = new LinkedHashMap<>();
    extra.put("enable.idempotence", "true");
    extra.put("delivery.timeout.ms", "30000");
    extra.put("buffer.memory", "67108864");
    extra.put("max.block.ms", "5000");
    props.setProperties(extra);

    Map<String, Object> cfg = BatchKafkaProducerSupport.stringProducerConfig("broker:9092", props);

    assertThat(cfg).containsEntry("bootstrap.servers", "broker:9092");
    assertThat(cfg)
        .containsEntry("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        .containsEntry(
            "value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    assertThat(cfg).containsEntry("acks", "all").containsEntry("retries", 5);
    // properties.* 原样透传(三处共用,消除 buffer.memory / max.block.ms 等漂移)
    assertThat(cfg)
        .containsEntry("enable.idempotence", "true")
        .containsEntry("delivery.timeout.ms", "30000")
        .containsEntry("buffer.memory", "67108864")
        .containsEntry("max.block.ms", "5000");
  }

  @Test
  @DisplayName("确认配置为空白时不写入该项,且返回的配置集合可继续修改以覆盖超时设置")
  void shouldOmitAcksWhenBlankAndReturnMutableMapForOverride() {
    BatchKafkaProducerProperties props = new BatchKafkaProducerProperties();
    props.setAcks("  ");

    Map<String, Object> cfg = BatchKafkaProducerSupport.stringProducerConfig("b:9092", props);

    assertThat(cfg).doesNotContainKey("acks");
    // 返回可变 Map:调用方(如 trigger)可覆盖 max.block.ms
    cfg.put("max.block.ms", "8000");
    assertThat(cfg).containsEntry("max.block.ms", "8000");
  }
}
