package io.github.pinpols.batch.orchestrator.infrastructure.http;

import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.security.DnsResolveGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Orchestrator 的 OkHttp 出站适配器。 */
@Component
@OrchestratorOutboundTransport
public class OkHttpOrchestratorExternalHttpTransport implements OutboundHttpTransport {

  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

  private final OkHttpClient guardedClient;
  private final OkHttpClient trustedClient;
  private final Dns guardedDns;

  @Autowired
  public OkHttpOrchestratorExternalHttpTransport() {
    this(guardedClient(), trustedClient(), DnsResolveGuard::resolveAllAndValidate);
  }

  OkHttpOrchestratorExternalHttpTransport(OkHttpClient guardedClient, OkHttpClient trustedClient) {
    this(guardedClient, trustedClient, DnsResolveGuard::resolveAllAndValidate);
  }

  OkHttpOrchestratorExternalHttpTransport(
      OkHttpClient guardedClient, OkHttpClient trustedClient, Dns guardedDns) {
    this.guardedClient = guardedClient;
    this.trustedClient = trustedClient;
    this.guardedDns = guardedDns;
  }

  @Override
  public OutboundHttpResponse execute(OutboundHttpRequest request) throws IOException {
    boolean guarded = request.addressPolicy() == OutboundAddressPolicy.GUARDED;
    OkHttpClient base = guarded ? guardedClient : trustedClient;
    OkHttpClient.Builder clientBuilder = base.newBuilder()
        .connectTimeout(request.connectTimeout())
        .readTimeout(request.requestTimeout())
        .writeTimeout(request.requestTimeout())
        .callTimeout(request.requestTimeout());
    if (guarded) {
      applyGuardedRoute(clientBuilder, request);
    }
    OkHttpClient client = clientBuilder.build();

    Request.Builder requestBuilder = new Request.Builder().url(request.uri().toString());
    request.headers().forEach(requestBuilder::header);
    applyMethod(requestBuilder, request);
    try (Response response = client.newCall(requestBuilder.build()).execute()) {
      String body = readBoundedBody(response);
      return new OutboundHttpResponse(response.code(), body);
    }
  }

  private static void applyMethod(Request.Builder builder, OutboundHttpRequest request) {
    switch (request.method()) {
      case GET -> builder.get();
      case HEAD -> builder.head();
      case POST ->
        builder.post(RequestBody.create(
            EmptyChecks.isNull(request.body()) ? "" : request.body(),
            MediaType.get(request.mediaType())));
    }
  }

  private static String readBoundedBody(Response response) throws IOException {
    ResponseBody responseBody = response.body();
    byte[] bytes = responseBody.byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
    if (bytes.length > MAX_RESPONSE_BYTES) {
      throw new IOException("outbound HTTP response exceeds " + MAX_RESPONSE_BYTES + " bytes");
    }
    MediaType contentType = responseBody.contentType();
    Charset charset = EmptyChecks.isNull(contentType)
        ? StandardCharsets.UTF_8
        : contentType.charset(StandardCharsets.UTF_8);
    return new String(bytes, charset);
  }

  private void applyGuardedRoute(OkHttpClient.Builder clientBuilder, OutboundHttpRequest request)
      throws UnknownHostException {
    String requestHost = request.uri().getHost();
    List<InetAddress> validatedAddresses = List.copyOf(guardedDns.lookup(requestHost));
    if (EmptyChecks.isEmpty(validatedAddresses)) {
      throw new UnknownHostException("guarded DNS returned no address for " + requestHost);
    }
    clientBuilder
        // 系统代理可能在代理端重新解析目标域名，无法保证校验地址就是连接地址。
        .proxy(Proxy.NO_PROXY)
        .dns(hostname ->
            sameHost(requestHost, hostname) ? validatedAddresses : guardedDns.lookup(hostname));
  }

  private static boolean sameHost(String left, String right) {
    return stripIpv6Brackets(left).equalsIgnoreCase(stripIpv6Brackets(right));
  }

  private static String stripIpv6Brackets(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  static OkHttpClient guardedClient() {
    return baseClient().proxy(Proxy.NO_PROXY).build();
  }

  static OkHttpClient trustedClient() {
    return baseClient().dns(Dns.SYSTEM).build();
  }

  static OkHttpClient.Builder baseClient() {
    return new OkHttpClient.Builder()
        .fastFallback(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false);
  }
}
