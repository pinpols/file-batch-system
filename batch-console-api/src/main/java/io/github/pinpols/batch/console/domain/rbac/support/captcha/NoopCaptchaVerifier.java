package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认验证码实现:不做任何校验,一律通过。{@code provider=none}(默认)或未配置时装配。
 *
 * <p>意义:当 {@code login-protection.enabled=true} 但未接入任何验证码 provider 时,风控只剩失败退避层(仍有价值),验证码层等效旁路——
 * 保证「开了登录防护但还没选验证码」也能正常工作,不阻断登录。
 */
@Component
@ConditionalOnProperty(
    name = "batch.console.captcha.provider",
    havingValue = "none",
    matchIfMissing = true)
public class NoopCaptchaVerifier implements CaptchaVerifier {

  @Override
  public CaptchaResult verify(String token, String clientIp) {
    return CaptchaResult.ok();
  }

  @Override
  public String provider() {
    return "none";
  }
}
