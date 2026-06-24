package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import io.github.pinpols.batch.console.config.LoginProtectionProperties;
import io.github.pinpols.batch.console.domain.rbac.support.captcha.CaptchaChallengeStore;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 验证码公开端点(登录前即可访问,无需认证):
 *
 * <ul>
 *   <li>{@code GET /api/console/captcha/config} —— 下发 {provider, siteKey, loginProtectionEnabled},
 *       FE 据此决定是否预载验证码 widget 及加载哪个 provider 的 widget。<b>不含 secret</b>。
 *   <li>{@code GET /api/console/captcha/challenge} —— 仅 self-hosted provider 提供:签发一次滑块挑战
 *       {challengeId, gap}。其它 provider 下该端点返回 404(挑战由第三方 SDK 自行处理)。
 * </ul>
 */
@RestController
@RequestMapping("/api/console/captcha")
@RequiredArgsConstructor
public class ConsoleCaptchaController {

  private final CaptchaProperties captchaProperties;
  private final LoginProtectionProperties loginProtectionProperties;
  private final ConsoleResponseFactory responseFactory;
  // self-hosted 才装配 CaptchaChallengeStore;其它 provider 下为空 → challenge 端点 404。
  private final ObjectProvider<CaptchaChallengeStore> challengeStoreProvider;

  /** FE 拉取验证码配置:provider + 公开 siteKey + 登录防护是否开启。绝不下发 secretKey。 */
  @GetMapping("/config")
  public CommonResponse<Map<String, Object>> config() {
    return responseFactory.success(
        Map.of(
            "provider", captchaProperties.getProvider(),
            "siteKey", captchaProperties.getSiteKey(),
            "loginProtectionEnabled", loginProtectionProperties.isEnabled()));
  }

  /** self-hosted 滑块挑战签发。非 self-hosted provider → 404。 */
  @GetMapping("/challenge")
  public CommonResponse<CaptchaChallengeStore.Challenge> challenge() {
    CaptchaChallengeStore store = challengeStoreProvider.getIfAvailable();
    if (store == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.auth.captcha_unavailable");
    }
    return responseFactory.success(store.issue());
  }
}
