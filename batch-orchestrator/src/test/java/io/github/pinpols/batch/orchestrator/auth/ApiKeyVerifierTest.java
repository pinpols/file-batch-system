package io.github.pinpols.batch.orchestrator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.orchestrator.mapper.auth.ApiKeyAuthMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@ExtendWith(MockitoExtension.class)
@DisplayName("密钥校验器,覆盖候选行命中,作用域判定,结果缓存与最近使用时间节流")
class ApiKeyVerifierTest {

  @Mock
  private ApiKeyAuthMapper mapper;

  private ApiKeyVerifier verifier;

  /** 可推进的假时钟(nanos),同时驱动 Caffeine TTL 逐出、touch 节流窗口与 expires_at 复查墙钟。 */
  private final AtomicLong nanos = new AtomicLong(0);

  private final Ticker fakeTicker = nanos::get;

  /** 与 fakeTicker 同源的假墙钟:EPOCH + nanos。 */
  private final InstantSource fakeClock = () -> Instant.EPOCH.plusNanos(nanos.get());

  @BeforeEach
  void setup() {
    // 单测用假 ticker/墙钟构造器,避免依赖 Spring 容器;需要真实时钟的默认路径由单参构造器覆盖。
    verifier = new ApiKeyVerifier(mapper, fakeTicker, fakeClock);
  }

  private void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  // 至少 8 字符 (KEY_PREFIX_LEN)
  private static final String RAW_KEY = "bk_AAAA-secret-token";
  private static final String PREFIX = RAW_KEY.substring(0, ApiKeyVerifier.KEY_PREFIX_LEN);

  private static ApiKeyEntity legacyRow(long id, String tenant, String scopes, String rawKey) {
    String hash = ApiKeyHasher.legacySha256Hex(rawKey);
    return new ApiKeyEntity(id, tenant, "kn", scopes, true, null, hash, null, "sha256");
  }

  private static ApiKeyEntity pbkdf2Row(long id, String tenant, String scopes, String rawKey) {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf(rawKey);
    return new ApiKeyEntity(id, tenant, "kn", scopes, true, null, sh.hash(), sh.salt(), "pbkdf2");
  }

