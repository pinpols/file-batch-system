package io.github.pinpols.batch.orchestrator.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class OkHttpOrchestratorExternalHttpTransportTest {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

  @Test
  void selectsTrustedClientForOperatorManagedEndpoint() throws Exception {
    RecordingInterceptor guarded = new RecordingInterceptor("guarded");
    RecordingInterceptor trusted = new RecordingInterceptor("trusted");
    OkHttpOrchestratorExternalHttpTransport transport =
        new OkHttpOrchestratorExternalHttpTransport(client(guarded), client(trusted));

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
  void fallsBackFromUnreachableIpv6ToIpv4BeforeConnectTimeout() throws Exception {
    InetAddress unreachableIpv6 = InetAddress.getByName("2001:db8::1");
    InetAddress reachableIpv4 = InetAddress.getByName("127.0.0.1");
    try (MockWebServer server = startServer(reachableIpv4, "ipv4")) {
      FaultInjectingSocketFactory socketFactory = new FaultInjectingSocketFactory(unreachableIpv6);
      OkHttpClient client = happyEyeballsClient(socketFactory);
      OkHttpOrchestratorExternalHttpTransport transport =
          new OkHttpOrchestratorExternalHttpTransport(
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
  void connectsOverIpv6WhenIpv4CandidateIsUnreachable() throws Exception {
    InetAddress unreachableIpv4 = InetAddress.getByName("192.0.2.1");
    InetAddress reachableIpv6 = InetAddress.getByName("::1");
    try (MockWebServer server = startIpv6ServerOrSkip(reachableIpv6, "ipv6")) {
      FaultInjectingSocketFactory socketFactory = new FaultInjectingSocketFactory(unreachableIpv4);
      OkHttpClient client = happyEyeballsClient(socketFactory);
      OkHttpOrchestratorExternalHttpTransport transport =
          new OkHttpOrchestratorExternalHttpTransport(
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
  void usesFirstReachableAddressWhenBothIpFamiliesAreAvailable() throws Exception {
    InetAddress reachableIpv6 = InetAddress.getByName("::1");
    InetAddress reachableIpv4 = InetAddress.getByName("127.0.0.1");
    try (MockWebServer ipv6Server = startIpv6ServerOrSkip(reachableIpv6, "ipv6")) {
      int sharedPort = ipv6Server.getPort();
      try (MockWebServer ipv4Server = startServer(reachableIpv4, sharedPort, "ipv4")) {
        OkHttpClient client = OkHttpOrchestratorExternalHttpTransport.baseClient()
            .retryOnConnectionFailure(false)
            .build();
        OkHttpOrchestratorExternalHttpTransport transport =
            new OkHttpOrchestratorExternalHttpTransport(
                client, client, hostname -> List.of(reachableIpv6, reachableIpv4));

        OutboundHttpResponse response = transport.execute(request(sharedPort));

        assertThat(response.body()).isEqualTo("ipv6");
        assertThat(ipv6Server.getRequestCount()).isEqualTo(1);
        assertThat(ipv4Server.getRequestCount()).isZero();
      }
    }
  }

  @Test
  void productionGuardedClientKeepsSecurityAndHappyEyeballsPolicy() {
    OkHttpClient client = OkHttpOrchestratorExternalHttpTransport.guardedClient();

    assertThat(client.fastFallback()).isTrue();
    assertThat(client.followRedirects()).isFalse();
    assertThat(client.followSslRedirects()).isFalse();
    assertThat(client.retryOnConnectionFailure()).isFalse();
    assertThat(client.proxy()).isSameAs(Proxy.NO_PROXY);
  }

  @Test
  void guardedRequestRejectsPrivateIpLiteralBeforeNetworkCall() {
    OkHttpClient client = OkHttpOrchestratorExternalHttpTransport.baseClient()
        .addInterceptor(new RecordingInterceptor("unexpected"))
        .build();
    OkHttpOrchestratorExternalHttpTransport transport = new OkHttpOrchestratorExternalHttpTransport(
        client, client, DnsResolveGuard::resolveAllAndValidate);
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
  void guardedRequestUsesValidatedDnsSnapshotAndBypassesSystemProxy() throws Exception {
    InetAddress reachableIpv4 = InetAddress.getByName("127.0.0.1");
    AtomicInteger resolutions = new AtomicInteger();
    RecordingProxySelector proxySelector = new RecordingProxySelector();
    try (MockWebServer server = startServer(reachableIpv4, "direct")) {
      OkHttpClient client = OkHttpOrchestratorExternalHttpTransport.baseClient()
          .proxySelector(proxySelector)
          .build();
      OkHttpOrchestratorExternalHttpTransport transport =
          new OkHttpOrchestratorExternalHttpTransport(client, client, hostname -> {
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
    return OkHttpOrchestratorExternalHttpTransport.baseClient()
        .socketFactory(socketFactory)
        .build();
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
    private final String name;
    private boolean called;

    private RecordingInterceptor(String name) {
      this.name = name;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
      called = true;
      return new Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(ResponseBody.create(name, MediaType.get("text/plain; charset=utf-8")))
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
