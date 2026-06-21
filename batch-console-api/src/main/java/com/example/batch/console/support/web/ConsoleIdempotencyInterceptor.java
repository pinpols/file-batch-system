package com.example.batch.console.support.web;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.utils.Texts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Console 写接口幂等去重拦截器（5.5 重写版）。
 *
 * <p>Redis key 绑定 {@code tenant + method + uri + idempotencyKey}，避免跨接口/跨租户的假冲突。
 *
 * <p>两阶段占问题：preHandle 写入 {@code PENDING} 标记，afterCompletion 根据响应状态决定：
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
  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String KEY_PREFIX = "console:idempotency:";
  private static final String PENDING = "PENDING";
  private static final String DONE = "DONE";
  // C-2.10: 区分 DONE / PENDING 两种 CONFLICT 场景，便于前端决定是"提示已处理"还是"稍后重试"
  private static final String CONFLICT_DONE_BODY =
      "{\"code\":\""
          + ResultCode.CONFLICT.code()
          + "\",\"message\":\"duplicate request, same Idempotency-Key already processed\"}";
  private static final String CONFLICT_PENDING_BODY =
      "{\"code\":\""
          + ResultCode.CONFLICT.code()
          + "\",\"message\":\"request currently being processed, retry after 30s with same"
          + " Idempotency-Key\"}";
  // R-4.1：Redis 不可达时幂等采用 fail-closed（返回 503），宁可拒绝也不双写。
  // 与限流的 fail-open 形成对照——前者保可用，后者保安全。
  private static final String REDIS_UNAVAILABLE_BODY =
      "{\"code\":\""
          + ResultCode.SERVICE_UNAVAILABLE.code()
          + "\",\"message\":\"idempotency store temporarily unavailable, safe to retry later\"}";
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

    String method = request.getMethod().toUpperCase(Locale.ROOT);
    if (!MUTATING_METHODS.contains(method)) {
      return true;
    }

    if (securityProperties.isBypassMode()) {
      return true;
    }

    String idempotencyKey = request.getHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER);
    if (!Texts.hasText(idempotencyKey)) {
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
        KEY_PREFIX
            + tenantId
            + ":"
            + method
            + ":"
            + request.getRequestURI()
            + ":"
            + idempotencyKey.trim();

    String existing;
    try {
      existing = redisTemplate.opsForValue().get(redisKey);
    } catch (DataAccessException ex) {
      // R-4.1 fail-closed：幂等拦截器拿不到 Redis 直接 503
      log.warn(
          "idempotency Redis GET unavailable — fail-closed: key={}, cause={}",
          idempotencyKey,
          ex.getMessage());
      writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, REDIS_UNAVAILABLE_BODY);
      return false;
    }
    if (DONE.equals(existing)) {
      log.warn(
          "duplicate idempotency key rejected (already done): key={}, uri={}, tenant={}",
          idempotencyKey,
          request.getRequestURI(),
          tenantId);
      writeJson(response, HttpStatus.CONFLICT, CONFLICT_DONE_BODY);
      return false;
    }

    // PENDING 也占问题（防并发双提交），但短 TTL（30s），超时自动释放
    Boolean isNew;
    try {
      isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, PENDING, Duration.ofSeconds(30));
    } catch (DataAccessException ex) {
      log.warn(
          "idempotency Redis setIfAbsent unavailable — fail-closed: key={}, cause={}",
          idempotencyKey,
          ex.getMessage());
      writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, REDIS_UNAVAILABLE_BODY);
      return false;
    }
    if (Boolean.FALSE.equals(isNew)) {
      // C-2.10: setIfAbsent 失败后重新读一次，区分并发 PENDING 与刚落 DONE 两种情况。
      // 窗口内另一请求可能刚从 PENDING 晋升为 DONE（取不到锁但已处理完），
      // 前端应看到"已处理"而不是"稍后重试"，避免无谓轮询。
      // P0:此二次 get 必须同 catch DataAccessException(对齐 111-121 行 fail-closed 语义),
      // 否则 Redis 在 setIfAbsent ↔ get 间抖动会抛 DataAccessException 透传到
      // ExceptionHandler 返回 500 而非约定的 503,且保守回退 CONFLICT_PENDING_BODY
      // 让客户端走 retry 路径,避免误判"已处理"。
      String current;
      try {
        current = redisTemplate.opsForValue().get(redisKey);
      } catch (DataAccessException ex) {
        log.warn(
            "idempotency Redis follow-up GET unavailable — fail-closed (treat as pending):"
                + " key={}, cause={}",
            idempotencyKey,
            ex.getMessage());
        response.setHeader("Retry-After", "30");
        writeJson(response, HttpStatus.CONFLICT, CONFLICT_PENDING_BODY);
        return false;
      }
      if (DONE.equals(current)) {
        log.warn(
            "duplicate idempotency key rejected (raced to DONE): key={}, uri={}, tenant={}",
            idempotencyKey,
            request.getRequestURI(),
            tenantId);
        writeJson(response, HttpStatus.CONFLICT, CONFLICT_DONE_BODY);
      } else {
        log.warn(
            "concurrent idempotency key rejected (pending): key={}, uri={}, tenant={}",
            idempotencyKey,
            request.getRequestURI(),
            tenantId);
        // 给前端一个明确的 Retry-After 提示；30s 对齐 PENDING TTL
        response.setHeader("Retry-After", "30");
        writeJson(response, HttpStatus.CONFLICT, CONFLICT_PENDING_BODY);
      }
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
    return Texts.hasText(tenantId) ? tenantId.trim() : "_";
  }

  private void writeJson(HttpServletResponse response, HttpStatus httpStatus, String body)
      throws IOException {
    response.setStatus(httpStatus.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(body);
  }
}
