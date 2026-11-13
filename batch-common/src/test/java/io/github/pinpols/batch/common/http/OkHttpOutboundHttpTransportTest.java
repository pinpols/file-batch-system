package io.github.pinpols.batch.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pinpols.batch.common.security.BlockedAddressException;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.net.SocketFactory;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("外部出站网络传输 - 校验地址策略选择,请求响应映射,双栈连接回退与代理绕过行为")
class OkHttpOutboundHttpTransportTest {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

  @Test
  @DisplayName("运维托管端点走信任策略时由信任通道处理,受保护通道不被调用")
  void shouldUseTrustedChannel_whenEndpointIsOperatorManaged() throws Exception {
    RecordingInterceptor guarded = new RecordingInterceptor("guarded");
    RecordingInterceptor trusted = new RecordingInterceptor("trusted");
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(client(guarded), client(trusted));

    OutboundHttpResponse response = transport.execute(OutboundHttpRequest.get(
        "http://alertmanager.monitoring.svc/api/v2/status",
        Map.of(),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.TRUSTED));

    assertThat(response.body()).isEqualTo("trusted");
    assertThat(trusted.called).isTrue();
    assertThat(guarded.called).isFalse();
  }

  @Test
  @DisplayName("受控策略下由受控客户端发出请求, 方法、头部、请求体与连接读超时按要求映射")
  void shouldMapPostAndSelectGuardedClient_whenPolicyIsGuarded() throws Exception {
    RecordingInterceptor guarded = new RecordingInterceptor("accepted", 202);
    RecordingInterceptor trusted = new RecordingInterceptor("unused");
    OkHttpOutboundHttpTransport transport = new OkHttpOutboundHttpTransport(
        client(guarded), client(trusted), hostname -> List.of(InetAddress.getLoopbackAddress()));

    OutboundHttpContent httpContent =
        new OutboundHttpContent("{\"ok\":true}", "application/json; charset=utf-8");
    OutboundHttpRequest httpRequest = OutboundHttpRequest.post(
        "https://example.test/notify",
        Map.of("X-Test", "value"),
        httpContent,
        Duration.ofSeconds(2),
        Duration.ofSeconds(3),
        OutboundAddressPolicy.GUARDED);
    OutboundHttpResponse response = transport.execute(httpRequest);

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(response.body()).isEqualTo("accepted");
    assertThat(guarded.method).isEqualTo("POST");
    assertThat(guarded.header).isEqualTo("value");
    assertThat(guarded.body).isEqualTo("{\"ok\":true}");
    assertThat(guarded.connectTimeoutMillis).isEqualTo(2000);
    assertThat(guarded.readTimeoutMillis).isEqualTo(3000);
    assertThat(trusted.called).isFalse();
  }

  @Test
  @DisplayName("POST 请求体为空时按空字符串发送, 不因缺少请求体而失败")
  void shouldSendEmptyBody_whenPostBodyIsNull() throws Exception {
    RecordingInterceptor trusted = new RecordingInterceptor("ok");
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(client(new RecordingInterceptor("g")), client(trusted));
    OutboundHttpContent emptyContent = new OutboundHttpContent(null, "text/plain");
    OutboundHttpRequest request = OutboundHttpRequest.post(
        "https://example.test/empty",
        Map.of(),
        emptyContent,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.TRUSTED);

    OutboundHttpResponse response = transport.execute(request);

    assertThat(response.body()).isEqualTo("ok");
    assertThat(trusted.method).isEqualTo("POST");
    assertThat(trusted.body).isEmpty();
  }

  @Test
  @DisplayName("HEAD 请求透传方法, 响应缺少 Content-Type 时按 UTF-8 解码")
  void shouldDecodeAsUtf8_whenResponseHasNoContentType() throws Exception {
    RecordingInterceptor trusted = new RecordingInterceptor("中文", 200, null);
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(client(new RecordingInterceptor("g")), client(trusted));
    OutboundHttpRequest request = OutboundHttpRequest.head(
        "https://example.test/head",
        Map.of(),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.TRUSTED);

    OutboundHttpResponse response = transport.execute(request);

    assertThat(trusted.method).isEqualTo("HEAD");
    assertThat(response.body()).isEqualTo("中文");
  }

  @Test
  @DisplayName("受信策略下响应体超出上限时抛出带超限提示的异常")
  void shouldRejectOversizedResponse_whenBodyExceedsLimit() {
    RecordingInterceptor trusted = new RecordingInterceptor("x".repeat(1024 * 1024 + 1));
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(client(new RecordingInterceptor("g")), client(trusted));

    OutboundHttpRequest request = OutboundHttpRequest.get(
        "https://example.test/large",
        Map.of(),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.TRUSTED);

    assertThatThrownBy(() -> transport.execute(request))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exceeds");
  }

