package com.example.batch.worker.imports.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.config.BatchSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("import worker 内部接口共享密钥过滤器")
class ImportInternalAuthFilterTest {

  private static final String SECRET = "strong-internal-secret-0001";

  private BatchSecurityProperties props(boolean bypass) {
    BatchSecurityProperties p = new BatchSecurityProperties();
    p.setBypassMode(bypass);
    p.setInternalSecret(SECRET);
    return p;
  }

  private MockHttpServletRequest internalRequest(String secretHeader) {
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/internal/import/events/object-arrival");
    req.setRequestURI("/internal/import/events/object-arrival");
    if (secretHeader != null) {
      req.addHeader("X-Internal-Secret", secretHeader);
    }
    return req;
  }

  @Test
  @DisplayName("正确密钥 → 放行(链继续)")
  void shouldPass_whenSecretMatches() throws Exception {
    // arrange
    ImportInternalAuthFilter filter = new ImportInternalAuthFilter(props(false));
    MockHttpServletRequest req = internalRequest(SECRET);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    // act
    filter.doFilter(req, res, chain);

    // assert —— 链已走到底(MockFilterChain 记录了 request)
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  @DisplayName("缺密钥 → 401,且不进入业务链")
  void shouldReject_whenSecretMissing() throws Exception {
    // arrange
    ImportInternalAuthFilter filter = new ImportInternalAuthFilter(props(false));
    MockHttpServletRequest req = internalRequest(null);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    // act
    filter.doFilter(req, res, chain);

    // assert
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  @DisplayName("错误密钥 → 401")
  void shouldReject_whenSecretWrong() throws Exception {
    // arrange
    ImportInternalAuthFilter filter = new ImportInternalAuthFilter(props(false));
    MockHttpServletRequest req = internalRequest("wrong-secret");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    // act
    filter.doFilter(req, res, chain);

    // assert
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  @DisplayName("bypass-mode 开启 → 即便无密钥也放行")
  void shouldPass_whenBypassMode() throws Exception {
    // arrange
    ImportInternalAuthFilter filter = new ImportInternalAuthFilter(props(true));
    MockHttpServletRequest req = internalRequest(null);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    // act
    filter.doFilter(req, res, chain);

    // assert
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }

  @Test
  @DisplayName("非 /internal/ 路径 → 不拦截,直接放行")
  void shouldIgnore_whenNotInternalPath() throws Exception {
    // arrange
    ImportInternalAuthFilter filter = new ImportInternalAuthFilter(props(false));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
    req.setRequestURI("/actuator/health");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    // act
    filter.doFilter(req, res, chain);

    // assert
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(req);
  }
}
