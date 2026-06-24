package io.github.pinpols.batch.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
class RequestSignatureVerifierTest {

  private static final long NOW = 1_700_000_000_000L;
  private static final String KEY = "api-key-1";
  private static final byte[] BODY = "{\"tenantId\":\"t1\"}".getBytes(StandardCharsets.UTF_8);

  @Mock private NonceStore nonceStore;
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

  private String goodSig(long ts, String nonce) {
    return RequestSignatures.sign(
        KEY, "POST", "/internal/tasks/10/report", Long.toString(ts), nonce, BODY);
  }

  @Test
  @DisplayName("合法签名+新 nonce+ts 在窗内 → OK")
  void validSignaturePasses() {
    when(nonceStore.registerIfAbsent(anyString(), anyString(), any())).thenReturn(true);
    Result r = verifier.verify(signed(NOW, "n1", goodSig(NOW, "n1")), NOW);
    assertThat(r).isEqualTo(Result.OK);
  }

  @Test
  @DisplayName("缺签名头 → MISSING_HEADERS")
  void missingHeaders() {
    assertThat(verifier.verify(signed(NOW, "n1", null), NOW)).isEqualTo(Result.MISSING_HEADERS);
  }

  @Test
  @DisplayName("时间戳超出偏移窗 → CLOCK_SKEW")
  void clockSkewRejected() {
    long stale = NOW - 301_000L;
    assertThat(verifier.verify(signed(stale, "n1", goodSig(stale, "n1")), NOW))
        .isEqualTo(Result.CLOCK_SKEW);
  }

  @Test
  @DisplayName("签名不匹配 → BAD_SIGNATURE")
  void badSignature() {
    assertThat(verifier.verify(signed(NOW, "n1", "deadbeef"), NOW)).isEqualTo(Result.BAD_SIGNATURE);
  }

  @Test
  @DisplayName("nonce 已用过(store 返回 false) → REPLAY")
  void replayRejected() {
    when(nonceStore.registerIfAbsent(anyString(), anyString(), any())).thenReturn(false);
    assertThat(verifier.verify(signed(NOW, "n1", goodSig(NOW, "n1")), NOW))
        .isEqualTo(Result.REPLAY);
  }
}
