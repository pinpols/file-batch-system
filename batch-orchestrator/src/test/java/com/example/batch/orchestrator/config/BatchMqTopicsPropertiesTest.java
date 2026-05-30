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

    // ADR-029:TASK worker_type → 专属 batch.task.dispatch.task(大小写不敏感)
    assertThat(properties.resolveDispatchTopic("TASK")).isEqualTo("batch.task.dispatch.task");
    assertThat(properties.resolveDispatchTopic("task")).isEqualTo("batch.task.dispatch.task");
  }
}
