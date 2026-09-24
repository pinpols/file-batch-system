package io.github.pinpols.batch.testing;

import io.github.pinpols.batch.common.http.OutboundHttpTransport;

/** 测试专用 HTTP transport 工厂。 */
public final class TestHttpTransports {

  private TestHttpTransports() {}

  /** 返回禁止网络调用的 transport；纯映射测试一旦意外发请求会立即失败。 */
  public static OutboundHttpTransport failOnRequest() {
    return request -> {
      throw new AssertionError("unexpected outbound HTTP request: " + request);
    };
  }
}
