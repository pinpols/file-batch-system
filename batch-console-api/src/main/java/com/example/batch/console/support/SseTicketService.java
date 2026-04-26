package com.example.batch.console.support;

import com.example.batch.common.utils.Texts;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * SSE 连接的一次性 ticket 服务。
 *
 * <p>流程：前端先 POST /api/console/stream/ticket（带 Authorization header）换取一次性 ticket， 再用
 * EventSource(?ticket=xxx) 建立 SSE 连接。ticket 验证成功后立即删除，防止重放。
 *
 * <p>ticket 有效期 5 分钟，存 Redis，格式 {@code console:sse:ticket:{ticket} → username:tenantId}。
 */
@Service
@RequiredArgsConstructor
public class SseTicketService {

  private static final String KEY_PREFIX = "console:sse:ticket:";
  private static final Duration TICKET_TTL = Duration.ofMinutes(5);

  private final StringRedisTemplate redisTemplate;

  /** 签发一次性 ticket。 */
  public String issue(String username, String tenantId) {
    String ticket = UUID.randomUUID().toString().replace("-", "");
    String value = username + ":" + (tenantId == null ? "" : tenantId);
    redisTemplate.opsForValue().set(KEY_PREFIX + ticket, value, TICKET_TTL);
    return ticket;
  }

  /**
   * 验证并消费 ticket（一次性）。
   *
   * @return "username:tenantId" 或 null（无效/已过期/已使用）
   */
  public String validate(String ticket) {
    if (!Texts.hasText(ticket)) {
      return null;
    }
    String key = KEY_PREFIX + ticket.trim();
    String value = redisTemplate.opsForValue().getAndDelete(key);
    return Texts.hasText(value) ? value : null;
  }
}
