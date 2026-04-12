package com.example.batch.console.support;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * POST 幂等去重拦截器。
 *
 * <p>当请求携带 {@code Idempotency-Key} header 时执行 Redis 去重（TTL 24 小时）：
 *
 * <ul>
 *   <li>首次请求：写入占位标记，放行。
 *   <li>重复请求（key 已存在）：返回 409 CONFLICT，阻止重复写库。
 *   <li>未携带 header：直接放行，由控制器的 {@code @RequestHeader} 注解决定是否拒绝。
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsoleIdempotencyInterceptor implements HandlerInterceptor {

  private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "console:idempotency:";
  private static final String CONFLICT_BODY =
      "{\"code\":\""
          + ResultCode.CONFLICT.code()
          + "\",\"message\":\"duplicate request, same Idempotency-Key already processed\"}";

  private final StringRedisTemplate redisTemplate;
  private final BatchSecurityProperties securityProperties;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {

    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }

    if (securityProperties.isTestingOpen()) {
      return true;
    }

    String idempotencyKey = request.getHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER);
    if (!StringUtils.hasText(idempotencyKey)) {
      // 无 header：放行，由 @RequestHeader 注解或业务逻辑决定是否拒绝
      return true;
    }

    String redisKey = KEY_PREFIX + idempotencyKey;
    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", IDEMPOTENCY_TTL);
    if (Boolean.FALSE.equals(isNew)) {
      log.warn(
          "duplicate idempotency key rejected: key={}, uri={}",
          idempotencyKey,
          request.getRequestURI());
      writeJson(response, HttpStatus.CONFLICT, CONFLICT_BODY);
      return false;
    }

    return true;
  }

  private void writeJson(HttpServletResponse response, HttpStatus status, String body)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(body);
  }
}
