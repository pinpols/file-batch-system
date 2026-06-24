package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.LoginProtectionProperties;
import io.github.pinpols.batch.console.domain.rbac.support.captcha.CaptchaResult;
import io.github.pinpols.batch.console.domain.rbac.support.captcha.CaptchaVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("登录防护编排:不锁账号 + risk-based 验证码 + 失败退避")
@ExtendWith(MockitoExtension.class)
class LoginProtectionServiceTest {

  @Mock private LoginFailureTracker failureTracker;
  @Mock private CaptchaVerifier captchaVerifier;

  private LoginProtectionProperties properties;
  private LoginProtectionService service;

  @BeforeEach
  void setUp() {
    properties = new LoginProtectionProperties();
    properties.setEnabled(true);
    properties.setFailThreshold(5);
    properties.setBackoffStepMillis(0L); // 测试不真睡
    properties.setBackoffCapMillis(0L);
    service = new LoginProtectionService(properties, failureTracker, captchaVerifier);
  }

  @Test
  @DisplayName("总开关关 → 三方法全旁路,不碰 tracker / verifier")
  void disabled_bypassesEverything() {
    properties.setEnabled(false);

    service.assertCaptchaSatisfied("alice", "1.2.3.4", null);
    service.onLoginFailure("alice", "1.2.3.4");
    service.onLoginSuccess("alice");

    verifyNoInteractions(failureTracker, captchaVerifier);
  }

  @Test
  @DisplayName("未达阈值 → 不要求验证码(verifier 不被调用)")
  void belowThreshold_noCaptcha() {
    when(failureTracker.currentFailures("alice", "1.2.3.4")).thenReturn(2L);

    service.assertCaptchaSatisfied("alice", "1.2.3.4", null);

    verify(captchaVerifier, never()).verify(any(), any());
  }

  @Test
  @DisplayName("达阈值 + 未提交验证码 → 抛 CAPTCHA_REQUIRED(captcha_required)")
  void atThreshold_noToken_requiresCaptcha() {
    when(failureTracker.currentFailures("alice", "1.2.3.4")).thenReturn(5L);
    when(captchaVerifier.verify(null, "1.2.3.4")).thenReturn(CaptchaResult.fail("empty token"));

    assertThatThrownBy(() -> service.assertCaptchaSatisfied("alice", "1.2.3.4", null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("captcha_required")
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.CAPTCHA_REQUIRED);
  }

  @Test
  @DisplayName("达阈值 + 验证码错 → 抛 CAPTCHA_REQUIRED(captcha_failed)")
  void atThreshold_badToken_captchaFailed() {
    when(failureTracker.currentFailures("alice", "1.2.3.4")).thenReturn(8L);
    when(captchaVerifier.verify("bad", "1.2.3.4"))
        .thenReturn(CaptchaResult.fail("position mismatch"));

    assertThatThrownBy(() -> service.assertCaptchaSatisfied("alice", "1.2.3.4", "bad"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("captcha_failed");
  }

  @Test
  @DisplayName("达阈值 + 验证码过 → 放行")
  void atThreshold_goodToken_passes() {
    when(failureTracker.currentFailures("alice", "1.2.3.4")).thenReturn(5L);
    when(captchaVerifier.verify("good", "1.2.3.4")).thenReturn(CaptchaResult.ok());

    service.assertCaptchaSatisfied("alice", "1.2.3.4", "good");
  }

  @Test
  @DisplayName("登录失败 → 记一次失败(账号+IP)")
  void onFailure_records() {
    when(failureTracker.recordFailure("alice", "1.2.3.4")).thenReturn(3L);

    service.onLoginFailure("alice", "1.2.3.4");

    verify(failureTracker).recordFailure("alice", "1.2.3.4");
  }

  @Test
  @DisplayName("登录成功 → 清零该账号失败计数")
  void onSuccess_clearsAccount() {
    service.onLoginSuccess("alice");

    verify(failureTracker).clearAccount("alice");
  }

  @Test
  @DisplayName("退避封顶:失败数极大时延迟不超过 cap(此处 cap=0 不真睡,断言不抛)")
  void backoff_capped() {
    when(failureTracker.recordFailure(anyString(), anyString())).thenReturn(1000L);

    service.onLoginFailure("alice", "1.2.3.4"); // step=0 → delay=0,瞬时返回
  }
}
