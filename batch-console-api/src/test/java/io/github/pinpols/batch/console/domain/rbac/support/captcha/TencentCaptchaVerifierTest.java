package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("腾讯天御验证码:token 校验 + TC3 签名结构 + 验票分支")
class TencentCaptchaVerifierTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CaptchaProperties properties;

  @BeforeEach
  void setUp() {
    properties = new CaptchaProperties();
    properties.setProvider("tencent");
    properties.setTencentSecretId("fake-secret-id-for-test");
    properties.setTencentSecretKey("fake-secret-key-for-test");
    properties.setTencentAppId(190000000L);
    properties.setTencentAppSecretKey("appSecretKeyExample");
    properties.setTencentEndpoint("https://captcha.tencentcloudapi.com");
  }

  /** 可控 epochSeconds + 捕获 postJson 入参 / 返回预置 JSON 的测试子类,全程无网络。 */
  private static final class StubVerifier extends TencentCaptchaVerifier {
    private final String cannedResponse;
    private final long fixedEpoch;
    private final AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private boolean called;

    StubVerifier(CaptchaProperties props, String cannedResponse, long fixedEpoch) {
      super(props, MAPPER);
      this.cannedResponse = cannedResponse;
      this.fixedEpoch = fixedEpoch;
    }

    @Override
    protected long epochSeconds() {
      return fixedEpoch;
    }

    @Override
    protected String postJson(String url, Map<String, String> headers, String body) {
      this.called = true;
      this.capturedHeaders.set(headers);
      this.capturedBody.set(body);
      return cannedResponse;
    }
  }

  @Test
  @DisplayName("空 / 无冒号 token → 失败,且不走网络")
  void blankOrColonless_failsWithoutNetwork() {
    StubVerifier verifier = new StubVerifier(properties, "{}", 1234567890L);

    assertThat(verifier.verify(null, "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("   ", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("noColonHere", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify(":randonly", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("ticketonly:", "1.2.3.4").success()).isFalse();
    assertThat(verifier.called).isFalse();
  }

  @Test
  @DisplayName("CaptchaCode==1 → ok();请求体含 ticket/randstr/AppId,头含 TC3 鉴权")
  void captchaCodeOne_passes() throws Exception {
    StubVerifier verifier =
        new StubVerifier(properties, "{\"Response\":{\"CaptchaCode\":1}}", 1234567890L);

    CaptchaResult result = verifier.verify("the-ticket:the-randstr", "9.9.9.9");

    assertThat(result.success()).isTrue();

    // 请求体结构断言
    var body = MAPPER.readTree(verifier.capturedBody.get());
    assertThat(body.path("CaptchaType").asInt()).isEqualTo(9);
    assertThat(body.path("Ticket").asText()).isEqualTo("the-ticket");
    assertThat(body.path("Randstr").asText()).isEqualTo("the-randstr");
    assertThat(body.path("UserIp").asText()).isEqualTo("9.9.9.9");
    assertThat(body.path("CaptchaAppId").asLong()).isEqualTo(190000000L);
    assertThat(body.path("AppSecretKey").asText()).isEqualTo("appSecretKeyExample");

    // 头结构断言
    Map<String, String> headers = verifier.capturedHeaders.get();
    assertThat(headers.get("Authorization")).startsWith("TC3-HMAC-SHA256 Credential=");
    assertThat(headers.get("Authorization")).contains("SignedHeaders=content-type;host");
    assertThat(headers.get("X-TC-Action")).isEqualTo("DescribeCaptchaResult");
    assertThat(headers.get("X-TC-Version")).isEqualTo("2019-07-22");
    assertThat(headers.get("X-TC-Timestamp")).isEqualTo("1234567890");
    assertThat(headers.get("Content-Type")).isEqualTo("application/json; charset=utf-8");
    assertThat(headers.get("Host")).isEqualTo("captcha.tencentcloudapi.com");
  }

  @Test
  @DisplayName("CaptchaCode!=1 → fail,reason 带 code/msg")
  void captchaCodeNonOne_fails() {
    StubVerifier verifier =
        new StubVerifier(
            properties,
            "{\"Response\":{\"CaptchaCode\":7,\"CaptchaMsg\":\"ticket expired\"}}",
            1234567890L);

    CaptchaResult result = verifier.verify("t:r", "1.2.3.4");

    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("7").contains("ticket expired");
  }

  @Test
  @DisplayName("响应缺 Response/CaptchaCode → 保守判失败")
  void missingResponseNode_fails() {
    StubVerifier verifier = new StubVerifier(properties, "{}", 1234567890L);
    assertThat(verifier.verify("t:r", "1.2.3.4").success()).isFalse();
  }

  @Test
  @DisplayName("postJson 抛异常 → 保守判失败,不外泄")
  void postThrows_failsSafely() {
    TencentCaptchaVerifier verifier =
        new TencentCaptchaVerifier(properties, MAPPER) {
          @Override
          protected long epochSeconds() {
            return 1234567890L;
          }

          @Override
          protected String postJson(String url, Map<String, String> headers, String body) {
            throw new RuntimeException("connection refused");
          }
        };

    assertThat(verifier.verify("t:r", "1.2.3.4").success()).isFalse();
  }

  // ── TC3 签名:结构 / 确定性断言 ──────────────────────────────────────────
  // 注意:这里没有腾讯官方公布的 golden signature 向量(官方示例用的是 cvm/DescribeInstances,
  // 与本处 service=captcha 不同),故只断言 canonicalRequest / stringToSign 的官方文本结构 +
  // signature 为 64 位小写 hex + 同输入确定性。端到端签名正确性需对真 API 联调验证。

  @Test
  @DisplayName("canonicalRequest 遵循官方 6 段结构(POST / 空query / content-type+host 头)")
  void canonicalRequest_hasOfficialShape() {
    String payload = "{\"CaptchaAppId\":190000000}";
    String canonical =
        TencentCaptchaVerifier.buildCanonicalRequest("captcha.tencentcloudapi.com", payload);

    String[] lines = canonical.split("\n", -1);
    // POST \n / \n (空query) \n content-type:... \n host:... \n content-type;host \n hashedPayload
    // CanonicalHeaders 各以 \n 结尾,故 host 行后直接接 SignedHeaders 行(无额外空行)。
    assertThat(lines[0]).isEqualTo("POST");
    assertThat(lines[1]).isEqualTo("/");
    assertThat(lines[2]).isEmpty(); // CanonicalQueryString 空
    assertThat(lines[3]).isEqualTo("content-type:application/json; charset=utf-8");
    assertThat(lines[4]).isEqualTo("host:captcha.tencentcloudapi.com");
    assertThat(lines[5]).isEqualTo("content-type;host"); // SignedHeaders
    assertThat(lines[6]).matches("[0-9a-f]{64}"); // hashedPayload = sha256 hex
    assertThat(lines).hasSize(7);
  }

  @Test
  @DisplayName("stringToSign 遵循官方 4 段结构")
  void stringToSign_hasOfficialShape() {
    String canonical =
        TencentCaptchaVerifier.buildCanonicalRequest("captcha.tencentcloudapi.com", "{}");
    String sts = TencentCaptchaVerifier.buildStringToSign(1234567890L, "2009-02-13", canonical);

    String[] lines = sts.split("\n", -1);
    assertThat(lines[0]).isEqualTo("TC3-HMAC-SHA256");
    assertThat(lines[1]).isEqualTo("1234567890");
    assertThat(lines[2]).isEqualTo("2009-02-13/captcha/tc3_request");
    assertThat(lines[3]).matches("[0-9a-f]{64}"); // hex(sha256(canonicalRequest))
  }

  @Test
  @DisplayName("signature = 64 位小写 hex 且同输入确定性")
  void signature_is64HexAndDeterministic() {
    String canonical =
        TencentCaptchaVerifier.buildCanonicalRequest("captcha.tencentcloudapi.com", "{}");
    String sts = TencentCaptchaVerifier.buildStringToSign(1234567890L, "2009-02-13", canonical);

    String sig1 = TencentCaptchaVerifier.sign("2009-02-13", sts, "secretKeyExample");
    String sig2 = TencentCaptchaVerifier.sign("2009-02-13", sts, "secretKeyExample");

    assertThat(sig1).matches("[0-9a-f]{64}");
    assertThat(sig1).isEqualTo(sig2);
    // 不同密钥 → 不同签名
    assertThat(TencentCaptchaVerifier.sign("2009-02-13", sts, "otherKey")).isNotEqualTo(sig1);
  }

  @Test
  @DisplayName("Authorization 头组装含 Credential scope + SignedHeaders + Signature")
  void authorization_assembledCorrectly() {
    String auth =
        TencentCaptchaVerifier.buildAuthorization(
            "captcha.tencentcloudapi.com",
            "{}",
            1234567890L,
            "2009-02-13",
            "secretId",
            "secretKey");

    assertThat(auth)
        .startsWith("TC3-HMAC-SHA256 Credential=secretId/2009-02-13/captcha/tc3_request");
    assertThat(auth).contains("SignedHeaders=content-type;host");
    assertThat(auth).containsPattern("Signature=[0-9a-f]{64}");
  }

  @Test
  @DisplayName("provider 标识 = tencent")
  void providerName() {
    assertThat(new StubVerifier(properties, "{}", 1L).provider()).isEqualTo("tencent");
  }
}
