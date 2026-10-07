package io.github.pinpols.batch.console.support.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.security.BlockedAddressException;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("外部出站 HTTP 传输: 受控客户端选择与请求响应映射")
class OkHttpConsoleExternalHttpTransportTest {

  @Test
  @DisplayName("受控策略下由受控客户端发出请求, 方法、头部、请求体与连接读超时按要求映射")
  void shouldMapPostAndSelectGuardedClient_whenPolicyIsGuarded() throws Exception {
    RecordingInterceptor guarded = new RecordingInterceptor("guarded", "accepted");
    RecordingInterceptor trusted = new RecordingInterceptor("trusted", "unused");
    OkHttpConsoleExternalHttpTransport transport = new OkHttpConsoleExternalHttpTransport(
        client(guarded), client(trusted), hostname -> List.of(InetAddress.getLoopbackAddress()));

    OutboundHttpResponse response = transport.execute(OutboundHttpRequest.post(
        "https://example.test/notify",
        Map.of("X-Test", "value"),
        "{\"ok\":true}",
        "application/json; charset=utf-8",
        Duration.ofSeconds(2),
        Duration.ofSeconds(3),
        OutboundAddressPolicy.GUARDED));

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(response.body()).isEqualTo("accepted");
    assertThat(guarded.method).isEqualTo("POST");
    assertThat(guarded.header).isEqualTo("value");
    assertThat(guarded.body).isEqualTo("{\"ok\":true}");
    assertThat(guarded.connectTimeoutMillis).isEqualTo(2000);
    assertThat(guarded.readTimeoutMillis).isEqualTo(3000);
    assertThat(trusted.method).isNull();
  }

  @Test
  @DisplayName("生产受控客户端启用快速回退, 并关闭重定向、安全重定向与连接失败重试且不使用代理")
  void shouldKeepSecurityOptions_whenBuildingGuardedClient() {
    OkHttpClient client = OkHttpConsoleExternalHttpTransport.guardedClient();

    assertThat(client.fastFallback()).isTrue();
    assertThat(client.followRedirects()).isFalse();
    assertThat(client.followSslRedirects()).isFalse();
    assertThat(client.retryOnConnectionFailure()).isFalse();
    assertThat(client.proxy()).isSameAs(Proxy.NO_PROXY);
  }

  @Test
  @DisplayName("受控策略下请求内网地址字面量时在建连前即被拒绝")
  void shouldRejectPrivateIpLiteral_whenPolicyIsGuarded() {
    OkHttpClient client = client(new RecordingInterceptor("unexpected", "unexpected"));
    OkHttpConsoleExternalHttpTransport transport = new OkHttpConsoleExternalHttpTransport(
        client, client, DnsResolveGuard::resolveAllAndValidate);
    OutboundHttpRequest request = OutboundHttpRequest.get(
        "http://127.0.0.1/internal",
        Map.of(),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.GUARDED);

    assertThatThrownBy(() -> transport.execute(request))
        .isInstanceOf(BlockedAddressException.class);
  }

  @Test
  @DisplayName("受信策略下响应体超出上限时抛出带超限提示的异常")
  void shouldRejectOversizedResponse_whenBodyExceedsLimit() {
    RecordingInterceptor trusted = new RecordingInterceptor("trusted", "x".repeat(1024 * 1024 + 1));
    OkHttpConsoleExternalHttpTransport transport = new OkHttpConsoleExternalHttpTransport(
        client(new RecordingInterceptor("g", "")), client(trusted));

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

  private static OkHttpClient client(Interceptor interceptor) {
    return new OkHttpClient.Builder().addInterceptor(interceptor).build();
  }

  private static final class RecordingInterceptor implements Interceptor {
    private final String name;
    private final String responseBody;
    private String method;
    private String header;
    private String body;
    private int connectTimeoutMillis;
    private int readTimeoutMillis;

    private RecordingInterceptor(String name, String responseBody) {
      this.name = name;
      this.responseBody = responseBody;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
      method = chain.request().method();
      header = chain.request().header("X-Test");
      connectTimeoutMillis = chain.connectTimeoutMillis();
      readTimeoutMillis = chain.readTimeoutMillis();
      if (chain.request().body() != null) {
        Buffer buffer = new Buffer();
        chain.request().body().writeTo(buffer);
        body = buffer.readUtf8();
      }
      return new Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(202)
          .message(name)
          .body(ResponseBody.create(responseBody, MediaType.get("text/plain; charset=utf-8")))
          .build();
    }
  }
}
