package io.github.pinpols.batch.console.support.web;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.idempotency.ConsoleDurableIdempotencyStore;
import io.github.pinpols.batch.console.config.ConsoleAsyncConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
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
  private static final String ATTR_OWNER = "console.idempotency.owner";

  private final ConsoleIdempotencyStore idempotencyStore;
  private final ConsoleDurableIdempotencyStore durableIdempotencyStore;
  private final BatchSecurityProperties securityProperties;
  private final TaskScheduler scheduler;
  private ScheduledFuture<?> renewal;
  private final ConcurrentMap<String, PendingLease> pendingLeases = new ConcurrentHashMap<>();

  private record PendingLease(String key, String value, Duration ttl) {}

  public ConsoleIdempotencyInterceptor(
      ConsoleIdempotencyStore idempotencyStore,
      ConsoleDurableIdempotencyStore durableIdempotencyStore,
      BatchSecurityProperties securityProperties,
      @Qualifier(ConsoleAsyncConfiguration.REALTIME_SCHEDULER) TaskScheduler scheduler) {
    this.idempotencyStore = idempotencyStore;
    this.durableIdempotencyStore = durableIdempotencyStore;
    this.securityProperties = securityProperties;
    this.scheduler = scheduler;
  }

  @PostConstruct
  void startRenewal() {
    // Console 未开启全局 @EnableScheduling，显式复用受 Spring 管理的调度器。
    renewal = scheduler.scheduleWithFixedDelay(
        this::renewPendingLeases, Instant.now().plusSeconds(10), Duration.ofSeconds(10));
  }

  @PreDestroy
  void stopRenewal() {
    if (EmptyChecks.isNotNull(renewal)) {
      renewal.cancel(false);
    }
    pendingLeases.clear();
  }

  /** 仅续租本请求仍拥有的占位；完成或失去所有权后不能复活旧占位。 */
  void renewPendingLeases() {
    pendingLeases.forEach((owner, lease) -> {
      try {
        if (!idempotencyStore.compareAndSet(lease.key(), lease.value(), lease.value(), lease.ttl())
            && pendingLeases.remove(owner, lease)) {
          log.error("idempotency pending ownership lost: key={}", lease.key());
        }
      } catch (DataAccessException ex) {
        log.error("idempotency pending renewal failed: key={}", lease.key(), ex);
      }
    });
  }

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

    String existing = readRedisState(redisKey, idempotencyKey, response);
    if (EmptyChecks.isNull(existing)
        && response.getStatus() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
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
    if (rejectWhenDurableCompleted(
        tenantId, durableKey, idempotencyKey, request.getRequestURI(), response)) {
      return false;
    }

    boolean streamRequest = request.getRequestURI().equals("/api/console/ai/chat/stream");
    String owner = PENDING + ":" + UUID.randomUUID();
    Duration pendingTtl = streamRequest ? STREAM_PENDING_TTL : Duration.ofSeconds(30);
    Boolean isNew = reservePending(redisKey, owner, pendingTtl, response);
    if (EmptyChecks.isNull(isNew)) {
      return false;
    }
    if (Boolean.FALSE.equals(isNew)) {
      rejectPending(redisKey, idempotencyKey, streamRequest, request, response);
      return false;
    }

    request.setAttribute(ATTR_REDIS_KEY, redisKey);
    request.setAttribute(ATTR_DURABLE_KEY, durableKey);
    request.setAttribute(ATTR_TENANT_ID, tenantId);
    request.setAttribute(ATTR_OWNER, owner);
    pendingLeases.put(owner, new PendingLease(redisKey, owner, pendingTtl));
    return true;
  }

  private String readRedisState(
      String redisKey, String idempotencyKey, HttpServletResponse response) throws IOException {
    try {
      return idempotencyStore.get(redisKey);
    } catch (DataAccessException ex) {
      // R-4.1 fail-closed：幂等拦截器拿不到 Redis 直接 503
      log.warn(
          "idempotency Redis GET unavailable — fail-closed: key={}, cause={}",
          idempotencyKey,
          SwallowedExceptionLogger.summary(ex));
      writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, REDIS_UNAVAILABLE_BODY);
      return null;
    }
  }

  private boolean rejectWhenDurableCompleted(
      String tenantId,
      String durableKey,
      String idempotencyKey,
      String requestUri,
      HttpServletResponse response)
      throws IOException {
    try {
      if (!durableIdempotencyStore.isCompleted(tenantId, durableKey)) {
        return false;
      }
      log.warn(
          "duplicate idempotency key rejected by durable completion record: key={}, uri={}, tenant={}",
          idempotencyKey,
          requestUri,
          tenantId);
      writeJson(response, HttpStatus.CONFLICT, CONFLICT_DONE_BODY);
      return true;
    } catch (DataAccessException ex) {
      log.warn(
          "durable idempotency lookup unavailable — fail-closed: key={}, cause={}",
          idempotencyKey,
          SwallowedExceptionLogger.summary(ex));
      writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, REDIS_UNAVAILABLE_BODY);
      return true;
    }
  }

  private Boolean reservePending(
      String redisKey, String owner, Duration pendingTtl, HttpServletResponse response)
      throws IOException {
    try {
      return idempotencyStore.setIfAbsent(redisKey, owner, pendingTtl);
    } catch (DataAccessException ex) {
      log.warn(
          "idempotency Redis setIfAbsent unavailable — fail-closed: owner={} cause={}",
          owner,
          ex.getClass().getSimpleName());
      writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, REDIS_UNAVAILABLE_BODY);
      return null;
    }
  }

  private void rejectPending(
      String redisKey,
      String idempotencyKey,
      boolean streamRequest,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    String current;
    try {
      current = idempotencyStore.get(redisKey);
    } catch (DataAccessException ex) {
      log.warn(
          "idempotency Redis follow-up GET unavailable — fail-closed (treat as pending):"
              + " key={}, cause={}",
          idempotencyKey,
          SwallowedExceptionLogger.summary(ex));
      response.setHeader("Retry-After", streamRequest ? "600" : "30");
      writeJson(
          response,
          HttpStatus.CONFLICT,
          streamRequest ? STREAM_CONFLICT_PENDING_BODY : CONFLICT_PENDING_BODY);
      return;
    }
    if (DONE.equals(current)) {
      log.warn(
          "duplicate idempotency key rejected (raced to DONE): key={}, uri={}",
          idempotencyKey,
          request.getRequestURI());
      writeJson(response, HttpStatus.CONFLICT, CONFLICT_DONE_BODY);
      return;
    }
    log.warn(
        "concurrent idempotency key rejected (pending): key={}, uri={}",
        idempotencyKey,
        request.getRequestURI());
    response.setHeader("Retry-After", streamRequest ? "600" : "30");
    writeJson(
        response,
        HttpStatus.CONFLICT,
        streamRequest ? STREAM_CONFLICT_PENDING_BODY : CONFLICT_PENDING_BODY);
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
    String owner = (String) request.getAttribute(ATTR_OWNER);
    if (EmptyChecks.isNull(owner)) {
      return;
    }
    pendingLeases.remove(owner);
    int status = response.getStatus();
    if (status >= 200 && status < 300 && ex == null) {
      // 成功：升级为 DONE，长 TTL 阻止重复提交
      try {
        if (!idempotencyStore.compareAndSet(redisKey, owner, DONE, IDEMPOTENCY_TTL)) {
          log.error("idempotency completion no longer owns pending marker: key={}", redisKey);
        }
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
        idempotencyStore.deleteIfValue(redisKey, owner);
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
