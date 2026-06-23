package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import io.github.pinpols.batch.sdk.task.SdkTaskTypeDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SDK Phase 3 M3.1:register 装配 taskTypes[] —— code 以 handler.taskType() 为权威 + 过滤无 descriptor 的
 * handler。
 */
class TaskTypeDescriptorAssemblyTest {

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://batch.example.com")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.tx.*")
        .kafkaGroupId("g")
        .maxConcurrentTasks(8)
        .build();
  }

  private static SdkTaskHandler handlerWithDescriptor(
      String type, SdkTaskTypeDescriptor descriptor) {
    return new SdkTaskHandler() {
      @Override
      public String taskType() {
        return type;
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok();
      }

      @Override
      public SdkTaskTypeDescriptor descriptor() {
        return descriptor;
      }
    };
  }

  private static SdkTaskHandler plainHandler(String type) {
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
  void defaultDescriptorIsNull() {
    assertThat(plainHandler("t").descriptor()).isNull();
  }

  @Test
  void collectsOnlyHandlersThatDeclareDescriptor() {
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg())
            .register(
                handlerWithDescriptor(
                    "tenant_tx_import",
                    SdkTaskTypeDescriptor.builder()
                        .displayName("导入")
                        .version("v1")
                        .defaults(Map.of("batchSize", 500))
                        .build()))
            .register(plainHandler("tenant_tx_noop"))
            .build();

    List<SdkTaskTypeDescriptor> collected = client.collectDescriptors();

    assertThat(collected).hasSize(1);
    assertThat(collected.get(0).code()).isEqualTo("tenant_tx_import");
    assertThat(collected.get(0).displayName()).isEqualTo("导入");
    assertThat(collected.get(0).defaults()).containsEntry("batchSize", 500);
  }

  @Test
  void taskTypeOverridesDescriptorCode() {
    // descriptor 里写错 / 漏填 code,装配时一律以 handler.taskType() 为权威,保证派单路由一致
    BatchPlatformClient client =
        BatchPlatformClient.builder(cfg())
            .register(
                handlerWithDescriptor(
                    "tenant_tx_export",
                    SdkTaskTypeDescriptor.builder().code("WRONG_CODE").version("v2").build()))
            .build();

    List<SdkTaskTypeDescriptor> collected = client.collectDescriptors();

    assertThat(collected).hasSize(1);
    assertThat(collected.get(0).code()).isEqualTo("tenant_tx_export");
    assertThat(collected.get(0).version()).isEqualTo("v2");
  }
}
