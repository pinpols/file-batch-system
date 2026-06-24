package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("阿里云验证码 2.0 校验:成功 / 失败 / 空 token 不走网络 / ACS3 签名结构与确定性")
class AliyunCaptchaVerifierTest {

  private CaptchaProperties properties;

  /** 子类覆盖 postJson 返回预置 JSON,并固定 acsDate / nonce 保证签名确定性;记录调用次数 + 捕获实际发送的 headers/body 供断言。 */
  private static final class StubVerifier extends AliyunCaptchaVerifier {
    private final String cannedJson;
    final AtomicInteger postCalls = new AtomicInteger();
    final AtomicReference<Map<String, String>> sentHeaders = new AtomicReference<>();
    final AtomicReference<String> sentBody = new AtomicReference<>();

    StubVerifier(CaptchaProperties properties, String cannedJson) {
      super(properties, new ObjectMapper());
      this.cannedJson = cannedJson;
    }

    @Override
    protected String postJson(String url, Map<String, String> headers, String body) {
      postCalls.incrementAndGet();
      sentHeaders.set(headers);
      sentBody.set(body);
      return cannedJson;
    }

    @Override
    protected String acsDate() {
      return "2023-03-05T01:02:03Z";
    }

    @Override
    protected String nonce() {
      return "fixednonce0000000000000000000000";
    }
  }

  @BeforeEach
  void setUp() {
    properties = new CaptchaProperties();
    properties.setProvider("aliyun");
    properties.setAliyunAccessKeyId("test-ak-id");
    properties.setAliyunAccessKeySecret("test-ak-secret");
    properties.setAliyunSceneId("scene-123");
  }

