package io.github.pinpols.batch.trigger.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.mapper.auth.TriggerApiKeyAuthMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Verifies tenant API keys and binds the authenticated key name to an external event source. */
@Component
public class TriggerApiKeyVerifier {

  public static final String EVENT_SCOPE = "trigger.event";
  public static final int KEY_PREFIX_LENGTH = 8;

  private static final Duration CACHE_TTL = Duration.ofMinutes(1);
  private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);
  private static final long TOUCH_INTERVAL_NANOS = TOUCH_INTERVAL.toNanos();

  private final TriggerApiKeyAuthMapper mapper;
  private final InstantSource instantSource;
  private final Cache<String, TriggerApiKeyPrincipal> verifiedKeys =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(CACHE_TTL).build();
  private final ConcurrentHashMap<Long, AtomicLong> lastTouchNanos = new ConcurrentHashMap<>();

  @Autowired
  public TriggerApiKeyVerifier(TriggerApiKeyAuthMapper mapper) {
    this(mapper, InstantSource.system());
  }

  TriggerApiKeyVerifier(TriggerApiKeyAuthMapper mapper, InstantSource instantSource) {
    this.mapper = mapper;
    this.instantSource = instantSource;
  }

  public Optional<TriggerApiKeyPrincipal> authenticate(String rawKey, String tenantId) {
    if (EmptyChecks.isNull(rawKey)
        || rawKey.length() < KEY_PREFIX_LENGTH
        || EmptyChecks.isNull(tenantId)
        || EmptyChecks.isBlank(tenantId)) {
      return Optional.empty();
    }
    String cacheKey = ApiKeyHasher.legacySha256Hex(rawKey + "|" + tenantId);
    TriggerApiKeyPrincipal cached = verifiedKeys.getIfPresent(cacheKey);
    if (EmptyChecks.isNotNull(cached) && !isExpired(cached.expiresAt())) {
      touch(cached.id(), cached.tenantId());
      return Optional.of(cached);
    }
    verifiedKeys.invalidate(cacheKey);

    String prefix = rawKey.substring(0, KEY_PREFIX_LENGTH);
    List<TriggerApiKeyCredential> candidates =
        mapper.findActiveCandidatesByPrefixAndTenant(prefix, tenantId);
    if (EmptyChecks.isNull(candidates)) {
      return Optional.empty();
    }
    for (TriggerApiKeyCredential candidate : candidates) {
      if (!Boolean.TRUE.equals(candidate.enabled())
          || isExpired(candidate.expiresAt())
          || !tenantId.equals(candidate.tenantId())
          || !ApiKeyHasher.verify(
              rawKey, candidate.keyHash(), candidate.salt(), candidate.keyHashAlgo())) {
        continue;
      }
      TriggerApiKeyPrincipal principal = new TriggerApiKeyPrincipal(
          candidate.id(),
          candidate.tenantId(),
          candidate.keyName(),
          candidate.scopes(),
          candidate.expiresAt());
      verifiedKeys.put(cacheKey, principal);
      touch(principal.id(), principal.tenantId());
      return Optional.of(principal);
    }
    return Optional.empty();
  }

  public boolean authorizesExternalEvent(
      TriggerApiKeyPrincipal principal, String tenantId, String externalEventSource) {
    if (EmptyChecks.isNull(principal)
        || EmptyChecks.isNull(tenantId)
        || EmptyChecks.isNull(externalEventSource)
        || !tenantId.equals(principal.tenantId())
        || !externalEventSource.equals(principal.keyName())
        || isExpired(principal.expiresAt())) {
      return false;
    }
    return Arrays.stream(Optional.ofNullable(principal.scopes()).orElse("").split("[,\\s]+"))
        .filter(scope -> EmptyChecks.isNotBlank(scope))
        .anyMatch(EVENT_SCOPE::equals);
  }

  private boolean isExpired(Instant expiresAt) {
    return EmptyChecks.isNotNull(expiresAt) && !instantSource.instant().isBefore(expiresAt);
  }

  private void touch(Long id, String tenantId) {
    if (EmptyChecks.isNull(id) || EmptyChecks.isNull(tenantId)) {
      return;
    }
    long now = System.nanoTime();
    AtomicLong last = lastTouchNanos.computeIfAbsent(id, ignored -> new AtomicLong(Long.MIN_VALUE));
    long previous = last.get();
    if ((previous == Long.MIN_VALUE || now - previous >= TOUCH_INTERVAL_NANOS)
        && last.compareAndSet(previous, now)) {
      mapper.touchLastUsedAt(id, tenantId);
    }
  }

  void clearCacheForTests() {
    verifiedKeys.invalidateAll();
    lastTouchNanos.clear();
  }
}
