package com.example.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class BatchMqTopicsPropertiesTest {

  @Test
  void resolveDispatchTopic_returnsProcessTopicForProcessWorkerType() {
    BatchMqTopicsProperties properties = new BatchMqTopicsProperties();

    assertThat(properties.resolveDispatchTopic("PROCESS")).isEqualTo("batch.task.dispatch.process");
  }
}
