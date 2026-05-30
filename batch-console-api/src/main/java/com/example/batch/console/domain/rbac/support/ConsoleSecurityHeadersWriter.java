package com.example.batch.console.domain.rbac.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.stereotype.Component;

@Component
public class ConsoleSecurityHeadersWriter implements HeaderWriter {

  private static final String CONTENT_SECURITY_POLICY =
      "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'; "
          + "img-src 'self' data:; connect-src 'self'; style-src 'self' 'unsafe-inline'; "
          + "script-src 'none'; object-src 'none';";
  private static final String REFERRER_POLICY = "no-referrer";
  private static final String PERMISSIONS_POLICY =
      "camera=(), microphone=(), geolocation=(), payment=(), usb=(), fullscreen=()";
  // 63072000s = 2 年；preload 让浏览器在首次访问前也强制 HTTPS（需网站实际全站 HTTPS 才能提交
  // 到 https://hstspreload.org/ 登记）。反代卸 TLS 时确保 X-Forwarded-Proto=https 透传才生效。
  private static final String HSTS = "max-age=63072000; includeSubDomains; preload";

  @Override
  public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", REFERRER_POLICY);
    response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
    response.setHeader("Strict-Transport-Security", HSTS);
  }
}
