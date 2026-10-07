package io.github.pinpols.batch.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.security.RequestSignatures;
import io.github.pinpols.batch.orchestrator.security.RequestSignatureVerifier.Result;
import io.github.pinpols.batch.orchestrator.security.RequestSignatureVerifier.SignedRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("请求签名校验,验证合法请求放行与缺失头部,时钟偏移,签名错误和重放的拒绝分支")
class RequestSignatureVerifierTest {

  private static final long NOW = 1_700_000_000_000L;
  private static final String KEY = "api-key-1";
  private static final byte[] BODY = "{\"tenantId\":\"t1\"}".getBytes(StandardCharsets.UTF_8);

  @Mock
  private NonceStore nonceStore;

  private RequestSignatureVerifier verifier;

  @BeforeEach
  void setUp() {
    RequestSigningProperties props = new RequestSigningProperties();
    props.setClockSkewSeconds(300);
    verifier = new RequestSignatureVerifier(props, nonceStore);
  }

  private SignedRequest signed(long ts, String nonce, String signature) {
    return new SignedRequest(
        KEY, "POST", "/internal/tasks/10/report", BODY, Long.toString(ts), nonce, signature, "t1");
  }

  private SignedRequest signed(String ts, String nonce, String signature) {
    return new SignedRequest(
        KEY, "POST", "/internal/tasks/10/report", BODY, ts, nonce, signature, "t1");
  }

  private String goodSig(long ts, String nonce) {
    return RequestSignatures.sign(
        KEY, "POST", "/internal/tasks/10/report", Long.toString(ts), nonce, BODY);
  }

  @Test
  @DisplayName("签名合法且随机数为首次使用时放行请求")
  void shouldAcceptRequest_whenSignatureValidAndNonceFresh() {
    when(nonceStore.registerIfAbsent(anyString(), anyString(), any())).thenReturn(true);
    Result r = verifier.verify(signed(NOW, "nonce-01", goodSig(NOW, "nonce-01")), NOW);
    assertThat(r).isEqualTo(Result.OK);
  }

  @Test
  @DisplayName("请求缺少签名头时直接判定为头部缺失")
  void shouldRejectRequest_whenSignatureHeaderMissing() {
    assertThat(verifier.verify(signed(NOW, "nonce-01", null), NOW))
        .isEqualTo(Result.MISSING_HEADERS);
  }

  @Test
  @DisplayName("时间戳超出允许的时钟偏移窗口时判定为时钟偏移")
  void shouldRejectRequest_whenTimestampOutsideClockSkewWindow() {
    long stale = NOW - 301_000L;
    assertThat(verifier.verify(signed(stale, "nonce-01", goodSig(stale, "nonce-01")), NOW))
        .isEqualTo(Result.CLOCK_SKEW);
  }

  @Test
  @DisplayName("签名与服务端计算值不一致时判定为签名错误")
  void shouldRejectRequest_whenSignatureMismatch() {
    assertThat(verifier.verify(signed(NOW, "nonce-01", "0".repeat(64)), NOW))
        .isEqualTo(Result.BAD_SIGNATURE);
  }

  @Test
  @DisplayName("随机数已被防重放记录占用时判定为重放")
  void shouldRejectRequest_whenNonceAlreadyRegistered() {
    when(nonceStore.registerIfAbsent(anyString(), anyString(), any())).thenReturn(false);
    assertThat(verifier.verify(signed(NOW, "nonce-01", goodSig(NOW, "nonce-01")), NOW))
        .isEqualTo(Result.REPLAY);
  }

  @Test
  @DisplayName("时间戳格式非法时按时钟偏移拒绝,且不写入防重放记录")
  void shouldRejectMalformedTimestamp_beforeTouchingNonceStore() {
    assertThat(verifier.verify(signed(" 1700000000000 ", "nonce-01", "0".repeat(64)), NOW))
        .isEqualTo(Result.CLOCK_SKEW);
    verifyNoInteractions(nonceStore);
  }

  @Test
  @DisplayName("随机数格式非法时按签名错误拒绝,且不写入防重放记录")
  void shouldRejectMalformedNonce_beforeTouchingNonceStore() {
    assertThat(verifier.verify(signed(NOW, "short", "0".repeat(64)), NOW))
        .isEqualTo(Result.BAD_SIGNATURE);
    verifyNoInteractions(nonceStore);
  }

  @Test
  @DisplayName("签名字符串格式非法时按签名错误拒绝,且不写入防重放记录")
  void shouldRejectMalformedSignature_beforeTouchingNonceStore() {
    assertThat(verifier.verify(signed(NOW, "nonce-01", "not-hex"), NOW))
        .isEqualTo(Result.BAD_SIGNATURE);
    verifyNoInteractions(nonceStore);
  }
}
