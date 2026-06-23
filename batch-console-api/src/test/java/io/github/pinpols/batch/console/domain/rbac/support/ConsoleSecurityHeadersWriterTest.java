package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConsoleSecurityHeadersWriterTest {

  @Test
  void shouldWriteSecurityHeaders() {
    ConsoleSecurityHeadersWriter writer = new ConsoleSecurityHeadersWriter();
    HttpServletRequest request = new MockHttpServletRequest("GET", "/api/console/ops/summary");
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.writeHeaders(request, response);

    assertThat(response.getHeader("Content-Security-Policy"))
        .contains("default-src 'none'")
        .contains("frame-ancestors 'none'");
    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(response.getHeader("Permissions-Policy")).contains("camera=()");
    // HSTS 已提到 63072000s（2 年）+ includeSubDomains + preload（提交 hstspreload.org 的最低要求）
    assertThat(response.getHeader("Strict-Transport-Security"))
        .contains("max-age=63072000")
        .contains("includeSubDomains")
        .contains("preload");
  }
}
