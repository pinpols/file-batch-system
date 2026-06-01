package com.example.batch.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.batch.sdk.client.BatchPlatformClient;
import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.idempotent.SdkIdempotencyStore;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class BatchWorkerSdkAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(BatchWorkerSdkAutoConfiguration.class));

  @Test
  void bindsPropertiesToBatchPlatformClientConfig() {
    contextRunner
        .withPropertyValues(
            "batch.worker-sdk.enabled=false",
            "batch.worker-sdk.base-url=https://batch.example.com",
            "batch.worker-sdk.api-key=secret",
            "batch.worker-sdk.tenant-id=tenant-a",
            "batch.worker-sdk.worker-code=worker-a",
            "batch.worker-sdk.kafka-bootstrap=kafka:9092",
            "batch.worker-sdk.kafka-topic-pattern=batch.task.dispatch.*.tenant-a",
            "batch.worker-sdk.kafka-group-id=tenant-a-workers",
            "batch.worker-sdk.build-id=git-abc123",
            "batch.worker-sdk.http-timeout=7s",
            "batch.worker-sdk.heartbeat-interval=11s",
            "batch.worker-sdk.max-concurrent-tasks=9",
            "batch.worker-sdk.kafka-poll-interval=150ms",
            "batch.worker-sdk.lease-renew-interval=45s",
            "batch.worker-sdk.claim-max-5xx-retries=5",
            "batch.worker-sdk.claim-retry-base-delay=350ms",
            "batch.worker-sdk.client-error-fail-fast-threshold=6",
            "batch.worker-sdk.kafka-security-protocol=SASL_SSL",
            "batch.worker-sdk.kafka-sasl-mechanism=SCRAM-SHA-512",
            "batch.worker-sdk.kafka-sasl-jaas-config=jaas")
        .run(
            context -> {
              assertThat(context).hasSingleBean(BatchPlatformClientConfig.class);
              BatchPlatformClientConfig config = context.getBean(BatchPlatformClientConfig.class);
              assertThat(config.getBaseUrl()).isEqualTo("https://batch.example.com");
              assertThat(config.getApiKey()).isEqualTo("secret");
              assertThat(config.getTenantId()).isEqualTo("tenant-a");
              assertThat(config.getWorkerCode()).isEqualTo("worker-a");
              assertThat(config.getKafkaBootstrap()).isEqualTo("kafka:9092");
              assertThat(config.getKafkaTopicPattern()).isEqualTo("batch.task.dispatch.*.tenant-a");
              assertThat(config.getKafkaGroupId()).isEqualTo("tenant-a-workers");
              assertThat(config.getBuildId()).isEqualTo("git-abc123");
              assertThat(config.getHttpTimeout()).isEqualTo(Duration.ofSeconds(7));
              assertThat(config.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(11));
              assertThat(config.getMaxConcurrentTasks()).isEqualTo(9);
              assertThat(config.getKafkaPollInterval()).isEqualTo(Duration.ofMillis(150));
              assertThat(config.getLeaseRenewInterval()).isEqualTo(Duration.ofSeconds(45));
              assertThat(config.getClaimMax5xxRetries()).isEqualTo(5);
              assertThat(config.getClaimRetryBaseDelay()).isEqualTo(Duration.ofMillis(350));
              assertThat(config.getClientErrorFailFastThreshold()).isEqualTo(6);
              assertThat(config.getKafkaSecurityProtocol()).isEqualTo("SASL_SSL");
              assertThat(config.getKafkaSaslMechanism()).isEqualTo("SCRAM-SHA-512");
              assertThat(config.getKafkaSaslJaasConfig()).isEqualTo("jaas");
            });
  }

  @Test
  void backsOffWhenUserProvidesBatchPlatformClientConfig() {
    contextRunner
        .withUserConfiguration(CustomConfigConfiguration.class)
        .withPropertyValues(
            "batch.worker-sdk.enabled=false",
            "batch.worker-sdk.base-url=https://ignored.example.com",
            "batch.worker-sdk.tenant-id=ignored",
            "batch.worker-sdk.worker-code=ignored",
            "batch.worker-sdk.kafka-bootstrap=ignored:9092",
            "batch.worker-sdk.kafka-topic-pattern=ignored",
            "batch.worker-sdk.kafka-group-id=ignored")
        .run(
            context -> {
              assertThat(context).hasSingleBean(BatchPlatformClientConfig.class);
              assertThat(context.getBean(BatchPlatformClientConfig.class).getTenantId())
                  .isEqualTo("custom-tenant");
            });
  }

  @Test
  void registersAllSdkTaskHandlerBeans() {
    contextRunner
        .withUserConfiguration(HandlerConfiguration.class)
        .withPropertyValues(defaultPropertiesWithLifecycleDisabled())
        .run(
            context -> {
              BatchPlatformClient client = context.getBean(BatchPlatformClient.class);
              assertThat(registeredHandlers(client)).containsOnlyKeys("import", "export");
            });
  }

  @Test
  void injectsIdempotencyStoreWhenPresent() {
    contextRunner
        .withUserConfiguration(HandlerAndIdempotencyConfiguration.class)
        .withPropertyValues(defaultPropertiesWithLifecycleDisabled())
        .run(
            context -> {
              BatchPlatformClient client = context.getBean(BatchPlatformClient.class);
              assertThat(idempotencyStore(client))
                  .isSameAs(context.getBean(SdkIdempotencyStore.class));
            });
  }

  @Test
  void skipsIdempotencyStoreWhenMissing() {
    contextRunner
        .withUserConfiguration(HandlerConfiguration.class)
        .withPropertyValues(defaultPropertiesWithLifecycleDisabled())
        .run(
            context -> {
              BatchPlatformClient client = context.getBean(BatchPlatformClient.class);
              assertThat(idempotencyStore(client)).isNull();
            });
  }

  @Test
  void disabledPropertyKeepsBeansButDisablesLifecycle() {
    contextRunner
        .withUserConfiguration(HandlerConfiguration.class)
        .withPropertyValues(defaultPropertiesWithLifecycleDisabled())
        .run(
            context -> {
              assertThat(context).hasSingleBean(BatchPlatformClientConfig.class);
              assertThat(context).hasSingleBean(BatchPlatformClient.class);
              assertThat(context).doesNotHaveBean(BatchPlatformClientLifecycle.class);
            });
  }

  @Test
  void lifecycleStartsAndStopsClientWhenEnabled() {
    BatchPlatformClient client = mock(BatchPlatformClient.class);
    contextRunner
        .withBean(BatchPlatformClient.class, () -> client)
        .withUserConfiguration(CustomConfigConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(BatchPlatformClientLifecycle.class);
              BatchPlatformClientLifecycle lifecycle =
                  context.getBean(BatchPlatformClientLifecycle.class);
              assertThat(lifecycle.getPhase()).isEqualTo(BatchPlatformClientLifecycle.PHASE);
              assertThat(lifecycle.isAutoStartup()).isTrue();
              assertThat(lifecycle.isRunning()).isTrue();
              verify(client).start();
            });
    verify(client).stop();
  }

  private static String[] defaultPropertiesWithLifecycleDisabled() {
    return new String[] {
      "batch.worker-sdk.enabled=false",
      "batch.worker-sdk.base-url=https://batch.example.com",
      "batch.worker-sdk.tenant-id=tenant-a",
      "batch.worker-sdk.worker-code=worker-a",
      "batch.worker-sdk.kafka-bootstrap=kafka:9092",
      "batch.worker-sdk.kafka-topic-pattern=batch.task.dispatch.*.tenant-a",
      "batch.worker-sdk.kafka-group-id=tenant-a-workers"
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, SdkTaskHandler> registeredHandlers(BatchPlatformClient client) {
    return (Map<String, SdkTaskHandler>) fieldValue(client, "handlers");
  }

  private static SdkIdempotencyStore idempotencyStore(BatchPlatformClient client) {
    return (SdkIdempotencyStore) fieldValue(client, "idempotencyStore");
  }

  private static Object fieldValue(Object target, String name) {
    try {
      Field field = BatchPlatformClient.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private static BatchPlatformClientConfig customConfig() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("https://custom.example.com")
        .tenantId("custom-tenant")
        .workerCode("custom-worker")
        .kafkaBootstrap("custom-kafka:9092")
        .kafkaTopicPattern("batch.task.dispatch.*.custom-tenant")
        .kafkaGroupId("custom-workers")
        .build();
  }

  private static SdkTaskHandler handler(String taskType) {
    return new SdkTaskHandler() {
      @Override
      public String taskType() {
        return taskType;
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        return SdkTaskResult.ok();
      }
    };
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomConfigConfiguration {
    @Bean
    BatchPlatformClientConfig customBatchPlatformClientConfig() {
      return customConfig();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class HandlerConfiguration {
    @Bean
    SdkTaskHandler importHandler() {
      return handler("import");
    }

    @Bean
    SdkTaskHandler exportHandler() {
      return handler("export");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class HandlerAndIdempotencyConfiguration extends HandlerConfiguration {
    @Bean
    SdkIdempotencyStore idempotencyStore() {
      return new SdkIdempotencyStore.NoOp();
    }
  }
}
