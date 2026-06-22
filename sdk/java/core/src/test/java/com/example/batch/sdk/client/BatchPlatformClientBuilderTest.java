package com.example.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import org.junit.jupiter.api.Test;

/** {@link BatchPlatformClient.Builder} — handler 注册 + 重复 / 空 type fail-fast。 */
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
  void canRegisterMultiple() {
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg())
            .register(stub("type-a"))
            .register(stub("type-b"))
            .build();
    assertThat(client).isNotNull();
  }

  @Test
  void duplicateTypeRejected() {
    assertThatThrownBy(
            () -> BatchPlatformClient.builder(cfg()).register(stub("dup")).register(stub("dup")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate taskType");
  }

  @Test
  void blankTypeRejected() {
    assertThatThrownBy(() -> BatchPlatformClient.builder(cfg()).register(stub("")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank");
    assertThatThrownBy(() -> BatchPlatformClient.builder(cfg()).register(stub("  ")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void newIdempotencyKeyUnique() {
    String k1 = BatchPlatformClient.newIdempotencyKey();
    String k2 = BatchPlatformClient.newIdempotencyKey();
    assertThat(k1).startsWith("sdk-").isNotEqualTo(k2);
  }

  @Test
  void buildWithoutHandlerThenStartFails() {
    // build 允许空 handler(让 builder 灵活),只在 start 时校验
    BatchPlatformClient empty = BatchPlatformClient.builder(cfg()).build();
    assertThatThrownBy(empty::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least one SdkTaskHandler");
  }
}
