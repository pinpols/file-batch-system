package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.internal.SdkJsonMapperFactory;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 使用真实 Kafka 验证认领等待期间消费组的存活和后续位点。 */
@EnabledIfEnvironmentVariable(named = "BATCH_SDK_KAFKA_BOOTSTRAP", matches = ".+")
@DisplayName("Kafka 消费组等待认领期间保持心跳")
class KafkaTaskConsumerHeartbeatIntegrationTest {

  @Test
  @DisplayName("慢认领超过一个会话周期后成员不变，随后两条任务完成并提交")
  void shouldKeepGroupMemberAndCommitAfterSlowClaim() throws Exception {
    String bootstrap = System.getenv("BATCH_SDK_KAFKA_BOOTSTRAP");
    String suffix = UUID.randomUUID().toString();
    String topic = "sdk-java-heartbeat-" + suffix;
    String group = "sdk-java-heartbeat-" + suffix;
    CountDownLatch claimEntered = new CountDownLatch(1);
    CountDownLatch releaseClaim = new CountDownLatch(1);
    CountDownLatch handled = new CountDownLatch(2);
    CountDownLatch reported = new CountDownLatch(2);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/tasks", exchange -> {
      try {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/claim")) {
          if (path.contains("/70001/")) {
            claimEntered.countDown();
            if (!releaseClaim.await(30, TimeUnit.SECONDS)) {
              respond(exchange, 503, "");
              return;
            }
          }
          respond(
              exchange, 200, "{\"partitionInvocationId\":\"test-invocation\",\"payload\":\"{}\"}");
        } else if (path.endsWith("/renew")) {
          respond(exchange, 200, "{\"cancelRequested\":false}");
        } else if (path.endsWith("/report")) {
          reported.countDown();
          respond(exchange, 204, "");
        } else {
          respond(exchange, 404, "");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        exchange.close();
      }
    });
    server.start();

    BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
        .tenantId("heartbeat-tenant")
        .workerCode("heartbeat-worker")
        .kafkaBootstrap(bootstrap)
        .kafkaTopicPattern("^" + topic + "$")
        .kafkaGroupId(group)
        .kafkaPollInterval(Duration.ofMillis(100))
        .httpTimeout(Duration.ofSeconds(35))
        .maxConcurrentTasks(2)
        .build();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "heartbeat";
      }

      @Override
      public boolean isSafeToReplay(SdkTaskContext context) {
        return true;
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        handled.countDown();
        return SdkTaskResult.ok();
      }
    };
    Properties adminProperties = new Properties();
    adminProperties.put("bootstrap.servers", bootstrap);
    adminProperties.put("request.timeout.ms", "10000");
    Properties producerProperties = new Properties();
    producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    producerProperties.put(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producerProperties.put(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    Properties consumerProperties = KafkaTaskConsumer.consumerProperties(config);
    // 缩短会话周期使测试有界；不改变产品默认配置。
    consumerProperties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6000);
    consumerProperties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1000);
    consumerProperties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30000);

    try (Admin admin = Admin.create(adminProperties);
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProperties)) {
      admin.createTopics(Set.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
      TaskDispatcher dispatcher =
          new TaskDispatcher(config, Map.of("heartbeat", handler), new PlatformHttpClient(config));
      KafkaConsumer<String, byte[]> kafkaClient = new KafkaConsumer<>(consumerProperties);
      KafkaTaskConsumer consumer =
          new KafkaTaskConsumer(config, dispatcher, kafkaClient, SdkJsonMapperFactory.create());
      Thread pollThread = new Thread(consumer, "sdk-heartbeat-integration");
      try {
        pollThread.start();
        AtomicReference<String> originalMember = new AtomicReference<>();
        await(
            () -> {
              try {
                ConsumerGroupDescription description = groupDescription(admin, group);
                if (description.groupState() != GroupState.STABLE
                    || description.members().size() != 1) return false;
                originalMember.set(description.members().iterator().next().consumerId());
                return true;
              } catch (Exception ignored) {
                return false;
              }
            },
            Duration.ofSeconds(30));
        for (long taskId : new long[] {70001L, 70002L}) {
          String payload = """
              {"schemaVersion":"v2","taskId":%d,"tenantId":"heartbeat-tenant",
               "jobCode":"heartbeat-job","workerType":"heartbeat","taskInstanceId":"instance-%d"}
              """.formatted(taskId, taskId);
          producer
              .send(new ProducerRecord<>(topic, 0, null, payload.getBytes(StandardCharsets.UTF_8)))
              .get(20, TimeUnit.SECONDS);
        }
        assertThat(claimEntered.await(20, TimeUnit.SECONDS))
            .as("首个 CLAIM 已进入 HTTP 服务")
            .isTrue();
        long until = System.nanoTime() + Duration.ofSeconds(9).toNanos();
        while (System.nanoTime() < until) {
          ConsumerGroupDescription description = groupDescription(admin, group);
          assertThat(description.groupState()).isEqualTo(GroupState.STABLE);
          assertThat(description.members()).hasSize(1);
          assertThat(description.members().iterator().next().consumerId())
              .isEqualTo(originalMember.get());
          Thread.sleep(250);
        }
        Map<TopicPartition, OffsetAndMetadata> blocked = admin
            .listConsumerGroupOffsets(group)
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS);
        OffsetAndMetadata pending = blocked.get(new TopicPartition(topic, 0));
        assertThat(pending == null || pending.offset() == 0L).as("认领未结束不得提交首条").isTrue();
        assertThat(handled.getCount()).isEqualTo(2);

        releaseClaim.countDown();
        assertThat(handled.await(30, TimeUnit.SECONDS)).as("两条业务处理完成").isTrue();
        assertThat(reported.await(30, TimeUnit.SECONDS)).as("两条结果已回报测试服务").isTrue();
        await(
            () -> {
              try {
                OffsetAndMetadata offset = admin
                    .listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS)
                    .get(new TopicPartition(topic, 0));
                return offset != null && offset.offset() == 2L;
              } catch (Exception ignored) {
                return false;
              }
            },
            Duration.ofSeconds(30));
        assertThat(groupDescription(admin, group).members().iterator().next().consumerId())
            .isEqualTo(originalMember.get());
        assertThat(consumer.hasCrashed()).isFalse();
      } finally {
        releaseClaim.countDown();
        consumer.close(Duration.ofSeconds(10));
        dispatcher.stop(Duration.ofSeconds(10));
      }
    } finally {
      server.stop(0);
    }
  }

  private static ConsumerGroupDescription groupDescription(Admin admin, String group)
      throws Exception {
    return admin
        .describeConsumerGroups(Set.of(group))
        .describedGroups()
        .get(group)
        .get(5, TimeUnit.SECONDS);
  }

  private static void await(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(200);
    }
    throw new AssertionError("等待 Kafka 状态超时");
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
