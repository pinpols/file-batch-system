package io.github.pinpols.batch.orchestrator.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 缓存请求体的包装器：签名 filter 已读尽 InputStream 算摘要，下游 controller 仍需可重新读取同一份字节。 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

  private final byte[] cachedBody;

  public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
    super(request);
    this.cachedBody = cachedBody;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream buffer = new ByteArrayInputStream(cachedBody);
    return new ServletInputStream() {
      @Override
      public int read() {
        return buffer.read();
      }

      @Override
      public boolean isFinished() {
        return buffer.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(
        new InputStreamReader(new ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8));
  }
}
