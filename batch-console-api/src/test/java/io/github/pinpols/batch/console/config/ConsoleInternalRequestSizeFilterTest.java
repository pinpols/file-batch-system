package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("内部回调请求体积过滤器: 超限拒绝,未知长度兜底与路径方法白名单")
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
  @DisplayName("请求体超过上限时,返回请求过大状态且不放行到下游")
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
  @DisplayName("请求体在上限之内时,状态正常并放行到下游")
  void passesThrough_whenBodyWithinLimit() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(4096).doFilter(amPost(2048), response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  @DisplayName("未声明内容长度但实际读取超限时,仍然拒绝并不放行")
  void rejects_whenContentLengthUnknownButActualBodyExceedsLimit() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = chunkedPost(2048);

    filter(1024).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  @DisplayName("未声明内容长度且实际读取在上限内时,正常放行")
  void passesThrough_whenContentLengthUnknownAndActualBodyWithinLimit() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = chunkedPost(512);

    filter(1024).doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  @DisplayName("补丁类请求体超限时,按同一口径拒绝并不放行")
  void rejectsPatchRequests_whenBodyExceedsLimit() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/internal/am-notify/ops");
    request.setContentType("application/json");
    request.setContent(new byte[2048]);

    filter(1024).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  @DisplayName("非内部路径不参与体积校验,超大请求体同样放行")
  void shouldPassThrough_whenRequestPathIsNotInternal() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/anything");
    request.setContent(new byte[1_000_000]);

    filter(1024).doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  @DisplayName("查询类请求不参与体积校验,超大请求体同样放行")
  void shouldPassThrough_whenRequestMethodIsGet() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/am-notify/ops");
    request.setContent(new byte[1_000_000]);

    filter(1024).doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  @DisplayName("上限配置为非正数时,视为不限制体积并直接放行")
  void unlimited_whenMaxBytesNonPositive() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(0).doFilter(amPost(10_000_000), response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  private MockHttpServletRequest chunkedPost(int actualBytes) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/am-notify/ops") {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1L;
      }
    };
    request.setContentType("application/json");
    request.setContent(new byte[actualBytes]);
    return request;
  }
}
