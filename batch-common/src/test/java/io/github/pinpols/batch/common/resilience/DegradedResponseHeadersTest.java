package io.github.pinpols.batch.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayName("降级响应头标记:已有来源的合并去重,非法来源与无请求上下文时的静默忽略")
class DegradedResponseHeadersTest {

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("响应未提交且已有降级来源时再次标记:合并去重后写回响应头")
  void shouldMergeAndDeduplicateFallbackSourcesOnCurrentResponse() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.isCommitted()).thenReturn(false);
    when(response.getHeader(anyString())).thenReturn("trigger");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

    DegradedResponseHeaders.mark("orchestrator");

    verify(response).setHeader("X-Degraded-Source", "trigger,orchestrator");
  }

  @Test
  @DisplayName("来源含非法字符或当前无请求上下文:不写响应头也不创建上下文")
  void shouldIgnoreInvalidSource_whenNoWebRequestContext() {
    DegradedResponseHeaders.mark("\r\n");
    assertThat(RequestContextHolder.getRequestAttributes()).isNull();
  }
}
