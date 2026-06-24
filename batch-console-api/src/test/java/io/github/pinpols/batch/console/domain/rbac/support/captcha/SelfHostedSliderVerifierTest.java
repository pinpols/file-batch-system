package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("自建滑块验证码:单次有效 + 时序风控 + 位置命中")
@ExtendWith(MockitoExtension.class)
class SelfHostedSliderVerifierTest {

  @Mock private CaptchaChallengeStore challengeStore;
  @Mock private BatchDateTimeSupport dateTimeSupport;

  private CaptchaProperties properties;
  private SelfHostedSliderVerifier verifier;

  @BeforeEach
  void setUp() {
    properties = new CaptchaProperties();
    properties.setProvider("selfhosted");
    properties.setSelfhostedTolerancePx(5);
    properties.setSelfhostedMinElapsedMillis(300L);
    verifier = new SelfHostedSliderVerifier(challengeStore, properties, dateTimeSupport);
  }

  @Test
  @DisplayName("位置命中 + 耗时合理 → 通过")
  void validSolve_passes() {
    // 挑战缺口 150,签发于 t=1000;提交位置 152(差 2 ≤ 容差 5),现在 t=2000(elapsed=1000ms ≥ 300)
    when(challengeStore.consume("cid"))
        .thenReturn(Optional.of(new CaptchaChallengeStore.Consumed(150, 1000L)));
    when(dateTimeSupport.currentEpochMillis()).thenReturn(2000L);

    assertThat(verifier.verify("cid:152", "1.2.3.4").success()).isTrue();
  }

  @Test
  @DisplayName("挑战不存在(过期/重放/伪造)→ 失败")
  void missingChallenge_fails() {
    when(challengeStore.consume("cid")).thenReturn(Optional.empty());

    CaptchaResult result = verifier.verify("cid:152", "1.2.3.4");
    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("expired");
  }

  @Test
  @DisplayName("秒过(elapsed < minElapsed)→ 失败")
  void tooFast_fails() {
    when(challengeStore.consume("cid"))
        .thenReturn(Optional.of(new CaptchaChallengeStore.Consumed(150, 1000L)));
    when(dateTimeSupport.currentEpochMillis()).thenReturn(1100L); // 仅 100ms

    CaptchaResult result = verifier.verify("cid:150", "1.2.3.4");
    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("fast");
  }

  @Test
  @DisplayName("位置超出容差 → 失败")
  void positionMismatch_fails() {
    when(challengeStore.consume("cid"))
        .thenReturn(Optional.of(new CaptchaChallengeStore.Consumed(150, 1000L)));
    when(dateTimeSupport.currentEpochMillis()).thenReturn(2000L);

    CaptchaResult result = verifier.verify("cid:200", "1.2.3.4"); // 差 50 > 容差 5
    assertThat(result.success()).isFalse();
    assertThat(result.reason()).contains("position");
  }

  @Test
  @DisplayName("空 / 畸形 token → 失败,不碰 store")
  void blankOrMalformed_fails() {
    assertThat(verifier.verify(null, "ip").success()).isFalse();
    assertThat(verifier.verify("", "ip").success()).isFalse();
    assertThat(verifier.verify("noColon", "ip").success()).isFalse();
    assertThat(verifier.verify("cid:", "ip").success()).isFalse();
    assertThat(verifier.verify("cid:abc", "ip").success()).isFalse();
  }

  @Test
  @DisplayName("越界 position(含 Integer 极值)→ 失败,且不消费挑战(防 tainted 算术溢出)")
  void outOfRangePosition_failsWithoutConsuming() {
    // 超大 / 负值 / Integer 极值都应在算术前被设界拦下;此时不应触达 challengeStore（strict mock 不 stub consume）
    assertThat(verifier.verify("cid:99999", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("cid:-1", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("cid:2147483647", "1.2.3.4").success()).isFalse();
    assertThat(verifier.verify("cid:-2147483648", "1.2.3.4").reason()).contains("range");
    verifyNoInteractions(challengeStore);
  }

  @Test
  @DisplayName("provider 标识 = selfhosted")
  void providerName() {
    assertThat(verifier.provider()).isEqualTo("selfhosted");
  }
}
