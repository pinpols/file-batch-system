package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConsoleInternalRequestSizeFilterTest {

  private ConsoleInternalRequestSizeFilter filter(long maxBytes) {
    ConsoleInternalRequestProperties props = new ConsoleInternalRequestProperties();
    props.setMaxBodyBytes(maxBytes);
    return new ConsoleInternalRequestSizeFilter(props);
  }

  private MockHttpServletRequest amPost(int contentLength) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/am-notify/ops");
    request.setContentType("application/json");
    request.setContent(new byte[contentLength]);
    return request;
  }

  @Test
  void rejectsWith413_whenBodyExceedsLimit() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(1024).doFilter(amPost(2048), response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    // 未放行到下游(chain 未被调用)。
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void passesThrough_whenBodyWithinLimit() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(4096).doFilter(amPost(2048), response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void passesThrough_whenContentLengthUnknown() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/am-notify/ops");
    request.setContentType("application/json");
    // 不设置 Content-Length(chunked) → 无法预判,放行。

    filter(1024).doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void ignoresNonInternalPaths() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/anything");
    request.setContent(new byte[1_000_000]);

    filter(1024).doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void ignoresGetRequests() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/am-notify/ops");
    request.setContent(new byte[1_000_000]);

    filter(1024).doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void unlimited_whenMaxBytesNonPositive() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(0).doFilter(amPost(10_000_000), response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }
}
