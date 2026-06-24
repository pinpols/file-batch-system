package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 自建滑块验证码校验(不外联,保底)。{@code provider=selfhosted} 装配。
 *
 * <p>token 形态 = {@code challengeId:position}(position = 用户滑动落点像素)。校验三关:
 *
 * <ol>
 *   <li><b>单次有效</b>:challengeId 经 {@code GETDEL} 原子消费,重放/伪造/过期即 null → 失败
 *   <li><b>时序风控</b>:服务端自算 elapsed = now - issuedAt,低于 {@code minElapsedMillis} 判脚本秒过 → 失败
 *   <li><b>位置命中</b>:|position - gap| ≤ {@code tolerancePx} 才通过
 * </ol>
 *
 * <p>定位:配合 IP 限流 + 失败退避做纵深的"抬门槛",非真壁垒(见设计文档 §5)。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.console.captcha.provider", havingValue = "selfhosted")
@RequiredArgsConstructor
public class SelfHostedSliderVerifier implements CaptchaVerifier {

  private final CaptchaChallengeStore challengeStore;
  private final CaptchaProperties properties;
  private final BatchDateTimeSupport dateTimeSupport;

  @Override
  public CaptchaResult verify(String token, String clientIp) {
    if (token == null || token.isBlank()) {
      return CaptchaResult.fail("empty token");
    }
    int sep = token.indexOf(':');
    if (sep <= 0 || sep == token.length() - 1) {
      return CaptchaResult.fail("malformed token");
    }
    String challengeId = token.substring(0, sep);
    int position;
    try {
      position = Integer.parseInt(token.substring(sep + 1).trim());
    } catch (NumberFormatException ex) {
      return CaptchaResult.fail("non-numeric position");
    }

    Optional<CaptchaChallengeStore.Consumed> consumed = challengeStore.consume(challengeId);
    if (consumed.isEmpty()) {
      return CaptchaResult.fail("challenge expired, reused or forged");
    }
    CaptchaChallengeStore.Consumed challenge = consumed.get();

    long elapsed = dateTimeSupport.currentEpochMillis() - challenge.issuedAtMillis();
    if (elapsed < properties.getSelfhostedMinElapsedMillis()) {
      log.warn("captcha self-hosted rejected: too fast elapsed={}ms ip={}", elapsed, clientIp);
      return CaptchaResult.fail("solved too fast");
    }
    if (Math.abs(position - challenge.gap()) > properties.getSelfhostedTolerancePx()) {
      return CaptchaResult.fail("position mismatch");
    }
    return CaptchaResult.ok();
  }

  @Override
  public String provider() {
    return "selfhosted";
  }
}
