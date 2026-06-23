package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class PlatformHttpExceptionTest {

  @Test
  void shouldClassify401And403AsAuthError() {
    assertThat(new PlatformHttpException(401, "x").isAuthError()).isTrue();
    assertThat(new PlatformHttpException(403, "x").isAuthError()).isTrue();
    assertThat(new PlatformHttpException(409, "x").isAuthError()).isFalse();
    assertThat(new PlatformHttpException(500, "x").isAuthError()).isFalse();
  }

  @Test
  void shouldClassify409AsConflict() {
    assertThat(new PlatformHttpException(409, "x").isConflict()).isTrue();
    assertThat(new PlatformHttpException(400, "x").isConflict()).isFalse();
  }

  @Test
  void shouldClassify5xxAsServerError() {
    assertThat(new PlatformHttpException(500, "x").isServerError()).isTrue();
    assertThat(new PlatformHttpException(503, "x").isServerError()).isTrue();
    assertThat(new PlatformHttpException(599, "x").isServerError()).isTrue();
    assertThat(new PlatformHttpException(499, "x").isServerError()).isFalse();
    assertThat(new PlatformHttpException(600, "x").isServerError()).isFalse();
  }

  @Test
  void shouldClassify4xxAsClientError() {
    assertThat(new PlatformHttpException(400, "x").isClientError()).isTrue();
    assertThat(new PlatformHttpException(404, "x").isClientError()).isTrue();
    assertThat(new PlatformHttpException(499, "x").isClientError()).isTrue();
    assertThat(new PlatformHttpException(500, "x").isClientError()).isFalse();
  }

  @Test
  void shouldBeIOExceptionSubclassForBackCompat() {
    // PlatformHttpException 必须能被 catch(IOException)接住,旧调用方不破坏
    PlatformHttpException ex = new PlatformHttpException(503, "boom");
    assertThat(ex).isInstanceOf(IOException.class);
    assertThat(ex.statusCode()).isEqualTo(503);
    assertThat(ex.getMessage()).isEqualTo("boom");
  }
}
