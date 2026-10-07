package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link BatchPlatformClient.Builder} — handler 注册 + 重复 / 空 type fail-fast。 */
@DisplayName("BatchPlatformClient 构造器 — handler 注册装配,重复或空白任务类型的快速失败")
class BatchPlatformClientBuilderTest {

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("tx-workers")
        .build();
  }

  private static SdkTaskHandler stub(String type) {
    return new SdkTaskHandler() {
      @Override
      public String taskType() {
        return type;
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok();
      }
    };
  }

  @Test
  @DisplayName("注册多个不同任务类型的 handler 后客户端可正常构造")
  void shouldRegisterAllHandlers_whenTypesDistinct() {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg())
        .register(stub("type-a"))
        .register(stub("type-b"))
        .build();
    assertThat(client).isNotNull();
  }

  @Test
  @DisplayName("重复注册同一任务类型时立即报错拒绝")
  void shouldRejectDuplicateTaskType_whenRegisteredTwice() {
    assertThatThrownBy(
            () -> BatchPlatformClient.builder(cfg()).register(stub("dup")).register(stub("dup")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate taskType");
  }

  @Test
  @DisplayName("任务类型为空串或纯空白时拒绝注册")
  void shouldRejectBlankTaskType_whenTypeIsBlank() {
    assertThatThrownBy(() -> BatchPlatformClient.builder(cfg()).register(stub("")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank");
    assertThatThrownBy(() -> BatchPlatformClient.builder(cfg()).register(stub("  ")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("两次生成的幂等键带固定前缀且互不相同")
  void shouldGenerateDistinctIdempotencyKey_whenCalledTwice() {
    String k1 = BatchPlatformClient.newIdempotencyKey();
    String k2 = BatchPlatformClient.newIdempotencyKey();
    assertThat(k1).startsWith("sdk-").isNotEqualTo(k2);
  }

  @Test
  @DisplayName("未注册任何 handler 时构造放过,启动阶段才报错")
  void shouldFailOnStart_whenNoHandlerRegistered() {
    // build 允许空 handler(让 builder 灵活),只在 start 时校验
    BatchPlatformClient empty = BatchPlatformClient.builder(cfg()).build();
    assertThatThrownBy(empty::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least one SdkTaskHandler");
  }
}
