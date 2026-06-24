package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.CaptchaProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 自建滑块验证码挑战存储(Redis 支撑)。仅 {@code provider=selfhosted} 装配。
 *
 * <p>挑战值 = {@code gap:issuedAtMillis},单次有效(verify 时 {@code GETDEL} 原子消费,防重放)。 签发时间存服务端,校验时<b>服务端自算
 * elapsed</b>(FE 不能伪造耗时)——这是"防脚本秒过"时序风控的关键。
 */
@Component
@ConditionalOnProperty(name = "batch.console.captcha.provider", havingValue = "selfhosted")
@RequiredArgsConstructor
public class CaptchaChallengeStore {

  private static final String KEY_PREFIX = "captcha:slider:";
  private static final int GAP_MIN_PX = 40;
  private static final int GAP_MAX_PX = 260;

  private final StringRedisTemplate redisTemplate;
  private final BatchDateTimeSupport dateTimeSupport;
  private final CaptchaProperties properties;

  /** 新挑战:随机缺口位置 + challengeId,落 Redis(带 TTL)。gap 回传 FE 用于渲染滑块缺口。 */
  public Challenge issue() {
    String challengeId = UUID.randomUUID().toString();
    int gap = ThreadLocalRandom.current().nextInt(GAP_MIN_PX, GAP_MAX_PX + 1);
    long issuedAt = dateTimeSupport.currentEpochMillis();
    redisTemplate
        .opsForValue()
        .set(
            KEY_PREFIX + challengeId,
            gap + ":" + issuedAt,
            Duration.ofSeconds(properties.getSelfhostedChallengeTtlSeconds()));
    return new Challenge(challengeId, gap);
  }

  /** 原子消费挑战(GETDEL,单次有效)。返回 gap + 签发时间;不存在(过期/重放/伪造)则空。 */
  public Optional<Consumed> consume(String challengeId) {
    if (challengeId == null || challengeId.isBlank()) {
      return Optional.empty();
    }
    String raw = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + challengeId);
    if (raw == null) {
      return Optional.empty();
    }
    int sep = raw.indexOf(':');
    if (sep <= 0) {
      return Optional.empty();
    }
    try {
      int gap = Integer.parseInt(raw.substring(0, sep));
      long issuedAt = Long.parseLong(raw.substring(sep + 1));
      return Optional.of(new Consumed(gap, issuedAt));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /** 一次挑战:challengeId + 缺口像素位置(FE 据此渲染)。 */
  public record Challenge(String challengeId, int gap) {}

  /** 消费后的挑战内容:目标缺口 + 签发时间(epoch ms)。 */
  public record Consumed(int gap, long issuedAtMillis) {}
}
