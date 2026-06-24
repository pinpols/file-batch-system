package io.github.pinpols.batch.console.domain.rbac.support.captcha;

/**
 * 验证码校验结果。{@code reason} 仅用于服务端日志/排查,<b>不</b>回传 FE(避免泄露风控细节)。
 *
 * @param success 是否通过人机验证
 * @param reason 失败原因(success=true 时为空串)
 */
public record CaptchaResult(boolean success, String reason) {

  public static CaptchaResult ok() {
    return new CaptchaResult(true, "");
  }

  public static CaptchaResult fail(String reason) {
    return new CaptchaResult(false, reason);
  }
}
