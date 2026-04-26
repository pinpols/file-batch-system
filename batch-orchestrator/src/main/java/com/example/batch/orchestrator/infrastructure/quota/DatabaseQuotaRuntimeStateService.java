package com.example.batch.orchestrator.infrastructure.quota;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import com.example.batch.orchestrator.domain.entity.QuotaRuntimeStateRecord;
import com.example.batch.orchestrator.domain.scheduler.QuotaResetPolicy;
import com.example.batch.orchestrator.domain.scheduler.ResourceCheck;
import com.example.batch.orchestrator.repository.QuotaRuntimeStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG @Version 乐观锁实现的 quota 状态服务。属于遗留实现，{@code batch.quota.runtime-store=database} 时启用；新部署默认走 {@link
 * RedisQuotaRuntimeStateService}。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "batch.quota.runtime-store", havingValue = "database")
public class DatabaseQuotaRuntimeStateService implements QuotaRuntimeStateService {

  private final QuotaRuntimeStateRepository quotaRuntimeStateRepository;
  private final BatchTimezoneProvider timezoneProvider;
  // C-2.8：自引用，供 reconcileExpiredStates 内触发 REQUIRES_NEW 子事务。
  // 通过 ObjectProvider 打破 bean 循环依赖，并拿到 Spring 代理后的实例。
  private final ObjectProvider<DatabaseQuotaRuntimeStateService> selfProvider;

  public DatabaseQuotaRuntimeStateService(
      QuotaRuntimeStateRepository quotaRuntimeStateRepository,
      BatchTimezoneProvider timezoneProvider,
      ObjectProvider<DatabaseQuotaRuntimeStateService> selfProvider) {
    this.quotaRuntimeStateRepository = quotaRuntimeStateRepository;
    this.timezoneProvider = timezoneProvider;
    this.selfProvider = selfProvider;
  }

  @Override
  @Transactional
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
    QuotaRuntimeStateRecord state =
        loadOrCreate(
            new StateContext(
                request.owner(), policy.name(), now, request.policy().slidingWindowHours()));
    state = refreshState(state, policy, now, request.policy().slidingWindowHours());

