package io.github.pinpols.batch.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundHttpRequestTest {

  @Test
  void copiesHeadersAndUsesTypedMethod() {
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Test", "one");

    OutboundHttpRequest request = OutboundHttpRequest.post(
        "https://example.test/hook",
        headers,
        "{}",
        "application/json",
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        OutboundAddressPolicy.GUARDED);
    headers.put("X-Test", "changed");

    assertThat(request.method()).isEqualTo(OutboundHttpMethod.POST);
    assertThat(request.headers()).containsEntry("X-Test", "one");
    assertThat(request.toString())
        .contains("https://example.test/hook", "headerNames=[X-Test]", "bodyLength=2")
        .doesNotContain("one", "\"ok\"");
    Map<String, String> copiedHeaders = request.headers();
    assertThatThrownBy(() -> copiedHeaders.put("X-Other", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNonHttpScheme() {
    Map<String, String> headers = Map.of();
    Duration connectTimeout = Duration.ofSeconds(1);
    Duration requestTimeout = Duration.ofSeconds(2);

    assertThatThrownBy(() -> OutboundHttpRequest.get(
            "file:///tmp/data",
            headers,
            connectTimeout,
            requestTimeout,
            OutboundAddressPolicy.TRUSTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http or https");
  }

  @Test
  void rejectsUriWithoutHost() {
    Map<String, String> headers = Map.of();
    Duration connectTimeout = Duration.ofSeconds(1);
    Duration requestTimeout = Duration.ofSeconds(2);

    assertThatThrownBy(() -> OutboundHttpRequest.get(
            "https:/missing-host",
            headers,
            connectTimeout,
            requestTimeout,
            OutboundAddressPolicy.GUARDED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contain a host");
  }

  @Test
  void rejectsInvalidTimeoutAndMissingPostMediaType() {
    Map<String, String> headers = Map.of();
    Duration requestTimeout = Duration.ofSeconds(2);

    assertThatThrownBy(() -> OutboundHttpRequest.get(
            "https://example.test",
            headers,
            Duration.ZERO,
            requestTimeout,
            OutboundAddressPolicy.TRUSTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeouts");

    Duration connectTimeout = Duration.ofSeconds(1);
    assertThatThrownBy(() -> OutboundHttpRequest.post(
            "https://example.test",
            headers,
            "{}",
            null,
            connectTimeout,
            requestTimeout,
            OutboundAddressPolicy.TRUSTED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mediaType");
  }
}