  @Test
  @DisplayName("按密钥前缀与租户查到新版加盐派生哈希候选行且摘要比对一致时放行")
  void shouldMatchSaltedHashRow_whenPrefixAndTenantHit() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(rec));

    Optional<ApiKeyEntity> result = verifier.verify(RAW_KEY, "tx");

    assertThat(result).contains(rec);
    verify(mapper).findActiveCandidatesByPrefixAndTenant(PREFIX, "tx");
  }

  @Test
  @DisplayName("旧版摘要哈希候选行比对一致时放行并同步触发一次哈希升级改写")
  void shouldMatchLegacyHashRowAndTriggerUpgrade_whenHashMatches() {
    ApiKeyEntity legacy = legacyRow(42L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(legacy));

    Optional<ApiKeyEntity> result = verifier.verify(RAW_KEY, "tx");

    assertThat(result).contains(legacy);
    // 触发了 upgrade(同步在测试里执行,@Async 无 spring proxy)
    verify(mapper, times(1))
        .upgradeHashIfLegacy(eq(42L), eq(legacy.keyHash()), anyString(), anyString());
  }

  @Test
  @DisplayName("候选行使用新版加盐派生哈希时不触发哈希升级改写")
  void shouldNotUpgradeHash_whenRowUsesSaltedHash() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(rec));

    verifier.verify(RAW_KEY, "tx");

    verify(mapper, never()).upgradeHashIfLegacy(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("校验通过时写入一次最近使用时间")
  void shouldWriteLastUsedTime_whenVerifySucceeds() {
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(pbkdf2Row(7L, "tx", "*", RAW_KEY)));

    verifier.verify(RAW_KEY, "tx");

    verify(mapper, times(1)).touchLastUsedAt(7L);
  }

  @Test
  @DisplayName("查无候选行时返回空且不写最近使用时间")
  void shouldReturnEmpty_whenNoCandidateRowMatches() {
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of());

    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    verify(mapper, never()).touchLastUsedAt(anyLong());
  }

  @Test
  @DisplayName("候选行存在但摘要比对不一致时拒绝放行,且不写最近使用时间,不触发升级")
  void shouldReturnEmpty_whenCandidateHashMismatches() {
    // 候选行存在但 hash 是另一 key 的 — 不应放行,也不触发 touch / upgrade
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", "bk_OTHER-secret");
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));

    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    verify(mapper, never()).touchLastUsedAt(anyLong());
    verify(mapper, never()).upgradeHashIfLegacy(anyLong(), any(), any(), any());
  }

  @Test
  @DisplayName("密钥缺失或为空白文本时直接拒绝且不查询候选行")
  void shouldRejectKey_whenKeyIsNullOrBlank() {
    assertThat(verifier.verify(null, "tx")).isEmpty();
    assertThat(verifier.verify("", "tx")).isEmpty();
    assertThat(verifier.verify("  ", "tx")).isEmpty();
    verify(mapper, never()).findActiveCandidatesByPrefixAndTenant(any(), any());
  }

  @Test
  @DisplayName("租户标识缺失或为空串时直接拒绝且不查询候选行")
  void shouldRejectKey_whenTenantIsNullOrBlank() {
    assertThat(verifier.verify(RAW_KEY, null)).isEmpty();
    assertThat(verifier.verify(RAW_KEY, "")).isEmpty();
    verify(mapper, never()).findActiveCandidatesByPrefixAndTenant(any(), any());
  }

  @Test
  @DisplayName("密钥长度不足前缀长度时直接拒绝,避免截取前缀时越界")
  void shouldRejectKey_whenKeyShorterThanPrefixLength() {
    // 短于 KEY_PREFIX_LEN(8) 直接拒;防 substring 越界
    assertThat(verifier.verify("abc", "tx")).isEmpty();
    verify(mapper, never()).findActiveCandidatesByPrefixAndTenant(any(), any());
  }

  // ─── ADR-035 scope 校验 ────────────────────────────────────────────────

  @Test
  @DisplayName("密钥作用域不含所需作用域时拒绝放行")
  void shouldRejectKey_whenRequiredScopeMissing() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "read.only", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    assertThat(verifier.verifyWithScope(RAW_KEY, "tx", "worker.execute")).isEmpty();
  }

  @Test
  @DisplayName("密钥作用域为通配符时任意所需作用域都放行")
  void shouldAcceptKey_whenScopeIsWildcard() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    assertThat(verifier.verifyWithScope(RAW_KEY, "tx", "worker.execute")).isPresent();
  }

  @Test
  @DisplayName("密钥作用域串含所需作用域时放行")
  void shouldAcceptKey_whenScopeListContainsRequiredScope() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "read, worker.execute", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    assertThat(verifier.verifyWithScope(RAW_KEY, "tx", "worker.execute")).isPresent();
  }

  @Test
  @DisplayName("只读密钥命中任一所需读类作用域时放行,但不满足纯执行作用域")
  void shouldAcceptReadOnlyKey_whenAnyRequestedScopeMatches() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "worker.read", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    // 只读 key 命中读 scope（read 或 execute 任一即可）
    assertThat(verifier.verifyWithAnyScope(
            RAW_KEY, "tx", ApiKeyVerifier.SCOPE_WORKER_READ, ApiKeyVerifier.SCOPE_WORKER_EXECUTE))
        .isPresent();
    // 但只读 key 不满足纯写 scope
    assertThat(verifier.verifyWithScope(RAW_KEY, "tx", ApiKeyVerifier.SCOPE_WORKER_EXECUTE))
        .isEmpty();
  }

  @Test
  @DisplayName("执行类密钥覆盖读作用域时同样放行")
  void shouldAcceptExecuteKey_whenReadScopeIsCovered() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "worker.execute", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(anyString(), anyString()))
        .thenReturn(List.of(rec));
    // execute 是 read 的超集：可写者必可读
    assertThat(verifier.verifyWithAnyScope(
            RAW_KEY, "tx", ApiKeyVerifier.SCOPE_WORKER_READ, ApiKeyVerifier.SCOPE_WORKER_EXECUTE))
        .isPresent();
  }

  @Test
  @DisplayName("所需作用域集合为空或任一被授权作用域命中时放行,两者都不满足时拒绝")
  void shouldAllowAnyScope_whenAnyGrantedScopeMatches() {
    assertThat(ApiKeyVerifier.scopesAllowAny("worker.read", "worker.read", "worker.execute"))
        .isTrue();
    assertThat(ApiKeyVerifier.scopesAllowAny("worker.execute", "worker.read", "worker.execute"))
        .isTrue();
    assertThat(ApiKeyVerifier.scopesAllowAny("*", "worker.read", "worker.execute"))
        .isTrue();
    assertThat(ApiKeyVerifier.scopesAllowAny("worker.read", "worker.execute")).isFalse();
    assertThat(ApiKeyVerifier.scopesAllowAny("anything")).isTrue();
  }

  @Test
  @DisplayName("授权作用域串按逗号或空格拆分去空白后命中所需作用域时放行,授权串为空或缺失时拒绝,未指定所需作用域时不限制")
  void shouldAllowScope_whenGrantedScopeListContainsRequired() {
    assertThat(ApiKeyVerifier.scopesAllow("*", "anything")).isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("worker.execute,read", "worker.execute"))
        .isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("worker.execute read", "read")).isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("  worker.execute  ", "worker.execute"))
        .isTrue();
    assertThat(ApiKeyVerifier.scopesAllow("read", "worker.execute")).isFalse();
    assertThat(ApiKeyVerifier.scopesAllow("", "anything")).isFalse();
    assertThat(ApiKeyVerifier.scopesAllow(null, "anything")).isFalse();
    assertThat(ApiKeyVerifier.scopesAllow("anything", null)).isTrue();
  }

  // ─── 验证结果缓存 + touch 节流(perf/apikey-verify-cache) ──────────────────

  @Test
  @DisplayName("同一密钥在同一租户内二次校验命中缓存,不再查询候选行")
  void shouldSkipCandidateLookup_whenSameKeyVerifiedTwice() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(rec));

    assertThat(verifier.verify(RAW_KEY, "tx")).contains(rec);
    assertThat(verifier.verify(RAW_KEY, "tx")).contains(rec);

    // 第二次命中缓存,不再查库(即不再走 PBKDF2 比对慢路径)。
    verify(mapper, times(1)).findActiveCandidatesByPrefixAndTenant(PREFIX, "tx");
  }

  @Test
  @DisplayName("比对失败的校验结果不进入缓存,每次校验都重新查询候选行")
  void shouldQueryCandidatesTwice_whenVerificationFails() {
    // 候选行 hash 属于另一 key → 每次都进慢路径,失败不缓存。
    ApiKeyEntity other = pbkdf2Row(1L, "tx", "*", "bk_OTHER-secret");
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(other));

    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();

    verify(mapper, times(2)).findActiveCandidatesByPrefixAndTenant(PREFIX, "tx");
  }

  @Test
  @DisplayName("缓存条目超过存活时长被逐出后,重新查询候选行比对")
  void shouldQueryCandidatesAgain_whenCacheEntryExpires() {
    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(rec));

    assertThat(verifier.verify(RAW_KEY, "tx")).contains(rec);
    advance(Duration.ofMinutes(5).plusSeconds(1)); // 越过 5 分钟 TTL
    assertThat(verifier.verify(RAW_KEY, "tx")).contains(rec);

    // TTL 逐出后重新查库比对。
    verify(mapper, times(2)).findActiveCandidatesByPrefixAndTenant(PREFIX, "tx");
  }

  @Test
  @DisplayName("不同租户使用同一密钥时各自缓存互不串用")
  void shouldKeepSeparateCacheEntries_whenTenantsDiffer() {
    ApiKeyEntity a = pbkdf2Row(1L, "ta", "*", RAW_KEY);
    ApiKeyEntity b = pbkdf2Row(2L, "tb", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "ta")).thenReturn(List.of(a));
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tb")).thenReturn(List.of(b));

    assertThat(verifier.verify(RAW_KEY, "ta")).contains(a);
    assertThat(verifier.verify(RAW_KEY, "tb")).contains(b);

    verify(mapper, times(1)).findActiveCandidatesByPrefixAndTenant(PREFIX, "ta");
    verify(mapper, times(1)).findActiveCandidatesByPrefixAndTenant(PREFIX, "tb");
  }

  @Test
  @DisplayName("缓存命中的候选行已过期时作废该缓存并回退查库,最终拒绝放行")
  void shouldRejectKey_whenCachedEntryExpired() {
    // key 在 fakeClock 的 30s 后自然过期
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf(RAW_KEY);
    ApiKeyEntity shortLived = new ApiKeyEntity(
        9L, "tx", "kn", "*", true, Instant.EPOCH.plusSeconds(30), sh.hash(), sh.salt(), "pbkdf2");
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx"))
        .thenReturn(List.of(shortLived))
        .thenReturn(List.of()); // 过期后 SQL 只取未过期行 → 无候选

    assertThat(verifier.verify(RAW_KEY, "tx")).contains(shortLived); // 入缓存

    advance(Duration.ofSeconds(60)); // < 5min 缓存 TTL,但已越过 expires_at

    // 缓存命中但 expiresAt 已过 → invalidate 落回慢路径,被 SQL 拒掉
    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    verify(mapper, times(2)).findActiveCandidatesByPrefixAndTenant(PREFIX, "tx");
  }

  @Test
  @DisplayName("查无候选行的结果不进入缓存,每次校验都重新查询")
  void shouldQueryCandidatesTwice_whenNoCandidateRowFound() {
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of());

    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();
    assertThat(verifier.verify(RAW_KEY, "tx")).isEmpty();

    // 查无候选行不入缓存:每次都重新查库
    verify(mapper, times(2)).findActiveCandidatesByPrefixAndTenant(PREFIX, "tx");
  }

  @Test
  @DisplayName("连续校验处于节流窗口内时最近使用时间只落库一次")
  void shouldThrottleLastUsedWrite_whenWithinThrottleWindow() {
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx"))
        .thenReturn(List.of(pbkdf2Row(7L, "tx", "*", RAW_KEY)));

    verifier.verify(RAW_KEY, "tx"); // 首次写
    advance(Duration.ofSeconds(30));
    verifier.verify(RAW_KEY, "tx"); // <60s,跳过
    advance(Duration.ofSeconds(20));
    verifier.verify(RAW_KEY, "tx"); // 累计 50s,仍跳过

    verify(mapper, times(1)).touchLastUsedAt(7L);
  }

  @Test
  @DisplayName("越过节流窗口后最近使用时间再次落库")
  void shouldWriteLastUsedAgain_whenThrottleWindowElapsed() {
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx"))
        .thenReturn(List.of(pbkdf2Row(7L, "tx", "*", RAW_KEY)));

    verifier.verify(RAW_KEY, "tx"); // 首次写
    advance(Duration.ofSeconds(61)); // 越过 60s 节流窗口
    verifier.verify(RAW_KEY, "tx"); // 再写一次

    verify(mapper, times(2)).touchLastUsedAt(7L);
  }

  @Test
  @DisplayName("异步写入最近使用时间抛异常时被吞掉,不向调用方传播")
  void shouldSwallowFailure_whenLastUsedWriteThrows() {
    when(mapper.touchLastUsedAt(anyLong())).thenThrow(new RuntimeException("DB down"));

    new ApiKeyAsyncMaintenance(mapper).touch(7L);

    verify(mapper).touchLastUsedAt(7L);
  }

  /**
   * Spring 装配冒烟:本类有两个构造器,多构造器无 {@code @Autowired} 注解时 Spring 回退无参构造器(不存在)→ 启动
   * BeanInstantiationException。此测试用标准注解式 bean 定义({@code register},走
   * AutowiredAnnotationBeanPostProcessor 构造器解析,与生产 component-scan 同路径;{@code registerBean(Class)}
   * 会偏好 public 构造器反而掩盖问题)守护生产构造器上的 {@code @Autowired} 不被误删。
   */
  @Test
  @DisplayName("存在多个构造器时容器仍能完成该组件的装配与实例化")
  void shouldInstantiateBean_whenMultipleConstructorsPresent() {
    try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
      ctx.registerBean(ApiKeyAuthMapper.class, () -> mapper);
      ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
      ctx.registerBean(ApiKeyAsyncMaintenance.class, () -> new ApiKeyAsyncMaintenance(mapper));
      ctx.register(ApiKeyVerifier.class);
      ctx.refresh();
      assertThat(ctx.getBean(ApiKeyVerifier.class)).isNotNull();
    }
  }

  // ─── O4: 验证缓存命中率可观测(CaffeineCacheMetrics) ──────────────────────

  @Test
  @DisplayName("缓存命中与未命中各一次时按缓存名分别暴露对应计数")
  void cacheMetricsBoundToMicrometer_recordHitsAndMisses() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ApiKeyVerifier metered = new ApiKeyVerifier(mapper, fakeTicker, fakeClock, registry);
    metered.bindCacheMetrics();

    ApiKeyEntity rec = pbkdf2Row(1L, "tx", "*", RAW_KEY);
    when(mapper.findActiveCandidatesByPrefixAndTenant(PREFIX, "tx")).thenReturn(List.of(rec));

    metered.verify(RAW_KEY, "tx"); // miss → 入缓存
    metered.verify(RAW_KEY, "tx"); // hit

    // CaffeineCacheMetrics 以 tag cache=apikey.verify 暴露 cache.gets{result=hit/miss}
    double hits = registry
        .get("cache.gets")
        .tags("cache", "apikey.verify", "result", "hit")
        .functionCounter()
        .count();
    double misses = registry
        .get("cache.gets")
        .tags("cache", "apikey.verify", "result", "miss")
        .functionCounter()
        .count();
    assertThat(hits).isEqualTo(1.0d);
    assertThat(misses).isEqualTo(1.0d);
  }
}
