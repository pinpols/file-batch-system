package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("控制台原请求幂等身份计算")
class ConsoleIdempotencyRequestIdentityTest {

  @Test
  @DisplayName("请求身份含首尾空白时保留既有存储键")
  void shouldMatchExistingKeys_whenRequestIdentityContainsWhitespace() {
    String tenant = " tenant-a ";
    String method = "post";
    String uri = "/api/console/jobs/demo";
    String key = " post-key ";

    assertThat(ConsoleIdempotencyRequestIdentity.tenantId(tenant)).isEqualTo("tenant-a");
    assertThat(ConsoleIdempotencyRequestIdentity.redisKey(tenant, method, uri, key))
        .isEqualTo("console:idempotency:tenant-a:POST:/api/console/jobs/demo:post-key");
    assertThat(ConsoleIdempotencyRequestIdentity.durableKey(tenant, method, uri, key))
        .isEqualTo("console-http:010493ad175a6509900c17679d2a3563f623c5488e766e9b950da7d9681e3d99");
    assertThat(ConsoleIdempotencyRequestIdentity.requestHash(tenant, method, uri, key))
        .isEqualTo("010493ad175a6509900c17679d2a3563f623c5488e766e9b950da7d9681e3d99");
  }

  @Test
  @DisplayName("租户头为空时保持既有 UTF-8 摘要")
  void shouldMatchExistingUtf8Digest_whenTenantHeaderIsBlank() {
    assertThat(ConsoleIdempotencyRequestIdentity.tenantId(null)).isEqualTo("_");
    assertThat(ConsoleIdempotencyRequestIdentity.tenantId("\u2003")).isEqualTo("_");
    assertThat(ConsoleIdempotencyRequestIdentity.redisKey("  ", "POST", "/api/console/租户", "键"))
        .isEqualTo("console:idempotency:_:POST:/api/console/租户:键");
    assertThat(ConsoleIdempotencyRequestIdentity.durableKey(null, "POST", "/api/console/租户", "键"))
        .isEqualTo("console-http:894184b660c9693592b7bd3c0f5ba5137aa6ca088d38fdaa3e38d96a03d8753a");
  }

  @Test
  @DisplayName("回查身份使用原请求方法、路径及租户")
  void shouldUseOriginalMethodAndUri_whenCalculatingRecoveryIdentity() {
    String original = ConsoleIdempotencyRequestIdentity.durableKey(
        "tenant-a", "POST", "/api/console/jobs/demo", "post-key");

    assertThat(ConsoleIdempotencyRequestIdentity.durableKey(
            "tenant-a", "GET", "/api/console/jobs/demo", "post-key"))
        .isNotEqualTo(original);
    assertThat(ConsoleIdempotencyRequestIdentity.durableKey(
            "tenant-a", "POST", "/api/console/jobs/other", "post-key"))
        .isNotEqualTo(original);
    assertThat(ConsoleIdempotencyRequestIdentity.durableKey(
            "tenant-b", "POST", "/api/console/jobs/demo", "post-key"))
        .isNotEqualTo(original);
  }
}