  @Test
  @DisplayName("受控 DNS 未返回任何地址时以未知主机失败, 不发出网络请求")
  void shouldFailWithUnknownHost_whenGuardedDnsReturnsNoAddress() {
    RecordingInterceptor guarded = new RecordingInterceptor("unexpected");
    OkHttpClient client = client(guarded);
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(client, client, hostname -> List.of());
    OutboundHttpRequest request = OutboundHttpRequest.get(
        "https://example.test/none",
        Map.of(),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.GUARDED);

    assertThatThrownBy(() -> transport.execute(request))
        .isInstanceOf(UnknownHostException.class)
        .hasMessageContaining("example.test");
    assertThat(guarded.called).isFalse();
  }

  @Test
  @DisplayName("生产信任通道同样不跟随重定向, 关闭时两个客户端的调度线程池都被释放")
  void shouldShutdownBothClients_whenTransportIsClosed() {
    OkHttpClient trustedClient = OkHttpOutboundHttpTransport.trustedClient();
    OkHttpClient guardedClient = OkHttpOutboundHttpTransport.guardedClient();
    ExecutorService trustedExecutor = trustedClient.dispatcher().executorService();
    ExecutorService guardedExecutor = guardedClient.dispatcher().executorService();
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(guardedClient, trustedClient);

    transport.shutdown();

    assertThat(trustedClient.followRedirects()).isFalse();
    assertThat(trustedExecutor.isShutdown()).isTrue();
    assertThat(guardedExecutor.isShutdown()).isTrue();
  }

  @Test
  @DisplayName("两个策略共用同一客户端时关闭只释放一次且不报错")
  void shouldShutdownSharedClientOnce_whenGuardedAndTrustedAreSame() {
    OkHttpClient sharedClient = OkHttpOutboundHttpTransport.guardedClient();
    ExecutorService executor = sharedClient.dispatcher().executorService();
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(sharedClient, sharedClient);

    transport.shutdown();

    assertThat(executor.isShutdown()).isTrue();
  }

  @Test
  @DisplayName("IPv6 候选地址不可达时在连接超时前回退到 IPv4 并完成请求")
  void shouldFallBackToIpv4_whenIpv6CandidateIsUnreachable() throws Exception {
    InetAddress unreachableIpv6 = InetAddress.getByName("2001:db8::1");
    InetAddress reachableIpv4 = InetAddress.getByName("127.0.0.1");
    try (MockWebServer server = startServer(reachableIpv4, "ipv4")) {
      FaultInjectingSocketFactory socketFactory = new FaultInjectingSocketFactory(unreachableIpv6);
      OkHttpClient client = happyEyeballsClient(socketFactory);
      OkHttpOutboundHttpTransport transport = new OkHttpOutboundHttpTransport(
          client, client, hostname -> List.of(unreachableIpv6, reachableIpv4));

      long startedAt = System.nanoTime();
      OutboundHttpResponse response = transport.execute(request(server.getPort()));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      assertThat(response.body()).isEqualTo("ipv4");
      assertThat(socketFactory.attemptedAddresses())
          .containsSubsequence(unreachableIpv6, reachableIpv4);
      assertThat(elapsedMillis).isBetween(150L, 2_500L);
    }
  }

  @Test
  @DisplayName("IPv4 候选地址不可达时改走 IPv6 完成连接且不尝试 IPv4")
  void shouldConnectOverIpv6_whenIpv4CandidateIsUnreachable() throws Exception {
    InetAddress unreachableIpv4 = InetAddress.getByName("192.0.2.1");
    InetAddress reachableIpv6 = InetAddress.getByName("::1");
    try (MockWebServer server = startIpv6ServerOrSkip(reachableIpv6, "ipv6")) {
      FaultInjectingSocketFactory socketFactory = new FaultInjectingSocketFactory(unreachableIpv4);
      OkHttpClient client = happyEyeballsClient(socketFactory);
      OkHttpOutboundHttpTransport transport = new OkHttpOutboundHttpTransport(
          client, client, hostname -> List.of(unreachableIpv4, reachableIpv6));

      long startedAt = System.nanoTime();
      OutboundHttpResponse response = transport.execute(request(server.getPort()));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      assertThat(response.body()).isEqualTo("ipv6");
      // OkHttp 5 会把双栈候选交错成 IPv6-first；IPv6 可用时不会尝试 IPv4 候选。
      assertThat(socketFactory.attemptedAddresses()).containsExactly(reachableIpv6);
      assertThat(elapsedMillis).isLessThan(2_500L);
    }
  }

