package io.github.pinpols.batch.console.support.web;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.utils.Texts;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
  private static final Duration STREAM_PENDING_TTL = Duration.ofMinutes(10);
  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String KEY_PREFIX = "console:idempotency:";
  private static final String PENDING = "PENDING";
  private static final String DONE = "DONE";
  // C-2.10: 区分 DONE / PENDING 两种 CONFLICT 场景，便于前端决定是"提示已处理"还是"稍后重试"
  private static final String CONFLICT_DONE_BODY = "{\"code\":\""
      + ResultCode.CONFLICT.code()
      + "\",\"message\":\"duplicate request, same Idempotency-Key already processed\"}";
  private static final String CONFLICT_PENDING_BODY = "{\"code\":\""
      + ResultCode.CONFLICT.code()
      + "\",\"message\":\"request currently being processed, retry after 30s with same"
      + " Idempotency-Key\"}";
  private static final String STREAM_CONFLICT_PENDING_BODY = "{\"code\":\""
      + ResultCode.CONFLICT.code()
      + "\",\"message\":\"AI stream currently being processed, retry after 600s with same"
      + " Idempotency-Key\"}";
  // R-4.1：Redis 不可达时幂等采用 fail-closed（返回 503），宁可拒绝也不双写。
  // 与限流的 fail-open 形成对照——前者保可用，后者保安全。
  private static final String REDIS_UNAVAILABLE_BODY = "{\"code\":\""
      + ResultCode.SERVICE_UNAVAILABLE.code()
      + "\",\"message\":\"idempotency store temporarily unavailable, safe to retry later\"}";
  private static final String MISSING_KEY_BODY = "{\"code\":\""
      + ResultCode.MISSING_IDEMPOTENCY_KEY.code()
      + "\",\"message\":\"this endpoint requires Idempotency-Key header\"}";

  /** Request attribute：记录本次请求使用的 Redis key，afterCompletion 时读取。 */
  private static final String ATTR_REDIS_KEY = "console.idempotency.redisKey";

  private static final String ATTR_DURABLE_KEY = "console.idempotency.durableKey";
  private static final String ATTR_TENANT_ID = "console.idempotency.tenantId";

  private final ConsoleIdempotencyStore idempotencyStore;
  private final ConsoleDurableIdempotencyStore durableIdempotencyStore;
  private final BatchSecurityProperties securityProperties;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {

    // 异步 SSE 分派会带同一个请求和键再次进入拦截器。
    // 初次分派持有占位，完成后只结算一次。
    if (request.getDispatcherType() == DispatcherType.ASYNC) {
      return true;
    }

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
    String redisKey = KEY_PREFIX
        + tenantId
        + ":"
        + method
        + ":"
        + request.getRequestURI()
        + ":"
        + idempotencyKey.trim();
    String durableKey = durableKey(redisKey);

    String existing;
    try {
      existing = idempotencyStore.get(redisKey);
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
    try {
      if (durableIdempotencyStore.isCompleted(tenantId, durableKey)) {
        log.warn(
            "duplicate idempotency key rejected by durable completion record: key={}, uri={}, tenant={}",
            idempotencyKey,
            request.getRequestURI(),
            tenantId);
        writeJson(response, HttpStatus.CONFLICT, CONFLICT_DONE_BODY);
        return false;
      }
    } catch (DataAccessException ex) {
      log.warn(
          "durable idempotency lookup unavailable — fail-closed: key={}, cause={}",
          idempotencyKey,
          ex.getMessage());
      writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, REDIS_UNAVAILABLE_BODY);
      return false;
    }

    // PENDING 阻止并发重复请求；流式请求的占位有效期比普通写请求更长。
    boolean streamRequest = request.getRequestURI().equals("/api/console/ai/chat/stream");
    Boolean isNew;
    try {
      Duration pendingTtl = streamRequest ? STREAM_PENDING_TTL : Duration.ofSeconds(30);
      isNew = idempotencyStore.setIfAbsent(redisKey, PENDING, pendingTtl);
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
        current = idempotencyStore.get(redisKey);
      } catch (DataAccessException ex) {
        log.warn(
            "idempotency Redis follow-up GET unavailable — fail-closed (treat as pending):"
                + " key={}, cause={}",
            idempotencyKey,
            ex.getMessage());
        response.setHeader("Retry-After", streamRequest ? "600" : "30");
        writeJson(
            response,
            HttpStatus.CONFLICT,
            streamRequest ? STREAM_CONFLICT_PENDING_BODY : CONFLICT_PENDING_BODY);
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
        // Retry-After 与占位有效期一致。
        response.setHeader("Retry-After", streamRequest ? "600" : "30");
        writeJson(
            response,
            HttpStatus.CONFLICT,
            streamRequest ? STREAM_CONFLICT_PENDING_BODY : CONFLICT_PENDING_BODY);
      }
      return false;
    }

    request.setAttribute(ATTR_REDIS_KEY, redisKey);
    request.setAttribute(ATTR_DURABLE_KEY, durableKey);
    request.setAttribute(ATTR_TENANT_ID, tenantId);
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    String redisKey = (String) request.getAttribute(ATTR_REDIS_KEY);
    if (redisKey == null) {
      return;
    }
    String durableKey = (String) request.getAttribute(ATTR_DURABLE_KEY);
    String tenantId = (String) request.getAttribute(ATTR_TENANT_ID);
    int status = response.getStatus();
    if (status >= 200 && status < 300 && ex == null) {
      // 成功：升级为 DONE，长 TTL 阻止重复提交
      try {
        idempotencyStore.set(redisKey, DONE, IDEMPOTENCY_TTL);
      } catch (DataAccessException redisException) {
        // 保留 PENDING，避免 Redis 恢复前立即放行重复请求；数据库完成态覆盖 Redis 短暂不可用窗口。
        log.error(
            "idempotency Redis completion write failed; durable completion will protect retries: key={}",
            redisKey,
            redisException);
      }
      try {
        durableIdempotencyStore.markCompleted(tenantId, durableKey);
      } catch (DataAccessException databaseException) {
        log.error(
            "durable idempotency completion write failed after successful mutation: key={}",
            redisKey,
            databaseException);
      }
    } else {
      // 失败：删除占位，允许安全重试
      try {
        idempotencyStore.delete(redisKey);
      } catch (DataAccessException deleteException) {
        log.warn(
            "idempotency pending marker cleanup failed; it will expire by TTL: key={}",
            redisKey,
            deleteException);
      }
    }
  }

  private static String durableKey(String redisKey) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(redisKey.getBytes(StandardCharsets.UTF_8));
      return "console-http:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
          "SHA-256 is required for durable idempotency keys", exception);
    }
  }

  private String resolveTenantId(HttpServletRequest request) {
    String tenantId = request.getHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER);
    return Texts.hasText(tenantId) ? tenantId.trim() : "_";
  }

  private void writeJson(HttpServletResponse response, HttpStatus httpStatus, String body)
      throws IOException {
    response.setStatus(httpStatus.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(body);
  }
}
