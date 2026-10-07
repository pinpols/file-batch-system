package io.github.pinpols.batch.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import io.github.pinpols.batch.sdk.task.SdkTaskTypeDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SDK Phase 3 M3.1:register 装配 taskTypes[] —— code 以 handler.taskType() 为权威 + 过滤无 descriptor 的
 * handler。
 */
@DisplayName("BatchPlatformClient 任务类型描述符装配 — 采集范围与编码优先级")
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
  @DisplayName("未声明描述符的 handler 取描述符为空")
  void shouldReturnNullDescriptor_whenHandlerDoesNotDeclare() {
    assertThat(plainHandler("t").descriptor()).isNull();
  }

  @Test
  @DisplayName("装配时只采集声明了描述符的 handler,其余忽略")
  void shouldCollectOnlyHandlersWithDescriptor_whenBuilding() {
    BatchPlatformClient client = BatchPlatformClient.builder(cfg())
        .register(handlerWithDescriptor(
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
  @DisplayName("描述符编码与 handler 声明冲突时以 handler 为准,保证派单路由一致")
  void shouldPreferHandlerTaskType_whenDescriptorCodeConflicts() {
    // descriptor 里写错 / 漏填 code,装配时一律以 handler.taskType() 为权威,保证派单路由一致
    BatchPlatformClient client = BatchPlatformClient.builder(cfg())
        .register(handlerWithDescriptor(
            "tenant_tx_export",
            SdkTaskTypeDescriptor.builder().code("WRONG_CODE").version("v2").build()))
        .build();

    List<SdkTaskTypeDescriptor> collected = client.collectDescriptors();

    assertThat(collected).hasSize(1);
    assertThat(collected.get(0).code()).isEqualTo("tenant_tx_export");
    assertThat(collected.get(0).version()).isEqualTo("v2");
  }
}
