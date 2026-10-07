package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformHttpException — 状态码分类与既有调用方兼容性")
class PlatformHttpExceptionTest {

  @Test
  @DisplayName("401 与 403 归类为鉴权错误,其余状态码不算")
  void shouldClassify401And403AsAuthError() {
    assertThat(new PlatformHttpException(401, "x").isAuthError()).isTrue();
    assertThat(new PlatformHttpException(403, "x").isAuthError()).isTrue();
    assertThat(new PlatformHttpException(409, "x").isAuthError()).isFalse();
    assertThat(new PlatformHttpException(500, "x").isAuthError()).isFalse();
  }

  @Test
  @DisplayName("409 归类为冲突,其余状态码不算")
  void shouldClassify409AsConflict() {
    assertThat(new PlatformHttpException(409, "x").isConflict()).isTrue();
    assertThat(new PlatformHttpException(400, "x").isConflict()).isFalse();
  }

  @Test
  @DisplayName("5xx 归类为服务端错误,区间外状态码不算")
  void shouldClassify5xxAsServerError() {
    assertThat(new PlatformHttpException(500, "x").isServerError()).isTrue();
    assertThat(new PlatformHttpException(503, "x").isServerError()).isTrue();
    assertThat(new PlatformHttpException(599, "x").isServerError()).isTrue();
    assertThat(new PlatformHttpException(499, "x").isServerError()).isFalse();
    assertThat(new PlatformHttpException(600, "x").isServerError()).isFalse();
  }

  @Test
  @DisplayName("4xx 归类为客户端错误,5xx 不算")
  void shouldClassify4xxAsClientError() {
    assertThat(new PlatformHttpException(400, "x").isClientError()).isTrue();
    assertThat(new PlatformHttpException(404, "x").isClientError()).isTrue();
    assertThat(new PlatformHttpException(499, "x").isClientError()).isTrue();
    assertThat(new PlatformHttpException(500, "x").isClientError()).isFalse();
  }

  @Test
  @DisplayName("异常仍属输入输出异常子类,状态码与消息原样保留以兼容旧调用方")
  void shouldBeIOExceptionSubclassForBackCompat() {
    // PlatformHttpException 必须能被 catch(IOException)接住,旧调用方不破坏
    PlatformHttpException ex = new PlatformHttpException(503, "boom");
    assertThat(ex).isInstanceOf(IOException.class);
    assertThat(ex.statusCode()).isEqualTo(503);
    assertThat(ex.getMessage()).isEqualTo("boom");
  }
}
