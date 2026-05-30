package com.example.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class BatchMqTopicsPropertiesTest {

  @Test
  void resolveDispatchTopic_returnsProcessTopicForProcessWorkerType() {
    BatchMqTopicsProperties properties = new BatchMqTopicsProperties();

    assertThat(properties.resolveDispatchTopic("PROCESS")).isEqualTo("batch.task.dispatch.process");
  }

  @Test
  void resolveDispatchTopic_returnsTaskTopicForTaskWorkerType() {
    BatchMqTopicsProperties properties = new BatchMqTopicsProperties();

    // ADR-029:SPI worker_type → 专属 batch.task.dispatch.spi(大小写不敏感)
    assertThat(properties.resolveDispatchTopic("SPI")).isEqualTo("batch.task.dispatch.spi");
    assertThat(properties.resolveDispatchTopic("spi")).isEqualTo("batch.task.dispatch.spi");
  }
}
