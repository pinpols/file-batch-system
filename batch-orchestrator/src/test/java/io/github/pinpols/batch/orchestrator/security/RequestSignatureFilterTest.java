package io.github.pinpols.batch.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.security.RequestSignatureVerifier.Result;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class RequestSignatureFilterTest {

  @Mock private RequestSignatureVerifier verifier;
  @Mock private FilterChain chain;

  private RequestSignatureFilter newFilter(boolean enabled) {
    RequestSigningProperties props = new RequestSigningProperties();
    props.setEnabled(enabled);
    return new RequestSignatureFilter(props, verifier);
  }

  private MockHttpServletRequest post(boolean withApiKey) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/tasks/10/report");
    req.setContent("{\"tenantId\":\"t1\"}".getBytes());
    if (withApiKey) {
      req.addHeader("X-Batch-Api-Key", "key-1");
      req.addHeader("X-Batch-Timestamp", "1700000000000");
      req.addHeader("X-Batch-Nonce", "n1");
      req.addHeader("X-Batch-Signature", "sig");
    }
    return req;
  }

  @Test
  @DisplayName("开关关闭 → 直通,不验签")
  void disabledPassthrough() throws Exception {
    newFilter(false).doFilter(post(true), new MockHttpServletResponse(), chain);
    verify(chain).doFilter(any(), any());
    verifyNoInteractions(verifier);
  }

  @Test
  @DisplayName("无 api_key(内部 secret 调用)→ 直通")
  void noApiKeyPassthrough() throws Exception {
    newFilter(true).doFilter(post(false), new MockHttpServletResponse(), chain);
    verify(chain).doFilter(any(), any());
    verifyNoInteractions(verifier);
  }

  @Test
  @DisplayName("GET 读请求 → 直通")
  void getPassthrough() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/tasks/10/claimed");
    req.addHeader("X-Batch-Api-Key", "key-1");
    newFilter(true).doFilter(req, new MockHttpServletResponse(), chain);
    verify(chain).doFilter(any(), any());
    verifyNoInteractions(verifier);
  }

  @Test
  @DisplayName("启用+api_key+写方法+验签 OK → 放行")
  void validProceeds() throws Exception {
    when(verifier.verify(any(), anyLong())).thenReturn(Result.OK);
    newFilter(true).doFilter(post(true), new MockHttpServletResponse(), chain);
    verify(chain).doFilter(any(), any());
  }

  @Test
  @DisplayName("验签失败 → 401,不放行")
  void invalidRejected() throws Exception {
    when(verifier.verify(any(), anyLong())).thenReturn(Result.BAD_SIGNATURE);
    MockHttpServletResponse resp = new MockHttpServletResponse();
    newFilter(true).doFilter(post(true), resp, chain);
    assertThat(resp.getStatus()).isEqualTo(401);
    verify(chain, never()).doFilter(any(), any());
  }
}
