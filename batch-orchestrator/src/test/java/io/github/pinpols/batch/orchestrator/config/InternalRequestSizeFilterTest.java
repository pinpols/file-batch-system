package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 缺口③:内部端点请求体大小上限过滤器单元测。 */
@ExtendWith(MockitoExtension.class)
class InternalRequestSizeFilterTest {

  @Mock private FilterChain chain;

  private InternalRequestSizeFilter filter(long maxBytes) {
    InternalRequestProperties props = new InternalRequestProperties();
    props.setMaxBodyBytes(maxBytes);
    return new InternalRequestSizeFilter(props);
  }

  private MockHttpServletRequest post(String uri, long contentLength) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
    req.setContentType("application/json");
    if (contentLength >= 0) {
      req.setContent(new byte[(int) contentLength]);
    }
    return req;
  }

  @Test
  @DisplayName("Content-Length 超限 → 413,不进 chain")
  void rejectsOversizedBodyWith413() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();

    filter(1024).doFilter(post("/internal/tasks/report", 4096), res, chain);

    assertThat(res.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    verify(chain, never())
        .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Content-Length 在上限内 → 放行")
  void passesBodyWithinLimit() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockHttpServletRequest req = post("/internal/tasks/report", 512);

    filter(1024).doFilter(req, res, chain);

    verify(chain).doFilter(req, res);
  }

  @Test
  @DisplayName("maxBodyBytes<=0(不限)→ 即便超大也放行")
  void unlimitedWhenMaxNonPositive() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockHttpServletRequest req = post("/internal/tasks/report", 8_000_000L);

    filter(0).doFilter(req, res, chain);

    verify(chain).doFilter(req, res);
  }

  @Test
  @DisplayName("Content-Length 缺失(chunked)→ 放行(无法预判大小)")
  void passesWhenContentLengthMissing() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockHttpServletRequest req = post("/internal/tasks/report", -1);

    filter(1024).doFilter(req, res, chain);

    verify(chain).doFilter(req, res);
  }

  @Test
  @DisplayName("非 /internal/** 路径 → 不拦(放行)")
  void passesNonInternalPath() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockHttpServletRequest req = post("/api/public/x", 4096);

    filter(1024).doFilter(req, res, chain);

    verify(chain).doFilter(req, res);
  }

  @Test
  @DisplayName("GET 方法 → 不拦(只管 POST/PUT)")
  void passesGetMethod() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/tasks/report");
    req.setContent(new byte[4096]);

    filter(1024).doFilter(req, res, chain);

    verify(chain).doFilter(req, res);
  }

  @Test
  @DisplayName("multipart 上传 → 不拦(走 Spring multipart 限制)")
  void passesMultipart() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/files/upload");
    req.setContentType("multipart/form-data; boundary=xyz");
    req.setContent(new byte[4096]);

    filter(1024).doFilter(req, res, chain);

    verify(chain).doFilter(req, res);
  }
}
