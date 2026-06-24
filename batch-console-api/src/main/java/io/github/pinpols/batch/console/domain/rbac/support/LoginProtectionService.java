package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.LoginProtectionProperties;
import io.github.pinpols.batch.console.domain.rbac.support.captcha.CaptchaResult;
import io.github.pinpols.batch.console.domain.rbac.support.captcha.CaptchaVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 控制台登录防暴力破解编排:IP 限流(已由 {@code ConsoleRateLimitFilter} 承担)之外的两层——<b>账号维度失败退避</b> + <b>risk-based
 * 验证码</b>。设计见 {@code docs/design/console-login-bruteforce-protection.md}。
 *
 * <p><b>关键安全约束:不锁账号</b>。失败达阈值只把"该次登录升级到要求验证码",受害者最多被要求做一次验证码,攻击者无法靠故意输错把别人锁出去 (规避 account-lockout
 * DoS)。
 *
 * <p>总开关 {@code batch.console.login-protection.enabled} 默认 false:关闭时三个方法全部立即返回,登录流程零变化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginProtectionService {

  private final LoginProtectionProperties properties;
  private final LoginFailureTracker failureTracker;
  private final CaptchaVerifier captchaVerifier;

  /**
   * 密码校验<b>之前</b>调用:若该账号/IP 失败计数已达阈值,则要求验证码。验证码不通过 → 抛 {@link ResultCode#CAPTCHA_REQUIRED} (FE
   * 据此弹验证码组件)。未达阈值 / 验证码通过 / 总开关关 → 静默放行。
   */
  public void assertCaptchaSatisfied(String username, String clientIp, String captchaToken) {
    if (!properties.isEnabled()) {
      return;
    }
    long failures = failureTracker.currentFailures(username, clientIp);
    if (failures < properties.getFailThreshold()) {
      return;
    }
    CaptchaResult result = captchaVerifier.verify(captchaToken, clientIp);
    if (!result.success()) {
      boolean provided = captchaToken != null && !captchaToken.isBlank();
      log.warn(
          "login captcha required: user={} ip={} failures={} provider={} provided={} reason={}",
          username,
          clientIp,
          failures,
          captchaVerifier.provider(),
          provided,
          result.reason());
      throw BizException.of(
          ResultCode.CAPTCHA_REQUIRED,
          provided ? "error.auth.captcha_failed" : "error.auth.captcha_required");
    }
  }

  /**
   * 密码校验<b>失败后</b>调用:记一次失败(账号 + IP),并按 {@code backoffStepMillis × 失败数}(封顶 {@code
   * backoffCapMillis})做渐进退避——拖慢自动化吞吐,真实用户连错才递增、几乎无感。
   */
  public void onLoginFailure(String username, String clientIp) {
    if (!properties.isEnabled()) {
      return;
    }
    long failures = failureTracker.recordFailure(username, clientIp);
    applyBackoff(failures);
  }

  /** 登录成功后调用:清零该账号失败计数(IP 计数保留)。 */
  public void onLoginSuccess(String username) {
    if (!properties.isEnabled()) {
      return;
    }
    failureTracker.clearAccount(username);
  }

  private void applyBackoff(long failures) {
    long delay =
        Math.min(properties.getBackoffStepMillis() * failures, properties.getBackoffCapMillis());
    if (delay <= 0) {
      return;
    }
    try {
      Thread.sleep(delay);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
