package com.example.batch.testing.chaos;

import com.example.batch.testing.AbstractIntegrationTest;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Toxiproxy 集成测试基类:在 {@link AbstractIntegrationTest} 提供的 PG / Kafka / Redis 容器前面套一层 Toxiproxy,用例可经由
 * {@link ProxyTarget} 注入 latency / slice / down 等故障,验证应用的熔断 / 降级 / 自愈路径。
 *
 * <p>架构:
 *
 * <pre>
 * [Test JVM] ──&gt; localhost:&lt;toxiproxy mapped port&gt; ──&gt; [Toxiproxy 容器]
 *           ──&gt; host.testcontainers.internal:&lt;PG/Kafka/Redis host port&gt; ──&gt; [真实容器]
 * </pre>
 *
 * <p>关键约束:
 *
 * <ul>
 *   <li>Toxiproxy 容器与 PG/Kafka/Redis 不在同一 Docker network — 通过 {@link
 *       Testcontainers#exposeHostPorts} 让 toxiproxy 反向访问宿主机的 testcontainer 映射端口
 *   <li>三个 proxy listen 端口在容器内 fixed(8666/8667/8668),容器映射后从测试 JVM 通过 {@code getMappedPort}
 *       拿到对应宿主机端口,业务客户端连接 {@code localhost:&lt;mapped&gt;}
 *   <li>{@link AfterEach} 自动清空所有 toxic — 用例间故障隔离,避免互相污染
 * </ul>
 */
public abstract class AbstractChaosIntegrationTest extends AbstractIntegrationTest {

  /** Toxiproxy 容器内固定的代理监听端口 — proxy 在 8666(PG)/ 8667(Kafka)/ 8668(Redis)上 listen。 */
  private static final int PG_PROXY_PORT = 8666;

  private static final int KAFKA_PROXY_PORT = 8667;
  private static final int REDIS_PROXY_PORT = 8668;
  private static final int TOXIPROXY_CONTROL_PORT = 8474;
  // 2.5.0:与 testcontainers-toxiproxy 1.21.4 模块默认 tag 对齐,manifest 在 ghcr 上完整可拉取。
  // 2.12.0 在某些主机网络下 manifest 拉取偶发 404,故钉到 2.5.0。
  private static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.5.0";

  @SuppressWarnings("resource")
  private static final GenericContainer<?> TOXIPROXY =
      new GenericContainer<>(DockerImageName.parse(TOXIPROXY_IMAGE))
          .withExposedPorts(
              TOXIPROXY_CONTROL_PORT, PG_PROXY_PORT, KAFKA_PROXY_PORT, REDIS_PROXY_PORT)
          .waitingFor(Wait.forHttp("/version").forPort(TOXIPROXY_CONTROL_PORT));

  private static ToxiproxyClient client;
  private static Proxy pgProxy;
  private static Proxy kafkaProxy;
  private static Proxy redisProxy;
  private static int pgMappedPort;
  private static int kafkaMappedPort;
  private static int redisMappedPort;

  protected AbstractChaosIntegrationTest() {}

  /**
   * 静态启动 Toxiproxy + 创建三个 proxy。
   *
   * <p>父类 {@link AbstractIntegrationTest} 在 static block 已起 PG/Kafka/Redis,此处只需要把其 host 端口暴露给
   * toxiproxy 容器(via {@link Testcontainers#exposeHostPorts}),然后 wire upstream 指向 {@code
   * host.testcontainers.internal:&lt;host port&gt;}。
   */
  @BeforeAll
  static void startToxiproxyAndCreateProxies() throws IOException {
    // 把宿主机上 PG/Kafka/Redis 的映射端口暴露给 toxiproxy 容器
    Testcontainers.exposeHostPorts(extractPort(platformJdbcUrl()), extractKafkaPort(), redisPort());
    if (!TOXIPROXY.isRunning()) {
      TOXIPROXY.start();
    }
    if (client != null) {
      return;
    }
    client =
        new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(TOXIPROXY_CONTROL_PORT));

    String hostBridge = "host.testcontainers.internal";
    pgProxy =
        getOrCreate(
            "pg_proxy",
            "0.0.0.0:" + PG_PROXY_PORT,
            hostBridge + ":" + extractPort(platformJdbcUrl()));
    kafkaProxy =
        getOrCreate(
            "kafka_proxy", "0.0.0.0:" + KAFKA_PROXY_PORT, hostBridge + ":" + extractKafkaPort());
    redisProxy =
        getOrCreate("redis_proxy", "0.0.0.0:" + REDIS_PROXY_PORT, hostBridge + ":" + redisPort());

    pgMappedPort = TOXIPROXY.getMappedPort(PG_PROXY_PORT);
    kafkaMappedPort = TOXIPROXY.getMappedPort(KAFKA_PROXY_PORT);
    redisMappedPort = TOXIPROXY.getMappedPort(REDIS_PROXY_PORT);
  }

  /** 每个用例后清空所有 toxic — 用例间故障隔离。 */
  @AfterEach
  void resetAllToxics() throws IOException {
    clearToxics(pgProxy);
    clearToxics(kafkaProxy);
    clearToxics(redisProxy);
  }

  private static Proxy getOrCreate(String name, String listen, String upstream) throws IOException {
    Proxy existing = client.getProxyOrNull(name);
    if (existing != null) {
      return existing;
    }
    return client.createProxy(name, listen, upstream);
  }

  private static void clearToxics(Proxy proxy) throws IOException {
    if (proxy == null) {
      return;
    }
    for (Toxic t : proxy.toxics().getAll()) {
      t.remove();
    }
  }

  // ─────────────────────────────  helper API  ─────────────────────────────

  /** 故障注入目标(PG / Kafka / Redis)。 */
  public enum ProxyTarget {
    PG,
    KAFKA,
    REDIS
  }

  /**
   * 在 {@code block} 执行期间向目标 proxy 注入延迟(downstream 方向 = backend → app)。
   *
   * @param target 目标
   * @param latency 单包延迟
   * @param block 用例代码,执行完后 latency toxic 自动移除
   */
  protected static void withLatency(ProxyTarget target, Duration latency, ChaosBlock block)
      throws IOException {
    Proxy proxy = resolve(target);
    String name = "latency_" + System.nanoTime();
    proxy.toxics().latency(name, ToxicDirection.DOWNSTREAM, latency.toMillis());
    try {
      block.run();
    } finally {
      proxy.toxics().get(name).remove();
    }
  }

  /** 在 {@code block} 执行期间向目标 proxy 注入分片(每 {@code bytesPerSlice} 字节切一刀,模拟 TCP 拥塞 / MTU 收缩)。 */
  protected static void withSlice(ProxyTarget target, int bytesPerSlice, ChaosBlock block)
      throws IOException {
    Proxy proxy = resolve(target);
    String name = "slice_" + System.nanoTime();
    proxy.toxics().slicer(name, ToxicDirection.DOWNSTREAM, bytesPerSlice, 0);
    try {
      block.run();
    } finally {
      proxy.toxics().get(name).remove();
    }
  }

  /**
   * 在 {@code block} 执行期间彻底切断目标 proxy。
   *
   * <p>实现:同时叠加 disable(阻止新连接)+ reset_peer toxic(以 0ms 立即 RST 现存连接), 双重保证"故障期内任何 TCP IO 都不通"。
   */
  protected static void withDown(ProxyTarget target, ChaosBlock block) throws IOException {
    Proxy proxy = resolve(target);
    String resetName = "down_reset_" + System.nanoTime();
    proxy.toxics().resetPeer(resetName, ToxicDirection.DOWNSTREAM, 0);
    proxy.disable();
    try {
      block.run();
    } finally {
      proxy.enable();
      try {
        proxy.toxics().get(resetName).remove();
      } catch (IOException ignored) {
        // toxic 可能已被 @AfterEach 清理;忽略
      }
    }
  }

  private static Proxy resolve(ProxyTarget target) {
    return switch (target) {
      case PG -> pgProxy;
      case KAFKA -> kafkaProxy;
      case REDIS -> redisProxy;
    };
  }

  // ─────────────────────────────  endpoint getters  ─────────────────────────────

  /** 经 toxiproxy 转发后的 PG JDBC URL — 业务侧 DataSource 应指向本 URL。 */
  protected static String pgProxiedJdbcUrl() {
    String original = platformJdbcUrl();
    // jdbc:postgresql://host:port/db?...
    int schemeEnd = original.indexOf("://") + 3;
    int slash = original.indexOf('/', schemeEnd);
    String tail = original.substring(slash);
    return "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + pgMappedPort + tail;
  }

  protected static String kafkaProxiedBootstrapServers() {
    return TOXIPROXY.getHost() + ":" + kafkaMappedPort;
  }

  protected static String redisProxiedHost() {
    return TOXIPROXY.getHost();
  }

  protected static int redisProxiedPort() {
    return redisMappedPort;
  }

  // ─────────────────────────────  helpers  ─────────────────────────────

  private static int extractPort(String jdbcUrl) {
    try {
      // jdbc:postgresql://host:5432/db → strip "jdbc:" 后用 URI 解析
      URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
      return uri.getPort();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("无法解析 JDBC URL: " + jdbcUrl, e);
    }
  }

  private static int extractKafkaPort() {
    // bootstrap: "host:port" — 取冒号后第一段
    String boot = kafkaBootstrapServers();
    int colon = boot.lastIndexOf(':');
    return Integer.parseInt(boot.substring(colon + 1));
  }

  /** chaos 块函数式接口 — 允许 throw checked IOException(toxiproxy client API 全 checked)。 */
  @FunctionalInterface
  protected interface ChaosBlock {
    void run() throws IOException;
  }
}
