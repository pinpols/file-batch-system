package com.example.batch.console.domain.observability.service;

import com.example.batch.common.utils.Texts;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
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
 * <p>R4-P1-1：之前 ticket value 只存 {@code username:tenantId}，消费端从配置 {@code defaultAuthorities} 还原角色 →
 * 低权用户拿 ticket 后获默认角色集（提权）。改为存 {@code username|tenantId|role1,role2} 三段格式，消费时还原签发时的真实角色集。
 */
@Service
@RequiredArgsConstructor
public class SseTicketService {

  private static final String KEY_PREFIX = "console:sse:ticket:";
  private static final Duration TICKET_TTL = Duration.ofMinutes(5);
  private static final String FIELD_SEPARATOR = "|";
  private static final String FIELD_SEPARATOR_REGEX = "\\|";
  private static final String ROLE_SEPARATOR = ",";

  private final StringRedisTemplate redisTemplate;

  /** 签发一次性 ticket，绑定签发时的角色集。 */
  public String issue(String username, String tenantId, Collection<String> authorities) {
    String ticket = UUID.randomUUID().toString().replace("-", "");
    String roles =
        authorities == null
            ? ""
            : String.join(
                ROLE_SEPARATOR, authorities.stream().filter(Texts::hasText).distinct().toList());
    String value =
        username + FIELD_SEPARATOR + (tenantId == null ? "" : tenantId) + FIELD_SEPARATOR + roles;
    redisTemplate.opsForValue().set(KEY_PREFIX + ticket, value, TICKET_TTL);
    return ticket;
  }

  /** 验证并消费 ticket（一次性）。 */
  public TicketPayload validate(String ticket) {
    if (!Texts.hasText(ticket)) {
      return null;
    }
    String key = KEY_PREFIX + ticket.trim();
    String value = redisTemplate.opsForValue().getAndDelete(key);
    if (!Texts.hasText(value)) {
      return null;
    }
    String[] parts = value.split(FIELD_SEPARATOR_REGEX, -1);
    String username = parts.length > 0 ? parts[0] : "";
    String tenantId = parts.length > 1 ? parts[1] : "";
    Set<String> roles = new LinkedHashSet<>();
    if (parts.length > 2 && Texts.hasText(parts[2])) {
      for (String r : parts[2].split(ROLE_SEPARATOR)) {
        if (Texts.hasText(r)) {
          roles.add(r.trim());
        }
      }
    }
    return new TicketPayload(username, tenantId, Collections.unmodifiableSet(roles));
  }

  /** R4-P1-1：ticket 解码结果——含签发时角色集。 */
  public record TicketPayload(String username, String tenantId, Set<String> authorities) {}
}