  @Test
  @DisplayName("双栈地址均可达时优先使用 IPv6,IPv4 侧不收到请求")
  void shouldUseFirstReachableAddress_whenBothIpFamiliesAreReachable() throws Exception {
    InetAddress reachableIpv6 = InetAddress.getByName("::1");
    InetAddress reachableIpv4 = InetAddress.getByName("127.0.0.1");
    try (MockWebServer ipv6Server = startIpv6ServerOrSkip(reachableIpv6, "ipv6")) {
      int sharedPort = ipv6Server.getPort();
      try (MockWebServer ipv4Server = startServer(reachableIpv4, sharedPort, "ipv4")) {
        OkHttpClient client = OkHttpOutboundHttpTransport.baseClient()
            .retryOnConnectionFailure(false)
            .build();
        OkHttpOutboundHttpTransport transport = new OkHttpOutboundHttpTransport(
            client, client, hostname -> List.of(reachableIpv6, reachableIpv4));

        OutboundHttpResponse response = transport.execute(request(sharedPort));

        assertThat(response.body()).isEqualTo("ipv6");
        assertThat(ipv6Server.getRequestCount()).isEqualTo(1);
        assertThat(ipv4Server.getRequestCount()).isZero();
      }
    }
  }

  @Test
  @DisplayName("生产受保护通道保持快速回退,不跟随重定向,不自动重试且不使用代理")
  void shouldKeepFastFallbackAndNoProxy_whenUsingProductionGuardedClient() {
    OkHttpClient client = OkHttpOutboundHttpTransport.guardedClient();

    assertThat(client.fastFallback()).isTrue();
    assertThat(client.followRedirects()).isFalse();
    assertThat(client.followSslRedirects()).isFalse();
    assertThat(client.retryOnConnectionFailure()).isFalse();
    assertThat(client.proxy()).isSameAs(Proxy.NO_PROXY);
  }

  @Test
  @DisplayName("受保护请求指向私有地址字面量时在发起网络调用之前被拒绝")
  void shouldRejectPrivateAddressLiteral_whenGuardedRequestIsSent() {
    OkHttpClient client = OkHttpOutboundHttpTransport.baseClient()
        .addInterceptor(new RecordingInterceptor("unexpected"))
        .build();
    OkHttpOutboundHttpTransport transport =
        new OkHttpOutboundHttpTransport(client, client, DnsResolveGuard::resolveAllAndValidate);
    OutboundHttpRequest request = OutboundHttpRequest.get(
        "http://169.254.169.254/latest/meta-data/",
        Map.of(),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.GUARDED);

    assertThatThrownBy(() -> transport.execute(request))
        .isInstanceOf(BlockedAddressException.class);
  }

  @Test
  @DisplayName("受保护请求只解析一次地址并绕过系统代理,最终仅发出一次真实请求")
  void shouldResolveOnceAndBypassSystemProxy_whenRequestIsGuarded() throws Exception {
    InetAddress reachableIpv4 = InetAddress.getByName("127.0.0.1");
    AtomicInteger resolutions = new AtomicInteger();
    RecordingProxySelector proxySelector = new RecordingProxySelector();
    try (MockWebServer server = startServer(reachableIpv4, "direct")) {
      OkHttpClient client =
          OkHttpOutboundHttpTransport.baseClient().proxySelector(proxySelector).build();
      OkHttpOutboundHttpTransport transport =
          new OkHttpOutboundHttpTransport(client, client, hostname -> {
            resolutions.incrementAndGet();
            return List.of(reachableIpv4);
          });

      OutboundHttpResponse response = transport.execute(request(server.getPort()));

      assertThat(response.body()).isEqualTo("direct");
      assertThat(resolutions).hasValue(1);
      assertThat(proxySelector.selections()).isZero();
      assertThat(server.getRequestCount()).isEqualTo(1);
    }
  }

  private static MockWebServer startServer(InetAddress address, String responseBody)
      throws IOException {
    return startServer(address, 0, responseBody);
  }

