package io.github.pinpols.batch.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("出站请求构造:请求头防御性拷贝、方法形态与地址校验")
class OutboundHttpRequestTest {

  @Test
  @DisplayName("构造请求时拷贝请求头快照,外部后续修改不影响已建请求")
  void shouldCopyHeadersDefensively_whenBuildingTypedRequest() {
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
  @DisplayName("非超文本传输协议地址被拒绝并提示仅支持两种协议")
  void shouldRejectRequest_whenSchemeIsNotHttpOrHttps() {
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
  @DisplayName("缺少主机名的地址被拒绝并提示必须包含主机")
  void shouldRejectRequest_whenUriHasNoHost() {
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
  @DisplayName("超时非正数或表单提交缺少媒体类型时被拒绝")
  void shouldRejectRequest_whenTimeoutInvalidOrMediaTypeMissing() {
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
