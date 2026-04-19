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
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * POST 幂等去重拦截器（5.5 重写版）。
 *
 * <p>Redis key 绑定 {@code tenant + method + uri + idempotencyKey}，避免跨接口/跨租户的假冲突。
 *
 * <p>两阶段占坑：preHandle 写入 {@code PENDING} 标记，afterCompletion 根据响应状态决定：
 *
 * <ul>
 *   <li>2xx 成功：标记改为 {@code DONE}，TTL 24 小时（阻止重复提交）。
 *   <li>非 2xx 失败：删除占位，允许调用方安全重试。
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsoleIdempotencyInterceptor implements HandlerInterceptor {

  private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "console:idempotency:";
  private static final String PENDING = "PENDING";
  private static final String DONE = "DONE";
  private static final String CONFLICT_BODY =
      "{\"code\":\""
          + ResultCode.CONFLICT.code()
          + "\",\"message\":\"duplicate request, same Idempotency-Key already processed\"}";
  private static final String MISSING_KEY_BODY =
      "{\"code\":\""
          + ResultCode.MISSING_IDEMPOTENCY_KEY.code()
          + "\",\"message\":\"this endpoint requires Idempotency-Key header\"}";
  /** Request attribute：记录本次请求使用的 Redis key，afterCompletion 时读取。 */
  private static final String ATTR_REDIS_KEY = "console.idempotency.redisKey";

  private final StringRedisTemplate redisTemplate;
  private final BatchSecurityProperties securityProperties;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {

    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }

    if (securityProperties.isBypassMode()) {
      return true;
    }

    String idempotencyKey = request.getHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER);
    if (!StringUtils.hasText(idempotencyKey)) {
      // 标了 @Idempotent 的方法 fail-close：没 header 直接 400
      if (handler instanceof HandlerMethod hm
          && (hm.getMethodAnnotation(Idempotent.class) != null
              || hm.getBeanType().isAnnotationPresent(Idempotent.class))) {
        log.warn(
            "missing Idempotency-Key on @Idempotent endpoint: uri={}", request.getRequestURI());
        writeJson(response, HttpStatus.BAD_REQUEST, MISSING_KEY_BODY);
        return false;
      }
      return true;
    }

    String tenantId = resolveTenantId(request);
    String redisKey =
        KEY_PREFIX + tenantId + ":" + request.getMethod() + ":" + request.getRequestURI() + ":"
            + idempotencyKey.trim();

    String existing = redisTemplate.opsForValue().get(redisKey);
    if (DONE.equals(existing)) {
      log.warn(
          "duplicate idempotency key rejected: key={}, uri={}, tenant={}",
          idempotencyKey, request.getRequestURI(), tenantId);
      writeJson(response, HttpStatus.CONFLICT, CONFLICT_BODY);
      return false;
    }

    // PENDING 也占坑（防并发双提交），但短 TTL（30s），超时自动释放
    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, PENDING, Duration.ofSeconds(30));
    if (Boolean.FALSE.equals(isNew)) {
      log.warn(
          "concurrent idempotency key rejected: key={}, uri={}, tenant={}",
          idempotencyKey, request.getRequestURI(), tenantId);
      writeJson(response, HttpStatus.CONFLICT, CONFLICT_BODY);
      return false;
    }

    request.setAttribute(ATTR_REDIS_KEY, redisKey);
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    String redisKey = (String) request.getAttribute(ATTR_REDIS_KEY);
    if (redisKey == null) {
      return;
    }
    int status = response.getStatus();
    if (status >= 200 && status < 300 && ex == null) {
      // 成功：升级为 DONE，长 TTL 阻止重复提交
      redisTemplate.opsForValue().set(redisKey, DONE, IDEMPOTENCY_TTL);
    } else {
      // 失败：删除占位，允许安全重试
      redisTemplate.delete(redisKey);
    }
  }

  private String resolveTenantId(HttpServletRequest request) {
    String tenantId = request.getHeader("X-Tenant-Id");
    return StringUtils.hasText(tenantId) ? tenantId.trim() : "_";
  }

  private void writeJson(HttpServletResponse response, HttpStatus httpStatus, String body)
      throws IOException {
    response.setStatus(httpStatus.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(body);
  }
}
