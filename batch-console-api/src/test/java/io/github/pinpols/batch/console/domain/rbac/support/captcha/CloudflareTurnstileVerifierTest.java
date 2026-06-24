package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Cloudflare Turnstile 校验:成功 / 失败 / 空 token 不走网络")
class CloudflareTurnstileVerifierTest {

  private CaptchaProperties properties;

  /** 子类覆盖 postForm 返回预置 JSON,无网络验证 verify 各分支;同时记录 postForm 调用次数。 */
  private static final class StubVerifier extends CloudflareTurnstileVerifier {
    private final String cannedJson;
    final AtomicInteger postCalls = new AtomicInteger();

    StubVerifier(CaptchaProperties properties, String cannedJson) {
      super(properties, new ObjectMapper());
      this.cannedJson = cannedJson;
    }

    @Override
    protected String postForm(String url, String body) {
      postCalls.incrementAndGet();
      return cannedJson;
    }
  }

  @BeforeEach
  void setUp() {
    properties = new CaptchaProperties();
    properties.setProvider("cloudflare");
    properties.setSecretKey("test-secret");
  }

  @Test
  @DisplayName("success=true → 通过")
  void successResponse_passes() {
    StubVerifier verifier = new StubVerifier(properties, "{\"success\":true}");

    CaptchaResult result = verifier.verify("good-token", "1.2.3.4");

    assertThat(result.success()).isTrue();
    assertThat(verifier.postCalls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("success=false 带 error-codes → 失败,reason 含错误码")
  void failureResponse_fails() {
    StubVerifier verifier =
        new StubVerifier(
            properties, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

    CaptchaResult result = verifier.verify("bad-token", "1.2.3.4");

    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("invalid-input-response");
  }

  @Test
  @DisplayName("空 / null token → 失败,且不调 postForm(不走网络)")
  void blankToken_failsWithoutNetwork() {
    StubVerifier verifier = new StubVerifier(properties, "{\"success\":true}");

    assertThat(verifier.verify(null, "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("   ", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify(null, "1.2.3.4").reason()).contains("missing token");
    assertThat(verifier.postCalls.get()).isZero();
  }

  @Test
  @DisplayName("postForm 异常 → 保守判失败")
  void postFormThrows_fails() {
    CloudflareTurnstileVerifier verifier =
        new CloudflareTurnstileVerifier(properties, new ObjectMapper()) {
          @Override
          protected String postForm(String url, String body) {
            throw new RuntimeException("network down");
          }
        };

    CaptchaResult result = verifier.verify("any-token", "1.2.3.4");

    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("turnstile verify error");
  }

  @Test
  @DisplayName("provider 标识 = cloudflare")
  void providerName() {
    StubVerifier verifier = new StubVerifier(properties, "{\"success\":true}");
    assertThat(verifier.provider()).isEqualTo("cloudflare");
  }
}