  @Test
  @DisplayName("VerifyResult=true → 通过,且 postJson 调一次")
  void verifyResultTrue_passes() {
    StubVerifier verifier =
        new StubVerifier(
            properties, "{\"Body\":{\"Result\":{\"VerifyResult\":true},\"Success\":true}}");

    CaptchaResult result = verifier.verify("good-token", "1.2.3.4");

    assertThat(result.success()).isTrue();
    assertThat(verifier.postCalls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("VerifyResult=false → 失败")
  void verifyResultFalse_fails() {
    StubVerifier verifier =
        new StubVerifier(
            properties,
            "{\"Body\":{\"Result\":{\"VerifyResult\":false},\"Success\":true,\"Code\":\"Verify.Fail\"}}");

    CaptchaResult result = verifier.verify("bad-token", "1.2.3.4");

    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("Verify.Fail");
  }

  @Test
  @DisplayName("空 / null token → 失败,且不调 postJson(不走网络)")
  void blankToken_failsWithoutNetwork() {
    StubVerifier verifier =
        new StubVerifier(
            properties, "{\"Body\":{\"Result\":{\"VerifyResult\":true},\"Success\":true}}");

    assertThat(verifier.verify(null, "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("   ", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify(null, "1.2.3.4").reason()).contains("missing token");
    assertThat(verifier.postCalls.get()).isZero();
  }

  @Test
  @DisplayName("postJson 异常 → 保守判失败")
  void postJsonThrows_fails() {
    AliyunCaptchaVerifier verifier =
        new AliyunCaptchaVerifier(properties, new ObjectMapper()) {
          @Override
          protected String postJson(String url, Map<String, String> headers, String body) {
            throw new RuntimeException("network down");
          }
        };

    CaptchaResult result = verifier.verify("any-token", "1.2.3.4");

    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("aliyun verify error");
  }

  @Test
  @DisplayName("发送请求带齐 ACS3 签名头(Authorization + x-acs-* + body 含 SceneId)")
  void sendsSignedHeaders() {
    StubVerifier verifier =
        new StubVerifier(
            properties, "{\"Body\":{\"Result\":{\"VerifyResult\":true},\"Success\":true}}");

    verifier.verify("tok-abc", "1.2.3.4");

    Map<String, String> headers = verifier.sentHeaders.get();
    assertThat(headers.get("Authorization")).startsWith("ACS3-HMAC-SHA256 Credential=test-ak-id");
    assertThat(headers)
        .containsKeys(
            "x-acs-action",
            "x-acs-version",
            "x-acs-date",
            "x-acs-signature-nonce",
            "x-acs-content-sha256",
            "host");
    assertThat(headers.get("x-acs-action")).isEqualTo("VerifyIntelligentCaptcha");
    assertThat(headers.get("x-acs-version")).isEqualTo("2023-03-05");
    assertThat(verifier.sentBody.get()).contains("scene-123").contains("tok-abc");
  }

  @Test
  @DisplayName("provider 标识 = aliyun")
  void providerName() {
    StubVerifier verifier =
        new StubVerifier(
            properties, "{\"Body\":{\"Result\":{\"VerifyResult\":true},\"Success\":true}}");
    assertThat(verifier.provider()).isEqualTo("aliyun");
  }

  // ── ACS3-HMAC-SHA256 签名结构 / 确定性单测 ─────────────────────────────────
  // 注意:这里只断言签名算法的结构(canonicalRequest 拼装、stringToSign 前缀、signature 为 64 位 hex)
  // 与确定性(同输入同输出)。端到端签名是否被阿里网关接受需联调真 API 验,本仓无官方 golden 向量。

  @Test
  @DisplayName("hexSha256 / hexHmacSha256 输出 64 位小写 hex 且确定")
  void digestPrimitives_are64HexAndDeterministic() throws Exception {
    String h1 = CaptchaCrypto.sha256Hex("hello");
    String h2 = CaptchaCrypto.sha256Hex("hello");
    assertThat(h1).isEqualTo(h2).hasSize(64).matches("[0-9a-f]{64}");
    // 已知向量:sha256("hello")
    assertThat(h1).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

    String mac1 = CaptchaCrypto.hmacSha256Hex("secret", "data");
    String mac2 = CaptchaCrypto.hmacSha256Hex("secret", "data");
    assertThat(mac1).isEqualTo(mac2).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("buildAuthorization:Authorization 结构正确,signature 64 位 hex,且确定")
  void buildAuthorization_structureAndDeterminism() throws Exception {
    TreeMap<String, String> canonicalHeaders = new TreeMap<>();
    canonicalHeaders.put("host", "captcha.ap-southeast-1.aliyuncs.com");
    canonicalHeaders.put("x-acs-action", "VerifyIntelligentCaptcha");
    canonicalHeaders.put("x-acs-content-sha256", CaptchaCrypto.sha256Hex("{}"));
    canonicalHeaders.put("x-acs-date", "2023-03-05T01:02:03Z");
    canonicalHeaders.put("x-acs-signature-nonce", "fixednonce");
    canonicalHeaders.put("x-acs-version", "2023-03-05");

    String auth1 =
        AliyunCaptchaVerifier.buildAuthorization(
            "POST",
            "/",
            "",
            new TreeMap<>(canonicalHeaders),
            canonicalHeaders.get("x-acs-content-sha256"),
            "ak-id",
            "ak-secret");
    String auth2 =
        AliyunCaptchaVerifier.buildAuthorization(
            "POST",
            "/",
            "",
            new TreeMap<>(canonicalHeaders),
            canonicalHeaders.get("x-acs-content-sha256"),
            "ak-id",
            "ak-secret");

    // 确定性
    assertThat(auth1).isEqualTo(auth2);
    // 结构
    assertThat(auth1)
        .startsWith("ACS3-HMAC-SHA256 Credential=ak-id,SignedHeaders=")
        .contains(
            "SignedHeaders=host;x-acs-action;x-acs-content-sha256;x-acs-date;"
                + "x-acs-signature-nonce;x-acs-version")
        .contains(",Signature=");
    // signature 段为 64 位小写 hex
    String signature = auth1.substring(auth1.indexOf(",Signature=") + ",Signature=".length());
    assertThat(signature).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("buildAuthorization:不同 secret → 不同 signature(签名确实绑定 secret)")
  void buildAuthorization_differentSecretDiffersSignature() throws Exception {
    TreeMap<String, String> h = new TreeMap<>();
    h.put("host", "captcha.ap-southeast-1.aliyuncs.com");
    h.put("x-acs-action", "VerifyIntelligentCaptcha");
    String payload = CaptchaCrypto.sha256Hex("{}");

    String a =
        AliyunCaptchaVerifier.buildAuthorization(
            "POST", "/", "", new TreeMap<>(h), payload, "ak", "secret-A");
    String b =
        AliyunCaptchaVerifier.buildAuthorization(
            "POST", "/", "", new TreeMap<>(h), payload, "ak", "secret-B");

    assertThat(a).isNotEqualTo(b);
  }
}
