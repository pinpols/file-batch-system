package io.github.pinpols.batch.common.http;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** 与具体 HTTP 客户端无关的出站请求描述。 */
public record OutboundHttpRequest(
    URI uri,
    OutboundHttpMethod method,
    Map<String, String> headers,
    String body,
    String mediaType,
    Duration connectTimeout,
    Duration requestTimeout,
    OutboundAddressPolicy addressPolicy) {

  public OutboundHttpRequest {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(addressPolicy, "addressPolicy");
    if (connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.isZero()) {
      throw new IllegalArgumentException("outbound HTTP timeouts must be positive");
    }
    if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("outbound HTTP URI must use http or https");
    }
    if (EmptyChecks.isBlank(uri.getHost())) {
      throw new IllegalArgumentException("outbound HTTP URI must contain a host");
    }
    if (method == OutboundHttpMethod.POST && EmptyChecks.isBlank(mediaType)) {
      throw new IllegalArgumentException("outbound HTTP POST requires mediaType");
    }
    headers = EmptyChecks.isNull(headers) ? Map.of() : Map.copyOf(headers);
  }

  public static OutboundHttpRequest get(
      String url,
      Map<String, String> headers,
      Duration connectTimeout,
      Duration requestTimeout,
      OutboundAddressPolicy addressPolicy) {
    return new OutboundHttpRequest(
        URI.create(url),
        OutboundHttpMethod.GET,
        headers,
        null,
        null,
        connectTimeout,
        requestTimeout,
        addressPolicy);
  }

  public static OutboundHttpRequest head(
      String url,
      Map<String, String> headers,
      Duration connectTimeout,
      Duration requestTimeout,
      OutboundAddressPolicy addressPolicy) {
    return new OutboundHttpRequest(
        URI.create(url),
        OutboundHttpMethod.HEAD,
        headers,
        null,
        null,
        connectTimeout,
        requestTimeout,
        addressPolicy);
  }

  public static OutboundHttpRequest post(
      String url,
      Map<String, String> headers,
      String body,
      String mediaType,
      Duration connectTimeout,
      Duration requestTimeout,
      OutboundAddressPolicy addressPolicy) {
    return new OutboundHttpRequest(
        URI.create(url),
        OutboundHttpMethod.POST,
        headers,
        body,
        mediaType,
        connectTimeout,
        requestTimeout,
        addressPolicy);
  }

  @Override
  public String toString() {
    String authority = uri.getHost();
    String path = EmptyChecks.isBlank(uri.getPath()) ? "/" : uri.getPath();
    return "OutboundHttpRequest[method="
        + method
        + ", uri="
        + uri.getScheme()
        + "://"
        + authority
        + path
        + ", headerNames="
        + headers.keySet()
        + ", bodyLength="
        + (EmptyChecks.isNull(body) ? 0 : body.length())
        + ", addressPolicy="
        + addressPolicy
        + ']';
  }
}
