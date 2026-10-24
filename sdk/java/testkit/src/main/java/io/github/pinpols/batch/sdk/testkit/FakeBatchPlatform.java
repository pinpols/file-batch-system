package io.github.pinpols.batch.sdk.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig.BatchPlatformClientConfigBuilder;
import io.github.pinpols.batch.sdk.dispatcher.TaskDispatchMessage;
import io.github.pinpols.batch.sdk.internal.EmptyChecks;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
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
 *       deactivate / claim / non-idempotent-started / report / renew)，并录下注册、认领、开始与报告。
 *       CLAIM 返回本次权威 invocation，开始、续租、报告按当前 tenant/worker/invocation 校验
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
  /** 每个派发任务通过 CLAIM 返回的持久化任务载荷。 */
  private final Map<Long, String> claimPayloads = new ConcurrentHashMap<>();
  private final Map<Long, String> dispatchedTenants = new ConcurrentHashMap<>();

  private final List<Map<String, Object>> registrations = new CopyOnWriteArrayList<>();
  private final List<Long> claims = new CopyOnWriteArrayList<>();

  /** CLAIM 身份由平台产生，不能采信 Kafka 消息或认领请求里的旧 invocation。 */
  private record ClaimedExecution(String tenantId, String workerId, String invocationId) {}

  private final Map<Long, ClaimedExecution> claimedExecutions = new ConcurrentHashMap<>();
  private final Map<Long, String> nonIdempotentStarts = new ConcurrentHashMap<>();

  private final AtomicReference<Producer<String, byte[]>> producer = new AtomicReference<>();

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
   * 投一条派单消息给 SDK consumer。该辅助入口等待 partition assignment 后发送，
   * 便于测试就绪状态；SDK 无提交位点时从 earliest 读取，先派单后启动由真实 Kafka E2E 覆盖。
   */
  public void dispatch(TaskDispatchMessage msg) {
    awaitTopicAssignment(ASSIGNMENT_TIMEOUT);
    sleepQuietly(250); // assignment 可见后为消费者完成首次 poll 保留既有缓冲
    byte[] payload;
    try {
      payload = objectMapper.writeValueAsBytes(msg);
      Long taskId = msg.taskId();
      String parametersJson = objectMapper.writeValueAsString(msg.parameters());
      claimPayloads.put(taskId, parametersJson);
      dispatchedTenants.put(taskId, msg.tenantId());
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

  /** 已确认的非幂等开始快照(taskId → 当前调用标识)。 */
  public Map<Long, String> nonIdempotentStarts() {
    return Map.copyOf(nonIdempotentStarts);
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
        Map<String, Object> registration = readBody(exchange);
        registrations.add(registration);
      } else if (path.endsWith("/report")) {
        Map<String, Object> body = readBody(exchange);
        if (!hasCurrentClaimOwner(path, body)) {
          respond(exchange, 409);
          return;
        }
        recordReport(body);
      } else if (path.endsWith("/claim")) {
        Long taskId = taskIdFromPath(path);
        Map<String, Object> claimBody = readBody(exchange);
        ClaimedExecution claimed = claimExecution(taskId, claimBody);
        if (claimed == null) {
          respond(exchange, 409);
          return;
        }
        claims.add(taskId);
        respondWithClaimPayload(exchange, claimPayloads.get(taskId), claimed.invocationId());
        return;
      } else if (path.endsWith("/non-idempotent-started")) {
        Map<String, Object> body = readBody(exchange);
        if (!"POST".equals(exchange.getRequestMethod()) || !hasCurrentClaimOwner(path, body)) {
          respond(exchange, 409);
          return;
        }
        Long taskId = taskIdFromPath(path);
        ClaimedExecution claimed = claimedExecutions.get(taskId);
        nonIdempotentStarts.put(taskId, claimed.invocationId());
      } else if (path.endsWith("/renew")) {
        Map<String, Object> body = readBody(exchange);
        if (!hasCurrentClaimOwner(path, body)) {
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

  private ClaimedExecution claimExecution(Long taskId, Map<String, Object> body) {
    String tenantId = asString(body.get("tenantId"));
    String workerId = asString(body.get("workerId"));
    if (taskId == null || !claimPayloads.containsKey(taskId)
        || !dispatchedTenants.get(taskId).equals(tenantId)
        || workerId == null || workerId.isBlank()) {
      return null;
    }
    ClaimedExecution candidate =
        new ClaimedExecution(tenantId, workerId, UUID.randomUUID().toString());
    ClaimedExecution existing = claimedExecutions.putIfAbsent(taskId, candidate);
    ClaimedExecution current = existing == null ? candidate : existing;
    return current.tenantId().equals(tenantId) && current.workerId().equals(workerId)
        ? current
        : null;
  }

  private boolean hasCurrentClaimOwner(String path, Map<String, Object> body) {
    Long taskId = taskIdFromPath(path);
    if (taskId == null) {
      return false;
    }
    ClaimedExecution current = claimedExecutions.get(taskId);
    Long reportedTaskId = asLong(body.get("taskId"));
    return current != null
        && (reportedTaskId == null || taskId.equals(reportedTaskId))
        && current.tenantId().equals(asString(body.get("tenantId")))
        && current.workerId().equals(asString(body.get("workerId")))
        && current.invocationId().equals(asString(body.get("partitionInvocationId")));
  }

  private void recordReport(Map<String, Object> body) {
    Long taskId = asLong(body.get("taskId"));
    if (taskId == null) {
      return;
    }
    boolean success = Boolean.TRUE.equals(body.get("success"));
    String message = asString(body.get("message"));
    Map<String, Object> outputs = asMap(body.get("outputs"));
    String errorCode = asString(body.get("errorCode"));
    reports.put(
        taskId,
        new RecordedReport(taskId, success, message, outputs, errorCode, body));
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

  private void respondWithClaimPayload(
      HttpExchange exchange, String payload, String invocationId) throws IOException {
    Map<String, Object> body = Map.of(
        "payload", EmptyChecks.isNull(payload) ? "{}" : payload,
        "partitionInvocationId", invocationId);
    byte[] response = objectMapper.writeValueAsBytes(body);
    exchange.getResponseHeaders().add(CONTENT_TYPE, JSON_MIME);
    exchange.sendResponseHeaders(200, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
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
    throw new IllegalStateException("no SDK consumer assigned to topic "
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
    Producer<String, byte[]> current = producer.get();
    if (current == null) {
      Properties props = new Properties();
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
      props.put(ProducerConfig.ACKS_CONFIG, "all");
      current = new KafkaProducer<>(props);
      producer.set(current);
    }
    return current;
  }

  // ─── 杂项 ─────────────────────────────────────────────────────────────────────

  @Override
  public void close() {
    Producer<String, byte[]> current = producer.getAndSet(null);
    if (current != null) {
      try {
        current.close(Duration.ofSeconds(2));
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
