package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("消息主题配置:按工作节点类型解析派发主题,区分进程节点与原子节点且大小写不敏感")
class BatchMqTopicsPropertiesTest {

  @Test
  @DisplayName("进程类型节点解析到进程专属派发主题")
  void resolveDispatchTopic_returnsProcessTopicForProcessWorkerType() {
    BatchMqTopicsProperties properties = new BatchMqTopicsProperties();

    assertThat(properties.resolveDispatchTopic("PROCESS")).isEqualTo("batch.task.dispatch.process");
  }

  @Test
  @DisplayName("原子类型节点解析到原子专属派发主题,且类型标识大小写不敏感")
  void resolveDispatchTopic_returnsTaskTopicForTaskWorkerType() {
    BatchMqTopicsProperties properties = new BatchMqTopicsProperties();

    // ADR-029:ATOMIC worker_type → 专属 batch.task.dispatch.atomic(大小写不敏感)
    assertThat(properties.resolveDispatchTopic("ATOMIC")).isEqualTo("batch.task.dispatch.atomic");
    assertThat(properties.resolveDispatchTopic("atomic")).isEqualTo("batch.task.dispatch.atomic");
  }
}