    int borrowedNeeded =
        Math.max(
            0,
            (int)
                (request.currentActiveCount() + normalizedRequested - request.policy().baseCap()));
    if (borrowedNeeded == 0) {
      quotaRuntimeStateRepository.save(state);
      return ResourceCheck.allow();
    }
    if (borrowedNeeded > normalizedBurst) {
      return waitForCapacity(request);
    }
    int currentPeak =
        state.peakBorrowedCount() == null ? 0 : Math.max(0, state.peakBorrowedCount());
    if (borrowedNeeded > currentPeak) {
      state =
          state.withPeakAndTimestamps(
              borrowedNeeded, state.lastResetAt() == null ? now : state.lastResetAt(), now);
    }
    // M-4: 只要使用了突发容量就必须持久化状态，否则 lastUpdatedAt 漂移导致窗口过期误判
    quotaRuntimeStateRepository.save(state);
    return ResourceCheck.allow();
  }

  @Override
  @Transactional(readOnly = true)
  public QuotaRuntimeSnapshot describe(QuotaDescribeRequest request) {
    String tenantId = request.owner().tenantId();
    String quotaScope = request.owner().quotaScope();
    String ownerCode = request.owner().ownerCode();
    String quotaResetPolicy = request.quotaResetPolicy();
    int burstLimit = request.burstLimit();
    int slidingWindowHours = request.slidingWindowHours();
    if (!Texts.hasText(tenantId) || !Texts.hasText(quotaScope) || !Texts.hasText(ownerCode)) {
      return new QuotaRuntimeSnapshot(
          normalizePolicy(quotaResetPolicy),
          Math.max(0, burstLimit),
          0,
          Math.max(0, burstLimit),
          null,
          null,
          null);
    }
    QuotaResetPolicy policy = QuotaResetPolicy.from(quotaResetPolicy);
    if (!policy.isRuntimeManaged() || burstLimit <= 0) {
      return new QuotaRuntimeSnapshot(
          policy.name(), Math.max(0, burstLimit), 0, Math.max(0, burstLimit), null, null, null);
    }
    QuotaRuntimeStateRecord state =
        quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
            tenantId, quotaScope, ownerCode);
    if (state == null) {
      return new QuotaRuntimeSnapshot(policy.name(), burstLimit, 0, burstLimit, null, null, null);
    }
    state = refreshState(state, policy, Instant.now(), slidingWindowHours, false);
    if (state == null) {
      return new QuotaRuntimeSnapshot(policy.name(), burstLimit, 0, burstLimit, null, null, null);
    }
    int peakBorrowed =
        state.peakBorrowedCount() == null ? 0 : Math.max(0, state.peakBorrowedCount());
    int remaining = Math.max(0, burstLimit - peakBorrowed);
    return new QuotaRuntimeSnapshot(
        policy.name(),
        burstLimit,
        peakBorrowed,
        remaining,
        state.windowStartedAt(),
        state.windowExpiresAt(),
        state.lastResetAt());
  }

  /**
   * C-2.8：外层方法不包事务，逐条过期状态走 REQUIRES_NEW 子事务（{@link #reconcileOne}）， 单条 CAS 冲突只跳过该条，不让一次乐观锁失败扳倒整批
   * reconcile。
   */
  @Override
  public void reconcileExpiredStates(int slidingWindowHours) {
    Instant now = Instant.now();
    List<QuotaRuntimeStateRecord> expired = quotaRuntimeStateRepository.findExpired(now);
    DatabaseQuotaRuntimeStateService self = selfProvider.getObject();
    for (QuotaRuntimeStateRecord state : expired) {
      try {
        self.reconcileOne(state, now, slidingWindowHours);
      } catch (OptimisticLockingFailureException conflict) {
        // 另一个节点刚抢先重置了该状态，下一 tick 扫不到即自愈，无需重试
        log.debug(
            "quota state optimistic lock conflict during reconcile; will retry next tick:"
                + " tenantId={}, scope={}, owner={}, id={}",
            state.tenantId(),
            state.quotaScope(),
            state.ownerCode(),
            state.id());
      } catch (RuntimeException ex) {
        // 单条异常只影响自己；记录后继续扫其他条
        log.warn(
            "quota state reconcile failed: tenantId={}, scope={}, owner={}, id={}, cause={}",
            state.tenantId(),
            state.quotaScope(),
            state.ownerCode(),
            state.id(),
            ex.getMessage());
      }
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reconcileOne(QuotaRuntimeStateRecord state, Instant now, int slidingWindowHours) {
    QuotaResetPolicy policy = QuotaResetPolicy.from(state.quotaResetPolicy());
    refreshState(state, policy, now, slidingWindowHours, true);
  }

  private record StateContext(
      QuotaReservationOwner owner, String quotaResetPolicy, Instant now, int slidingWindowHours) {}

  private QuotaRuntimeStateRecord loadOrCreate(StateContext ctx) {
    String tenantId = ctx.owner().tenantId();
    String quotaScope = ctx.owner().quotaScope();
    String ownerCode = ctx.owner().ownerCode();
    QuotaRuntimeStateRecord state =
        quotaRuntimeStateRepository.findFirstByTenantIdAndQuotaScopeAndOwnerCode(
            tenantId, quotaScope, ownerCode);
    if (state != null) {
      return state;
    }
    QuotaRuntimeStateRecord created =
        new QuotaRuntimeStateRecord(
            null,
            tenantId,
            quotaScope,
            ownerCode,
            ctx.quotaResetPolicy(),
            null,
            null,
            0,
            null,
            ctx.now(),
            ctx.now(),
            null);
    created =
        refreshState(
            created,
            QuotaResetPolicy.from(ctx.quotaResetPolicy()),
            ctx.now(),
            ctx.slidingWindowHours(),
            false);
    return created;
  }

  private QuotaRuntimeStateRecord refreshState(
      QuotaRuntimeStateRecord state, QuotaResetPolicy policy, Instant now, int slidingWindowHours) {
    return refreshState(state, policy, now, slidingWindowHours, true);
  }

  private QuotaRuntimeStateRecord refreshState(
      QuotaRuntimeStateRecord state,
      QuotaResetPolicy policy,
      Instant now,
      int slidingWindowHours,
      boolean persist) {
    if (state == null) {
      return null;
    }
    String normalizedPolicy = policy == null ? QuotaResetPolicy.NONE.name() : policy.name();
    boolean changed = false;
    if (!normalizedPolicy.equalsIgnoreCase(state.quotaResetPolicy())) {
      state =
          state.withRefresh(
              normalizedPolicy,
              state.windowStartedAt(),
              state.windowExpiresAt(),
              state.peakBorrowedCount() == null ? 0 : state.peakBorrowedCount(),
              state.lastResetAt());
      changed = true;
    }
    if (!policy.isRuntimeManaged()) {
      if (state.peakBorrowedCount() == null
          || state.peakBorrowedCount() != 0
          || state.windowStartedAt() != null
          || state.windowExpiresAt() != null) {
        state = state.withRefresh(normalizedPolicy, null, null, 0, state.lastResetAt());
        changed = true;
      }
      if (changed && persist) {
        quotaRuntimeStateRepository.save(state);
      }
      return state;
    }

    if (policy == QuotaResetPolicy.CALENDAR_DAY) {
      // 自然日窗口使用平台默认时区（batch.timezone.default-zone），与调度日历时区语义一致；
      // 避免 JVM default 在容器间漂移导致同一租户跨节点看到不同的"自然日"边界。
      ZonedDateTime nowZdt = now.atZone(timezoneProvider.defaultZone());
      Instant windowStart = QuotaResetPolicy.startOfCalendarDay(nowZdt).toInstant();
      Instant windowEnd = windowStart.plus(Duration.ofDays(1));
      if (!windowStart.equals(state.windowStartedAt())
          || state.windowExpiresAt() == null
          || !now.isBefore(state.windowExpiresAt())) {
        state = state.withRefresh(normalizedPolicy, windowStart, windowEnd, 0, now);
        changed = true;
      }
    } else if (policy == QuotaResetPolicy.SLIDING_WINDOW) {
      int normalizedHours = Math.max(1, slidingWindowHours);
      if (state.windowStartedAt() == null
          || state.windowExpiresAt() == null
          || !now.isBefore(state.windowExpiresAt())) {
        state =
            state.withRefresh(
                normalizedPolicy, now, now.plus(Duration.ofHours(normalizedHours)), 0, now);
        changed = true;
      }
    }
    if (changed && persist) {
      quotaRuntimeStateRepository.save(state);
    }
    return state;
  }

  private String normalizePolicy(String policy) {
    return QuotaResetPolicy.from(policy).name();
  }

  private ResourceCheck waitForCapacity(QuotaReservationRequest request) {
    QuotaReservationReason reason = request.reason();
    return ResourceCheck.waitForCapacity(
        reason == null ? null : reason.reasonCode(),
        reason == null ? null : reason.reasonMessage());
  }
}
