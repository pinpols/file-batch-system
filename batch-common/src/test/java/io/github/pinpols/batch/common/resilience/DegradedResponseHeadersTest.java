package io.github.pinpols.batch.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class DegradedResponseHeadersTest {

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void marksAndDeduplicatesFallbackSourcesOnCurrentResponse() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.isCommitted()).thenReturn(false);
    when(response.getHeader(anyString())).thenReturn("trigger");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

    DegradedResponseHeaders.mark("orchestrator");

    verify(response).setHeader("X-Degraded-Source", "trigger,orchestrator");
  }

  @Test
  void ignoresInvalidSourceWithoutWebRequest() {
    DegradedResponseHeaders.mark("\r\n");
    assertThat(RequestContextHolder.getRequestAttributes()).isNull();
  }
}
