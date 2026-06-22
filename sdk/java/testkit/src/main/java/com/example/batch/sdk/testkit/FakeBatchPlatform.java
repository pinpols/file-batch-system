package com.example.batch.sdk.testkit;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.client.BatchPlatformClientConfig.BatchPlatformClientConfigBuilder;
import com.example.batch.sdk.dispatcher.TaskDispatchMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * 租户 SDK worker 集成测试的「假平台」(SDK-P5-2 / roadmap §7) —— 一次性起两端真实依赖:
 *
 * <ul>
 *   <li><b>EmbeddedKafka</b>(KRaft,纯 JVM 无 Docker):派单 topic,{@link #dispatch} 真发消息给 SDK consumer
 *   <li><b>JDK HttpServer</b>:stub orchestrator {@code /internal/*}(register / heartbeat /
 *       deactivate / claim / report / renew),全部 200,并<b>录下</b> register / claim / report 供断言
 * </ul>
 *
 * <p>因此 SDK 的 {@code BatchPlatformClient} 不用改一行就能端到端跑:start → consumer 订到内嵌 broker → dispatch 投消息 →
 * handler 执行 → report 回到本 stub。典型用法见 {@link BatchWorkerTest} 与模块自测。
 *
 * <p>非线程安全的生命周期(start / close)单线程调用;录制容器线程安全,可在 handler / 测试线程并发读。
 */
public final class FakeBatchPlatform implements AutoCloseable {

  /** 内嵌 broker 的派单 topic;{@link #DISPATCH_TOPIC_PATTERN} 正则匹配它。 */
  public static final String DISPATCH_TOPIC = "batch.task.dispatch.testkit";

  /** 默认 config 的 {@code kafkaTopicPattern}(正则,匹配 {@link #DISPATCH_TOPIC})。 */
  public static final String DISPATCH_TOPIC_PATTERN = "batch\\.task\\.dispatch\\..*";

  private static final byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String JSON_MIME = "application/json";
  private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(15);

  private final EmbeddedKafkaBroker broker;
  private final HttpServer httpServer;
  private final String baseUrl;
  private final String kafkaBootstrap;
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private final Map<Long, RecordedReport> reports = new ConcurrentHashMap<>();
  private final List<Map<String, Object>> registrations = new CopyOnWriteArrayList<>();
  private final List<Long> claims = new CopyOnWriteArrayList<>();

  /** taskId → claim 时携带的 partitionInvocationId(仅分区任务),供 renew/report 回校防回归。 */
  private final Map<Long, String> claimedInvocations = new ConcurrentHashMap<>();

  private volatile Producer<String, byte[]> producer;

  private FakeBatchPlatform(
      EmbeddedKafkaBroker broker, HttpServer httpServer, String baseUrl, String kafkaBootstrap) {
    this.broker = broker;
    this.httpServer = httpServer;
    this.baseUrl = baseUrl;
    this.kafkaBootstrap = kafkaBootstrap;
  }

  /** 起内嵌 broker + HTTP stub。调用方负责 {@link #close()}(或用 {@link BatchWorkerTest} 托管)。 */
  public static FakeBatchPlatform start() {
    EmbeddedKafkaBroker broker = new EmbeddedKafkaKraftBroker(1, 1, DISPATCH_TOPIC);
    // 首次 rebalance 不等默认 3s,让 test 在秒级内拿到 partition assignment。
    broker.brokerProperties(Map.of("group.initial.rebalance.delay.ms", "0"));
    try {
      broker.afterPropertiesSet();
    } catch (Exception e) {
      throw new IllegalStateException("embedded kafka start failed", e);
    }
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      broker.destroy();
      throw new IllegalStateException("http stub start failed", e);
    }
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    FakeBatchPlatform platform =
        new FakeBatchPlatform(broker, server, url, broker.getBrokersAsString());
    server.createContext("/internal/", platform::handleHttp);
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.start();
    return platform;
  }

  // ─── 配置 ───────────────────────────────────────────────────────────────────

  /** 预接好两端的 config builder;租户可继续覆盖(如 maxConcurrentTasks)再 {@code build()}。 */
  public BatchPlatformClientConfigBuilder configBuilder(String tenantId, String workerCode) {
    return BatchPlatformClientConfig.builder()
        .baseUrl(baseUrl)
        .tenantId(tenantId)
        .workerCode(workerCode)
        .kafkaBootstrap(kafkaBootstrap)
        .kafkaTopicPattern(DISPATCH_TOPIC_PATTERN)
        .kafkaGroupId("testkit-" + workerCode)
        .heartbeatInterval(Duration.ofSeconds(30))
        .leaseRenewInterval(Duration.ofSeconds(30))
        .kafkaPollInterval(Duration.ofMillis(100));
  }

  /** 预接好两端的 config(默认参数)。 */
  public BatchPlatformClientConfig configFor(String tenantId, String workerCode) {
    return configBuilder(tenantId, workerCode).build();
  }

  // ─── 派单 ───────────────────────────────────────────────────────────────────

  /**
   * 投一条派单消息给 SDK consumer。先等 consumer 拿到 partition assignment(SDK 端 {@code
   * auto.offset.reset=latest},必须等订阅生效后再发,否则消息被跳过),再真发到内嵌 broker。
   */
  public void dispatch(TaskDispatchMessage msg) {
    awaitTopicAssignment(ASSIGNMENT_TIMEOUT);
    sleepQuietly(250); // assignment 可见后给 consumer 一点时间把 position 落到 log end
    byte[] payload;
    try {
      payload = objectMapper.writeValueAsBytes(msg);
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot serialize dispatch message", e);
    }
    String key = msg.taskId() == null ? null : String.valueOf(msg.taskId());
    try {
      producer().send(new ProducerRecord<>(DISPATCH_TOPIC, key, payload)).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("dispatch interrupted", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("dispatch send failed", e);
    }
  }

  /** 阻塞等待某 taskId 的 report 到达,超时抛 {@link AssertionError}(测试断言友好)。 */
  public RecordedReport awaitReport(long taskId, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      RecordedReport report = reports.get(taskId);
      if (report != null) {
        return report;
      }
      sleepQuietly(50);
    }
    throw new AssertionError("no report for taskId=" + taskId + " within " + timeout);
  }

  // ─── 录制读取 ─────────────────────────────────────────────────────────────────

  public String baseUrl() {
    return baseUrl;
  }

  public String kafkaBootstrap() {
    return kafkaBootstrap;
  }

  public String topic() {
    return DISPATCH_TOPIC;
  }

  /** 收到的 register body 快照列表(按到达顺序)。 */
  public List<Map<String, Object>> registrations() {
    return List.copyOf(registrations);
  }

  /** 收到的 CLAIM 的 taskId 列表(按到达顺序)。 */
  public List<Long> claims() {
    return List.copyOf(claims);
  }

  /** 已收到的 report 快照(taskId → report)。 */
  public Map<Long, RecordedReport> reports() {
    return Map.copyOf(reports);
  }

  // ─── HTTP stub ───────────────────────────────────────────────────────────────

  private void handleHttp(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    try {
      if (path.endsWith("/register")) {
        registrations.add(readBody(exchange));
      } else if (path.endsWith("/report")) {
        Map<String, Object> body = readBody(exchange);
        recordReport(body);
        // 守护回归:claim 带过 partitionInvocationId 的任务,report 必须也带(平台 R3-P0-5 late-report CAS 依赖它)。
        if (missingClaimedInvocation(path, body)) {
          respond(exchange, 409);
          return;
        }
      } else if (path.endsWith("/claim")) {
        captureClaimInvocation(taskIdFromPath(path), readBody(exchange));
        claims.add(taskIdFromPath(path));
      } else if (path.endsWith("/renew")) {
        Map<String, Object> body = readBody(exchange);
        // 守护回归:分区任务 renew 缺 partitionInvocationId,平台 R3-P1-10 会 409→不续租→双跑。这里如实拒。
        if (missingClaimedInvocation(path, body)) {
          respond(exchange, 409);
          return;
        }
      } else {
        readBody(exchange); // heartbeat / deactivate:消费 body 后 200
      }
      respond(exchange, 200);
    } catch (RuntimeException e) {
      respond(exchange, 500);
    }
  }

  /** claim 时若带了 partitionInvocationId 则留存,供 renew/report 回校(非分区任务 claim 无此字段,不留存)。 */
  private void captureClaimInvocation(Long taskId, Map<String, Object> claimBody) {
    if (taskId == null) {
      return;
    }
    Object pInv = claimBody.get("partitionInvocationId");
    if (pInv != null && !pInv.toString().isBlank()) {
      claimedInvocations.put(taskId, pInv.toString());
    }
  }

  /** 该 task claim 过 invocation,但本次 renew/report body 缺(或空)→ true,模拟平台拒绝。 */
  private boolean missingClaimedInvocation(String path, Map<String, Object> body) {
    Long taskId = taskIdFromPath(path);
    if (taskId == null || !claimedInvocations.containsKey(taskId)) {
      return false;
    }
    Object pInv = body.get("partitionInvocationId");
    return pInv == null || pInv.toString().isBlank();
  }

  private void recordReport(Map<String, Object> body) {
    Long taskId = asLong(body.get("taskId"));
    if (taskId == null) {
      return;
    }
    boolean success = Boolean.TRUE.equals(body.get("success"));
    reports.put(
        taskId,
        new RecordedReport(
            taskId,
            success,
            asString(body.get("message")),
            asMap(body.get("outputs")),
            asString(body.get("errorCode")),
            body));
  }

  private Map<String, Object> readBody(HttpExchange exchange) {
    try (InputStream in = exchange.getRequestBody()) {
      byte[] bytes = in.readAllBytes();
      if (bytes.length == 0) {
        return Map.of();
      }
      return parseMap(bytes);
    } catch (IOException e) {
      return Map.of();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseMap(byte[] bytes) throws IOException {
    return objectMapper.readValue(bytes, Map.class);
  }

  private static void respond(HttpExchange exchange, int status) throws IOException {
    exchange.getResponseHeaders().add(CONTENT_TYPE, JSON_MIME);
    exchange.sendResponseHeaders(status, EMPTY_JSON.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(EMPTY_JSON);
    }
  }

  private static Long taskIdFromPath(String path) {
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if ("tasks".equals(parts[i])) {
        return parseLong(parts[i + 1]);
      }
    }
    return null;
  }

  // ─── Kafka 辅助 ───────────────────────────────────────────────────────────────

  private void awaitTopicAssignment(Duration timeout) {
    Properties props = new Properties();
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
    long deadline = System.nanoTime() + timeout.toNanos();
    try (Admin admin = Admin.create(props)) {
      while (System.nanoTime() < deadline) {
        if (hasAssignedConsumer(admin)) {
          return;
        }
        sleepQuietly(100);
      }
    }
    throw new IllegalStateException(
        "no SDK consumer assigned to topic "
            + DISPATCH_TOPIC
            + " within "
            + timeout
            + "; did you call client.start() before dispatch()?");
  }

  private boolean hasAssignedConsumer(Admin admin) {
    try {
      Collection<GroupListing> groups = admin.listGroups().all().get();
      List<String> ids = groups.stream().map(GroupListing::groupId).toList();
      if (ids.isEmpty()) {
        return false;
      }
      Map<String, ConsumerGroupDescription> described =
          admin.describeConsumerGroups(ids).all().get();
      return described.values().stream().anyMatch(FakeBatchPlatform::ownsDispatchTopic);
    } catch (ExecutionException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean ownsDispatchTopic(ConsumerGroupDescription group) {
    for (MemberDescription member : group.members()) {
      for (TopicPartition tp : member.assignment().topicPartitions()) {
        if (DISPATCH_TOPIC.equals(tp.topic())) {
          return true;
        }
      }
    }
    return false;
  }

  private synchronized Producer<String, byte[]> producer() {
    if (producer == null) {
      Properties props = new Properties();
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
      props.put(ProducerConfig.ACKS_CONFIG, "all");
      producer = new KafkaProducer<>(props);
    }
    return producer;
  }

  // ─── 杂项 ─────────────────────────────────────────────────────────────────────

  @Override
  public void close() {
    if (producer != null) {
      try {
        producer.close(Duration.ofSeconds(2));
      } catch (RuntimeException ignored) {
        // 关闭 producer 失败不应淹没测试结果
      }
    }
    httpServer.stop(0);
    if (httpServer.getExecutor() instanceof ExecutorService es) {
      es.shutdownNow();
    }
    broker.destroy();
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Long asLong(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> m ? Map.copyOf((Map<String, Object>) m) : Map.of();
  }

  private static Long parseLong(String s) {
    try {
      return Long.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
