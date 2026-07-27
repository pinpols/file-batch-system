package io.github.pinpols.batch.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Servlet 请求体 bounded 读取工具，专门兜住 chunked / 缺失 Content-Length 的内存边界。 */
public final class BoundedRequestBodyReader {

  private static final int BUFFER_SIZE = 8192;

  private BoundedRequestBodyReader() {}

  public static CachedBodyHttpServletRequest readAndCache(
      HttpServletRequest request, long maxBytes, String purpose) throws IOException {
    byte[] body = read(request.getInputStream(), maxBytes, purpose);
    return new CachedBodyHttpServletRequest(request, body);
  }

  public static byte[] read(InputStream inputStream, long maxBytes, String purpose)
      throws IOException {
    if (maxBytes <= 0) {
      return inputStream.readAllBytes();
    }
    ByteArrayOutputStream output =
        new ByteArrayOutputStream((int) Math.min(maxBytes, (long) BUFFER_SIZE));
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0L;
    while (true) {
      int n = inputStream.read(buffer);
      if (n < 0) {
        return output.toByteArray();
      }
      total += n;
      if (total > maxBytes) {
        throw new RequestBodyLimitExceededException(purpose, maxBytes);
      }
      output.write(buffer, 0, n);
    }
  }

  public static final class RequestBodyLimitExceededException extends IOException {
    private static final long serialVersionUID = 1L;

    public RequestBodyLimitExceededException(String purpose, long maxBytes) {
      super(purpose + " request body exceeds configured limit: " + maxBytes + " bytes");
    }
  }
}
