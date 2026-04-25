package com.example.batch.orchestrator.infrastructure.quota;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.redis.BatchRedisKeys;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.domain.scheduler.QuotaResetPolicy;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis Lua 脚本实现的 quota 状态服务。{@code batch.quota.runtime-store=redis}（默认）启用。
 *
 * <p><b>关键设计</b>：
 * <ul>
 *   <li>每 (tenant, scope, owner) 对应一个 Redis Hash（key 见 {@link
 *       BatchRedisKeys#quotaState(String, String, String)}），字段含 peakBorrowedCount / windowStartedAt /
 *       windowExpiresAt / lastResetAt / quotaResetPolicy。
 *   <li>{@link #evaluateAndReserve} 走单条 Lua 脚本——窗口判定、peak CAS 抬升、TTL 续命一次原子完成；
 *       多 orchestrator 实例并发也不会互相覆盖。
 *   <li>时区敏感的 calendarDay 边界由 Java 侧用 {@link BatchTimezoneProvider} 计算后透传给 Lua，
 *       避免 Lua server 时区与平台默认时区不一致。
 *   <li>窗口 TTL = 窗口剩余时长 + 60s 缓冲，过期自动回收，不需要后台 reconcile 调度器。
 *   <li>Redis 故障 fail-open（返回 allow + WARN）：限流故障不应放大成业务故障。
 * </ul>
 *
 * <p><b>租户索引</b>：成功 reserve 后把 owner 标识 SADD 入 {@link BatchRedisKeys#quotaStateIndex(String)}，
 * 供 console / snapshot 调度器按租户列出所有 owner，避免全库 SCAN。
 */
@Slf4j
@Service
@ConditionalOnProperty(
    name = "batch.quota.runtime-store",
    havingValue = "redis",
    matchIfMissing = true)
public class RedisQuotaRuntimeStateService implements QuotaRuntimeStateService {

  // 索引项 ttl：14 天，足以覆盖任何业务窗口；过期由 Redis 自然清理
  private static final Duration INDEX_TTL = Duration.ofDays(14);

  // Lua 脚本：evaluateAndReserve 的原子实现
  // 返回值: [allowed(1/0), peakBorrowedCount, windowStartedAtMillis, windowExpiresAtMillis]
  private static final String EVAL_RESERVE_SCRIPT =
      """
      local nowMillis = tonumber(ARGV[1])
      local baseCap = tonumber(ARGV[2])
      local burst = tonumber(ARGV[3])
      local requested = tonumber(ARGV[4])
      local currentActive = tonumber(ARGV[5])
      local policy = ARGV[6]
      local calendarStart = tonumber(ARGV[7])
      local calendarEnd = tonumber(ARGV[8])
      local slidingHours = tonumber(ARGV[9])
      local key = KEYS[1]

      local fields = redis.call('HMGET', key,
        'peakBorrowedCount','windowStartedAt','windowExpiresAt','lastResetAt','quotaResetPolicy')
      local peak = tonumber(fields[1]) or 0
      local winStart = tonumber(fields[2])
      local winEnd = tonumber(fields[3])
      local lastReset = tonumber(fields[4])
      local prevPolicy = fields[5]

      local needsWrite = false
      if prevPolicy ~= policy then
        needsWrite = true
      end

      if policy == 'CALENDAR_DAY' then
        if (not winStart) or winStart ~= calendarStart or (not winEnd) or nowMillis >= winEnd then
          winStart = calendarStart
          winEnd = calendarEnd
          peak = 0
          lastReset = nowMillis
          needsWrite = true
        end
      elseif policy == 'SLIDING_WINDOW' then
        if (not winStart) or (not winEnd) or nowMillis >= winEnd then
          winStart = nowMillis
          winEnd = nowMillis + slidingHours * 3600000
          peak = 0
          lastReset = nowMillis
          needsWrite = true
        end
      end

      local borrowed = currentActive + requested - baseCap
      if borrowed < 0 then borrowed = 0 end

      local allowed = 1
      if borrowed > burst then
        allowed = 0
      elseif borrowed > peak then
        peak = borrowed
        needsWrite = true
      end

      if needsWrite then
        redis.call('HSET', key,
          'peakBorrowedCount', peak,
          'windowStartedAt', winStart or 0,
          'windowExpiresAt', winEnd or 0,
          'lastResetAt', lastReset or nowMillis,
          'quotaResetPolicy', policy,
          'updatedAt', nowMillis)
        if winEnd and winEnd > nowMillis then
          local ttl = (winEnd - nowMillis) + 60000
          redis.call('PEXPIRE', key, ttl)
        end
      end

      return { allowed, peak, winStart or 0, winEnd or 0 }
      """;

  // describe 只读不写，但仍要触发窗口过期判定（在脚本里）。简化：直接 HGETALL，过期由 evaluateAndReserve 触发
  // 同步重置；这里只做 nowMillis < windowExpiresAt 时返回真实数据，否则视为已过期返回零值。

  private final OrchestratorRedisSupport redis;
  private final BatchTimezoneProvider timezoneProvider;

  public RedisQuotaRuntimeStateService(
      OrchestratorRedisSupport redis, BatchTimezoneProvider timezoneProvider) {
    this.redis = redis;
    this.timezoneProvider = timezoneProvider;
  }

  @Override
  public ResourceCheck evaluateAndReserve(QuotaReservationRequest request) {
    if (request == null
        || request.owner() == null
        || !Texts.hasText(request.owner().tenantId())
        || !Texts.hasText(request.owner().quotaScope())
        || !Texts.hasText(request.owner().ownerCode())) {
      return ResourceCheck.allow();
    }
    if (request.policy() == null || request.policy().baseCap() <= 0) {
      return ResourceCheck.allow();
    }

    int normalizedBurst = Math.max(0, request.policy().burstLimit());
    int normalizedRequested = Math.max(1, request.requestedCount());
    QuotaResetPolicy policy = QuotaResetPolicy.from(request.policy().quotaResetPolicy());

    if (!policy.isRuntimeManaged() || normalizedBurst == 0) {
      long cap = (long) request.policy().baseCap() + normalizedBurst;
      if (request.currentActiveCount() + normalizedRequested > cap) {
        return waitForCapacity(request);
      }
      return ResourceCheck.allow();
    }

    Instant now = Instant.now();
    long nowMillis = now.toEpochMilli();
    long calendarStartMillis = 0L;
    long calendarEndMillis = 0L;
    if (policy == QuotaResetPolicy.CALENDAR_DAY) {
      ZonedDateTime nowZdt = now.atZone(timezoneProvider.defaultZone());
      Instant start = QuotaResetPolicy.startOfCalendarDay(nowZdt).toInstant();
      Instant end = start.plus(Duration.ofDays(1));
      calendarStartMillis = start.toEpochMilli();
      calendarEndMillis = end.toEpochMilli();
    }
    int slidingHours = Math.max(1, request.policy().slidingWindowHours());

    String key =
        BatchRedisKeys.quotaState(
            request.owner().tenantId(),
            request.owner().quotaScope(),
            request.owner().ownerCode());

    List<Object> result;
    try {
      result =
          redis.evalList(
              EVAL_RESERVE_SCRIPT,
              key,
              Long.toString(nowMillis),
              Integer.toString(request.policy().baseCap()),
              Integer.toString(normalizedBurst),
              Integer.toString(normalizedRequested),
              Long.toString(request.currentActiveCount()),
              policy.name(),
              Long.toString(calendarStartMillis),
              Long.toString(calendarEndMillis),
              Integer.toString(slidingHours));
    } catch (DataAccessException ex) {
      // Redis 故障 fail-open：放行 + WARN，避免限流故障扩散为业务故障；下一轮自然恢复
      log.warn(
          "redis quota evaluateAndReserve failed; failing open: tenant={}, scope={}, owner={},"
              + " cause={}",
          request.owner().tenantId(),
          request.owner().quotaScope(),
          request.owner().ownerCode(),
          ex.getMessage());
      return ResourceCheck.allow();
    }

    if (result == null || result.isEmpty()) {
      return ResourceCheck.allow();
    }
    long allowed = parseLong(asString(result.get(0)));
    if (allowed != 1L) {
      return waitForCapacity(request);
    }

    // 命中即把 owner 加入租户索引，供 snapshot 调度器枚举
    try {
      String indexKey = BatchRedisKeys.quotaStateIndex(request.owner().tenantId());
      String indexEntry = request.owner().quotaScope() + ":" + request.owner().ownerCode();
      StringRedisTemplate template = redis.redisTemplate();
      template.opsForSet().add(indexKey, indexEntry);
      template.expire(indexKey, INDEX_TTL);
    } catch (DataAccessException ex) {
      // 索引失败不影响主流程；snapshot 拿不到就等下次
      log.debug("redis quota index update failed: {}", ex.getMessage());
    }
    return ResourceCheck.allow();
  }

  @Override
  public QuotaRuntimeSnapshot describe(QuotaDescribeRequest request) {
    String tenantId = request.owner().tenantId();
    String quotaScope = request.owner().quotaScope();
    String ownerCode = request.owner().ownerCode();
    int burstLimit = Math.max(0, request.burstLimit());
    QuotaResetPolicy policy = QuotaResetPolicy.from(request.quotaResetPolicy());

    if (!Texts.hasText(tenantId) || !Texts.hasText(quotaScope) || !Texts.hasText(ownerCode)) {
      return new QuotaRuntimeSnapshot(
          policy.name(), burstLimit, 0, burstLimit, null, null, null);
    }
    if (!policy.isRuntimeManaged() || burstLimit <= 0) {
      return new QuotaRuntimeSnapshot(
          policy.name(), burstLimit, 0, burstLimit, null, null, null);
    }

    String key = BatchRedisKeys.quotaState(tenantId, quotaScope, ownerCode);
    Map<Object, Object> entries;
    try {
      entries = redis.entries(key);
    } catch (DataAccessException ex) {
      log.warn(
          "redis quota describe failed; returning empty snapshot: tenant={}, scope={}, owner={},"
              + " cause={}",
          tenantId,
          quotaScope,
          ownerCode,
          ex.getMessage());
      return new QuotaRuntimeSnapshot(
          policy.name(), burstLimit, 0, burstLimit, null, null, null);
    }
    if (entries == null || entries.isEmpty()) {
      return new QuotaRuntimeSnapshot(policy.name(), burstLimit, 0, burstLimit, null, null, null);
    }

    long nowMillis = Instant.now().toEpochMilli();
    long winEndMillis = parseLong(asString(entries.get("windowExpiresAt")));
    long winStartMillis = parseLong(asString(entries.get("windowStartedAt")));
    long lastResetMillis = parseLong(asString(entries.get("lastResetAt")));
    int peak = (int) parseLong(asString(entries.get("peakBorrowedCount")));
    if (winEndMillis > 0 && nowMillis >= winEndMillis) {
      // 窗口已过期：实际可用 burst 全量复位（Redis 端 TTL 到期会清掉，这里读路径同步语义）
      return new QuotaRuntimeSnapshot(
          policy.name(),
          burstLimit,
          0,
          burstLimit,
          winStartMillis > 0 ? Instant.ofEpochMilli(winStartMillis) : null,
          Instant.ofEpochMilli(winEndMillis),
          lastResetMillis > 0 ? Instant.ofEpochMilli(lastResetMillis) : null);
    }
    int remaining = Math.max(0, burstLimit - peak);
    return new QuotaRuntimeSnapshot(
        policy.name(),
        burstLimit,
        peak,
        remaining,
        winStartMillis > 0 ? Instant.ofEpochMilli(winStartMillis) : null,
        winEndMillis > 0 ? Instant.ofEpochMilli(winEndMillis) : null,
        lastResetMillis > 0 ? Instant.ofEpochMilli(lastResetMillis) : null);
  }

  /** Redis 实现下窗口由 TTL 自动回收，无需调度器 reconcile。 */
  @Override
  public void reconcileExpiredStates(int slidingWindowHours) {
    // no-op
  }

  private static long parseLong(String s) {
    if (s == null || s.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException ex) {
      return 0L;
    }
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  private static ResourceCheck waitForCapacity(QuotaReservationRequest request) {
    QuotaReservationReason reason = request.reason();
    return ResourceCheck.waitForCapacity(
        reason == null ? null : reason.reasonCode(),
        reason == null ? null : reason.reasonMessage());
  }
}
