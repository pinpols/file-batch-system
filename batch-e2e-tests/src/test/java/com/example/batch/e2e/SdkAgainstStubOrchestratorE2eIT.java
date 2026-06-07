package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.client.BatchPlatformClient;
import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.example.batch.sdk.testkit.RecordedReport;
import com.example.batch.sdk.testkit.TaskDispatchMessageBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * R3-6 first POC — SDK↔BE 真端到端集成测.
 *
 * <p>Round-1 TOP-9 启动:之前 SDK 只有契约测 + testkit 自测(EmbeddedKafka 内嵌 broker), 缺"真 Kafka 客户端协议 + 真 HTTP
 * 协议"端到端 IT。本 IT 起 1 个真 testcontainer Kafka + 1 个 JDK {@link HttpServer} stub orchestrator(录制
 * register/claim/report,全部 200),指 SDK 到这两端, 派单 → SDK consumer 收到 → handler 跑 → REPORT 回到 stub 录制 →
 * 断言 success/outputs/claim。
 *
 * <p><b>POC 范围(已诚实降级):</b>本 IT 用 HTTP stub 模拟 orchestrator 的 {@code /internal/*},而<b>不起</b> 真
 * Spring Boot orchestrator context。起真 orchestrator 需 PG/Kafka/MinIO/Redis 5 容器 + 5 min Spring 装配,超出
 * < 60s POC 预算且重复 {@code *PipelineE2eIT} 已覆盖的"DB→outbox→worker"链路。本 IT 价值是 <b>真 Kafka
 * producer/consumer wire + 真 SDK HTTP client</b>对 orchestrator 协议契约的端到端断言。 起真 orchestrator Spring
 * boot + 经 LaunchService → outbox 推 SDK 的全栈版本留作 R3-6 follow-up。
 *
 * <p>预算:Kafka container 启动 ~10s + SDK rebalance + dispatch + report ≈ 30s 内跑完。
 *
 * <p><b>测试基类例外(必须裸协议层)</b>:本 IT 故意不继承 {@code AbstractIntegrationTest}。本 POC 只验 SDK→BE 的 wire 协议契约(真
 * Kafka producer/consumer + 真 HTTP client),需要的隔离正是「**不要** 平台全栈」——基类拽起 PG/Redis/MinIO/Spring
 * Boot,SDK 拿到这些会改变 fail-fast 路径,反而测不出 协议层缺陷。仅起独立 Kafka container + HTTP stub 是必要的协议层隔离,与「必须裸
 * JDBC」同理。
 */
@Tag("smoke")
@Tag("e2e")
@Testcontainers
@DisplayName("SDK↔stub orchestrator 真 Kafka 端到端 POC")
class SdkAgainstStubOrchestratorE2eIT {

  private static final String TOPIC = "batch.task.dispatch.r3-6-poc";
  private static final String TOPIC_PATTERN = "batch\\.task\\.dispatch\\..*";
  private static final String TENANT = "r3-6-tenant";
  private static final String WORKER = "r3-6-worker";

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

  private static StubOrchestrator stub;
  private static Producer<String, byte[]> producer;

  @BeforeAll
  static void startStubAndProducer() throws Exception {
    stub = StubOrchestrator.start();
    // 预创建 topic — 否则 SDK consumer 的 pattern subscribe 要等 metadata.max.age.ms(默认 5min)
    // 刷新才能发现新 topic,30s 等不到。
    Properties adminProps = new Properties();
    adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (Admin admin = Admin.create(adminProps)) {
      admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
    }
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    producer = new KafkaProducer<>(props);
  }

  @AfterAll
  static void stopAll() {
    if (producer != null) {
      producer.close(Duration.ofSeconds(2));
    }
    if (stub != null) {
      stub.close();
    }
  }

  @Test
  @DisplayName("派单 → SDK echo handler → REPORT 成功路径")
  void sdkRoundTrip_dispatchAndReport() throws Exception {
    // 准备 — 注册 echo handler,启动 SDK 指向 stub HTTP + 真 Kafka
    SdkTaskHandler echo =
        new SdkTaskHandler() {
          @Override
          public String taskType() {
            return "r3_6_echo";
          }

          @Override
          public SdkTaskResult execute(SdkTaskContext ctx) {
            Object payload = ctx.parameters().get("payload");
            return SdkTaskResult.ok("echoed:" + payload, Map.of("echoed", payload));
          }
        };

    BatchPlatformClientConfig cfg =
        BatchPlatformClientConfig.builder()
            .baseUrl(stub.baseUrl())
            .tenantId(TENANT)
            .workerCode(WORKER)
            .kafkaBootstrap(KAFKA.getBootstrapServers())
            .kafkaTopicPattern(TOPIC_PATTERN)
            .kafkaGroupId("r3-6-group")
            .heartbeatInterval(Duration.ofSeconds(30))
            .leaseRenewInterval(Duration.ofSeconds(30))
            .kafkaPollInterval(Duration.ofMillis(100))
            .build();

    BatchPlatformClient client = BatchPlatformClient.builder(cfg).register(echo).build();
    client.start();
    try {
      // 等 consumer 订阅生效 — 真 broker rebalance 比 EmbeddedKafka 慢一点,给 3s 缓冲
      // 再发派单,否则 auto.offset.reset=latest 会让消息被 skip
      Thread.sleep(3_000);

      // 执行 — 真 Kafka 发派单消息
      var msg =
          TaskDispatchMessageBuilder.dispatch("r3_6_echo")
              .tenantId(TENANT)
              .taskId(424242L)
              .param("payload", "hello-r3-6")
              .build();
      byte[] body = new ObjectMapper().writeValueAsBytes(msg);
      producer.send(new ProducerRecord<>(TOPIC, String.valueOf(msg.taskId()), body)).get();

      // 断言 — REPORT 在 30s 内到达 stub,内容正确
      RecordedReport report = stub.awaitReport(424242L, Duration.ofSeconds(30));
      assertThat(report.success()).isTrue();
      assertThat(report.message()).isEqualTo("echoed:hello-r3-6");
      assertThat(report.outputs()).containsEntry("echoed", "hello-r3-6");

      // SDK 经过了 register + claim,证明 wire 协议端到端通
      assertThat(stub.registrations()).isNotEmpty();
      assertThat(stub.claims()).contains(424242L);
    } finally {
      client.stop();
    }
  }

  // ─── 嵌入 HTTP stub orchestrator(testkit FakeBatchPlatform 的最小子集,只走 HTTP 端,不带 EmbeddedKafka) ───

  /**
   * 最小化 orchestrator HTTP stub —— 录 register / claim / report 体,全部 200,够 SDK 跑完一轮。
   *
   * <p>本 stub 不做协议字段强校验(那是 SDK 单测的事),只验"SDK 真发了 HTTP + 体能被反序列化 + 路径正确"。
   */
  private static final class StubOrchestrator implements AutoCloseable {

    private static final byte[] OK = "{}".getBytes(StandardCharsets.UTF_8);
    private final HttpServer server;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, RecordedReport> reports = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> registrations = new CopyOnWriteArrayList<>();
    private final List<Long> claims = new CopyOnWriteArrayList<>();

    private StubOrchestrator(HttpServer server, String baseUrl) {
      this.server = server;
      this.baseUrl = baseUrl;
    }

    static StubOrchestrator start() throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      StubOrchestrator orch = new StubOrchestrator(server, url);
      server.createContext("/internal/", orch::handle);
      server.setExecutor(Executors.newFixedThreadPool(4));
      server.start();
      return orch;
    }

    String baseUrl() {
      return baseUrl;
    }

    List<Map<String, Object>> registrations() {
      return List.copyOf(registrations);
    }

    List<Long> claims() {
      return List.copyOf(claims);
    }

    RecordedReport awaitReport(long taskId, Duration timeout) {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        RecordedReport r = reports.get(taskId);
        if (r != null) {
          return r;
        }
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("interrupted while awaiting report", e);
        }
      }
      throw new AssertionError("no report for taskId=" + taskId + " within " + timeout);
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      Map<String, Object> body = readBody(exchange);
      try {
        if (path.endsWith("/register")) {
          registrations.add(body);
        } else if (path.endsWith("/report")) {
          Long taskId = asLong(body.get("taskId"));
          if (taskId != null) {
            reports.put(
                taskId,
                new RecordedReport(
                    taskId,
                    Boolean.TRUE.equals(body.get("success")),
                    String.valueOf(body.get("message")),
                    body.get("outputs") instanceof Map<?, ?> m
                        ? Map.copyOf((Map<String, Object>) m)
                        : Map.of(),
                    body.get("errorCode") == null ? null : String.valueOf(body.get("errorCode")),
                    body));
          }
        } else if (path.endsWith("/claim")) {
          Long taskId = parseTaskIdFromPath(path);
          if (taskId != null) {
            claims.add(taskId);
          }
        }
        // heartbeat / deactivate / renew / 其他 → 直接 200
        respond(exchange, 200);
      } catch (RuntimeException e) {
        respond(exchange, 500);
      }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
      try (InputStream in = exchange.getRequestBody()) {
        byte[] bytes = in.readAllBytes();
        if (bytes.length == 0) {
          return Map.of();
        }
        return mapper.readValue(bytes, Map.class);
      }
    }

    private static Long parseTaskIdFromPath(String path) {
      String[] parts = path.split("/");
      for (int i = 0; i < parts.length - 1; i++) {
        if ("tasks".equals(parts[i])) {
          try {
            return Long.valueOf(parts[i + 1]);
          } catch (NumberFormatException ignored) {
            return null;
          }
        }
      }
      return null;
    }

    private static Long asLong(Object value) {
      return value instanceof Number n ? n.longValue() : null;
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, OK.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(OK);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      if (server.getExecutor() instanceof ExecutorService es) {
        es.shutdownNow();
      }
    }
  }
}