  private static MockWebServer startServer(InetAddress address, int port, String responseBody)
      throws IOException {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse.Builder().body(responseBody).build());
    server.start(address, port);
    return server;
  }

  private static MockWebServer startIpv6ServerOrSkip(InetAddress address, String responseBody) {
    try {
      return startServer(address, responseBody);
    } catch (IOException exception) {
      assumeTrue(false, "IPv6 loopback is unavailable: " + exception.getMessage());
      throw new IllegalStateException("unreachable after aborted assumption", exception);
    }
  }

  private static OkHttpClient happyEyeballsClient(SocketFactory socketFactory) {
    return OkHttpOutboundHttpTransport.baseClient().socketFactory(socketFactory).build();
  }

  private static OutboundHttpRequest request(int port) {
    return OutboundHttpRequest.get(
        "http://dual-stack.test:" + port + "/health",
        Map.of(),
        CONNECT_TIMEOUT,
        REQUEST_TIMEOUT,
        OutboundAddressPolicy.GUARDED);
  }

  private static OkHttpClient client(Interceptor interceptor) {
    return new OkHttpClient.Builder().addInterceptor(interceptor).build();
  }

  private static final class RecordingInterceptor implements Interceptor {
    private static final MediaType TEXT_PLAIN = MediaType.get("text/plain; charset=utf-8");

    private final String responseBody;
    private final int responseCode;
    private final MediaType responseType;
    private boolean called;
    private String method;
    private String header;
    private String body;
    private int connectTimeoutMillis;
    private int readTimeoutMillis;

    private RecordingInterceptor(String responseBody) {
      this(responseBody, 200);
    }

    private RecordingInterceptor(String responseBody, int responseCode) {
      this(responseBody, responseCode, TEXT_PLAIN);
    }

    private RecordingInterceptor(String responseBody, int responseCode, MediaType responseType) {
      this.responseBody = responseBody;
      this.responseCode = responseCode;
      this.responseType = responseType;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
      called = true;
      Request request = chain.request();
      method = request.method();
      header = request.header("X-Test");
      connectTimeoutMillis = chain.connectTimeoutMillis();
      readTimeoutMillis = chain.readTimeoutMillis();
      RequestBody requestBody = request.body();
      // empty-check: allow - OkHttp 的 GET/HEAD 请求体为 null，属于第三方 API 约定
      if (requestBody != null) {
        Buffer buffer = new Buffer();
        requestBody.writeTo(buffer);
        body = buffer.readUtf8();
      }
      return new Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(responseCode)
          .message("OK")
          .body(ResponseBody.create(responseBody, responseType))
          .build();
    }
  }

  private static final class RecordingProxySelector extends ProxySelector {
    private final AtomicInteger selections = new AtomicInteger();

    private int selections() {
      return selections.get();
    }

    @Override
    public List<Proxy> select(URI uri) {
      selections.incrementAndGet();
      return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException exception) {
      // 测试要求 GUARDED 请求完全绕过系统代理，因此该回调不应发生。
    }
  }

  /**
   * 仅用于测试连接竞速：指定地址的 connect 保持挂起，直到 OkHttp 在另一地址成功后关闭该 socket。
   */
  private static final class FaultInjectingSocketFactory extends SocketFactory {
    private final InetAddress unreachableAddress;
    private final List<InetAddress> attemptedAddresses = new CopyOnWriteArrayList<>();

    private FaultInjectingSocketFactory(InetAddress unreachableAddress) {
      this.unreachableAddress = unreachableAddress;
    }

    private List<InetAddress> attemptedAddresses() {
      return List.copyOf(attemptedAddresses);
    }

    @Override
    public Socket createSocket() {
      return new FaultInjectingSocket(unreachableAddress, attemptedAddresses);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      return SocketFactory.getDefault().createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
      return SocketFactory.getDefault().createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      return SocketFactory.getDefault().createSocket(host, port);
    }

    @Override
    public Socket createSocket(
        InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      return SocketFactory.getDefault().createSocket(address, port, localAddress, localPort);
    }
  }

  private static final class FaultInjectingSocket extends Socket {
    private static final long POLL_INTERVAL_MILLIS = 10L;

    private final InetAddress unreachableAddress;
    private final List<InetAddress> attemptedAddresses;

    private FaultInjectingSocket(
        InetAddress unreachableAddress, List<InetAddress> attemptedAddresses) {
      this.unreachableAddress = unreachableAddress;
      this.attemptedAddresses = attemptedAddresses;
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      InetSocketAddress target = (InetSocketAddress) endpoint;
      attemptedAddresses.add(target.getAddress());
      if (!unreachableAddress.equals(target.getAddress())) {
        super.connect(endpoint, timeout);
        return;
      }

      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
      while (!isClosed() && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS));
        if (Thread.currentThread().isInterrupted()) {
          throw new SocketException("fault-injected connect interrupted");
        }
      }
      if (isClosed()) {
        throw new SocketException("fault-injected connect canceled");
      }
      throw new SocketTimeoutException("fault-injected connect timed out");
    }
  }
}
