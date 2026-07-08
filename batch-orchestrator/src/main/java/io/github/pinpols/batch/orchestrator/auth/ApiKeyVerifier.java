package io.github.pinpols.batch.orchestrator.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.orchestrator.mapper.auth.ApiKeyAuthMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 验证 X-Batch-Api-Key — 按 {@code key_prefix} 索引拿候选活跃行,逐行用 {@link ApiKeyHasher#verify} 常量时间比对。
 *
 * <p>匹配规则:hash + tenantId 必须双匹配(防租户冒充);记录 enabled + 未过期 + 未 revoke。
 *
 * <p>验证通过后异步 touch last_used_at(filter 不等),用于运维侧 "失效/僵尸 key 探测"。
 *
 * <p>scope 校验(ADR-035 §2):scope 字段是 comma/space-separated string,通配 {@code "*"} 命中任何检查; worker 操作类
 * endpoint 应调 {@link #verifyWithScope(String, String, String)} 要求显式 {@code "worker.execute"}
 * scope。老 key {@code scopes='*'}(V47 默认) 通配通过,无需轮转;新 key 必须显式带 {@code "worker.execute"} 才放行。
 *
 * <p>P1-1(2026-06-03,docs/analysis/2026-06-03-deep-scan-be-security.md): V166 起 api_key.key_hash 由裸
 * SHA-256 升级为 PBKDF2-HMAC-SHA256 + per-key salt。老 key {@code key_hash_algo='sha256'} 走原路径,验证
 * **命中**后异步 best-effort 升级为 PBKDF2,实现"登录即升级"。新 key 由 console-api 创建时即写 PBKDF2 + salt。
 *
 * <p><b>性能(perf/apikey-verify-cache):</b>{@link ApiKeyHasher#verify} 对 PBKDF2 行做 600k 迭代 KDF
 * (~100-200ms CPU),而 {@code InternalAuthFilter} 对每个 worker 内调(claim/report/heartbeat/register)都要
 * 验证一次——这是控制面最大单项 CPU 税。这里加两层缓解:
 *
 * <ul>
 *   <li><b>验证结果缓存</b>:进程内 Caffeine,key = {@code SHA-256(rawKey) hex + "|" + tenantId}(**绝不**用
 *       rawKey 明文,防堆转储泄漏),value = 命中的不可变 {@link ApiKeyEntity}。<b>只缓存成功</b>,失败不入缓存(避免误封
 *       窗口,失败本就罕见)。TTL {@value #VERIFY_CACHE_TTL_MINUTES} 分钟,maximumSize {@value
 *       #VERIFY_CACHE_MAX_SIZE}。命中缓存直接返回,跳过候选查库 + PBKDF2 比对慢路径。
 *       <p><b>失效语义</b>:api_key 的 revoke/disable/rotate 写路径全在 {@code ConsoleApiKeyService}
 *       (batch-console-api 模块,<b>独立进程</b>);orchestrator 只只读该表,进程内 invalidate 够不着 console-api 的写。
 *       因此接受 <b>revoke 后最长 {@value #VERIFY_CACHE_TTL_MINUTES} 分钟的生效窗口</b>——worker 内调是可信内网短命
 *       凭据,分钟级窗口可接受;需要即时吊销的场景应缩短 TTL 或引入跨进程失效信号(当前不做)。
 *       <p><b>expires_at 硬边界不受 TTL 窗口影响</b>:命中路径对缓存值做内存复查——{@code expiresAt} 已过则 invalidate
 *       并落回慢路径(SQL 只取未过期行,自然拒掉),短命 key 的自然过期<b>不会</b>被缓存延长。
 *       <p>legacy sha256 → PBKDF2 的"登录即升级":缓存 key 是 rawKey 的 SHA-256,升级不改 rawKey 故 key 不变;缓存命中
 *       路径<b>不再</b>读取 entity 的 {@code keyHash/salt/keyHashAlgo}(比对已在写入缓存前完成),故 entity 里这些 字段即使因升级而
 *       DB 侧已变,缓存值也不会被误用;下游只用 {@code id/tenantId/scopes/enabled} 等不随升级变化的字段。
 *   <li><b>last_used_at 写节流</b>:见 {@link #maybeTouch}。
 * </ul>
 *
 * <p><b>scope 过滤</b>({@link #verifyWithScope}/{@link #verifyWithAnyScope})在缓存<b>之后</b>做——它们是对
 * {@link #verify} 结果的纯函数过滤,天然与缓存兼容。
 */
@Slf4j
@Component
public class ApiKeyVerifier {

  /** 验证结果缓存 TTL(分钟);同时是 revoke 生效的最长窗口——见类 javadoc 失效语义。 */
  static final int VERIFY_CACHE_TTL_MINUTES = 5;

  static final int VERIFY_CACHE_MAX_SIZE = 10_000;

  /** last_used_at 两次真实写库的最小间隔;窗口内的命中跳过写库。 */
  static final Duration TOUCH_MIN_INTERVAL = Duration.ofSeconds(60);

  private static final long TOUCH_MIN_INTERVAL_NANOS = TOUCH_MIN_INTERVAL.toNanos();

  public static final String SCOPE_WORKER_EXECUTE = "worker.execute";

  /**
   * 只读 scope:允许查询类(GET)端点,但不允许 claim/report/register 等会改状态的写操作。 {@code worker.execute}
   * 是其超集(可写者必可读),故读端点接受二者之一。用于给监控 / 第三方集成发"只读 key"。
   */
  public static final String SCOPE_WORKER_READ = "worker.read";

  public static final String SCOPE_WILDCARD = "*";

  /** 明文 key 前 8 位用作索引前缀(与 console-api ConsoleApiKeyService.create 同步)。 */
  public static final int KEY_PREFIX_LEN = 8;

  private final ApiKeyAuthMapper mapper;

  private final Ticker ticker;

  /** 墙钟(epoch)来源——命中路径复查 {@code expires_at} 用;测试注入假钟,与 {@link #ticker} 同源推进。 */
  private final InstantSource instantSource;

  /** 验证成功结果缓存:key = SHA-256(rawKey)|tenantId,value = 命中的不可变 entity。只缓存成功。 */
  private final Cache<String, ApiKeyEntity> verifyCache;

  /** keyId → 上次真实写 last_used_at 的 ticker 纳秒时刻;用于 60s 写节流。key 数量 ~= 活跃 key 数,极小。 */
  private final ConcurrentHashMap<Long, Long> lastTouchNanos = new ConcurrentHashMap<>();

  /**
   * 自注入(CLAUDE.md §Java #3 豁免①):{@code touchAsync} / {@code upgradeLegacyHashAsync} 标了
   * {@code @Async}, 必须经 Spring 代理调用才异步。原先 {@code this.touchAsync()} 是同类自调用,绕过代理 → @Async 失效 → DB 写
   * + PBKDF2(50-200ms CPU)在请求线程同步执行,峰值流量会耗尽 Tomcat 线程池。改走 {@code self.xxx()}。
   */
  @Lazy @Autowired private ApiKeyVerifier self;

  /**
   * 生产构造器(Spring):系统时钟。
   *
   * <p>{@code @Autowired} 必须显式标——本类有两个构造器(测试用三参 package-private),Spring 多构造器无注解时回退无参构造器 (不存在)→ 启动
   * BeanInstantiationException。回归守护见 {@code
   * ApiKeyVerifierTest.springCanInstantiateBeanDespiteMultipleConstructors}。
   */
  @Autowired
  public ApiKeyVerifier(ApiKeyAuthMapper mapper) {
    this(mapper, Ticker.systemTicker(), InstantSource.system());
  }

  /**
   * 可注入 {@link Ticker} + {@link InstantSource} 的构造器(测试用假时钟驱动 TTL 逐出 / touch 节流 / expires_at 复查)。
   */
  ApiKeyVerifier(ApiKeyAuthMapper mapper, Ticker ticker, InstantSource instantSource) {
    this.mapper = mapper;
    this.ticker = ticker;
    this.instantSource = instantSource;
    this.verifyCache =
        Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfterWrite(Duration.ofMinutes(VERIFY_CACHE_TTL_MINUTES))
            .maximumSize(VERIFY_CACHE_MAX_SIZE)
            .build();
  }

  /**
   * @param rawKey 客户端传来的 X-Batch-Api-Key 原文
   * @param claimedTenantId 客户端传来的 X-Batch-Tenant-Id;必须与 key 在表里的 tenant_id 一致
   * @return 命中且活跃的 {@link ApiKeyEntity};否则空(filter 直接返 401,不暴露具体原因防侧信道)
   */
  public Optional<ApiKeyEntity> verify(String rawKey, String claimedTenantId) {
    if (rawKey == null || rawKey.isBlank()) return Optional.empty();
    if (claimedTenantId == null || claimedTenantId.isBlank()) return Optional.empty();
    if (rawKey.length() < KEY_PREFIX_LEN) return Optional.empty();

    // 缓存命中:跳过候选查库 + PBKDF2 慢路径,直接返回。仍走 touch 节流(命中也代表 key 活跃)。
    String cacheKey = verifyCacheKey(rawKey, claimedTenantId);
    ApiKeyEntity cached = verifyCache.getIfPresent(cacheKey);
    if (cached != null) {
      // expires_at 硬边界内存复查:自然过期不允许被缓存 TTL 延长(revoke 的 5 分钟窗口是另一回事,见类 javadoc)。
      Instant expiresAt = cached.expiresAt();
      if (expiresAt != null && expiresAt.isBefore(instantSource.instant())) {
        verifyCache.invalidate(cacheKey);
        // 落回慢路径:SQL 只取未过期行,自然拒掉。
      } else {
        maybeTouch(cached.id());
        return Optional.of(cached);
      }
    }

    String prefix = rawKey.substring(0, KEY_PREFIX_LEN);
    List<ApiKeyEntity> candidates =
        mapper.findActiveCandidatesByPrefixAndTenant(prefix, claimedTenantId);
    if (candidates == null || candidates.isEmpty()) return Optional.empty();

    // 候选数极小(同一租户同一前缀的活跃 key,索引剪枝后通常 0-1 行,极端冲突 <5)。
    // 逐行常量时间比对,命中即停;legacy sha256 命中后异步升级 KDF。
    for (ApiKeyEntity rec : candidates) {
      String algo = rec.keyHashAlgo() == null ? ApiKeyHasher.ALGO_SHA256_LEGACY : rec.keyHashAlgo();
      if (ApiKeyHasher.verify(rawKey, rec.keyHash(), rec.salt(), algo)) {
        // 只缓存成功;失败不入缓存(避免误封窗口)。
        verifyCache.put(cacheKey, rec);
        maybeTouch(rec.id());
        if (ApiKeyHasher.ALGO_SHA256_LEGACY.equals(algo)) {
          self.upgradeLegacyHashAsync(rec.id(), rec.keyHash(), rawKey);
        }
        return Optional.of(rec);
      }
    }
    return Optional.empty();
  }

  /**
   * 缓存 key:{@code SHA-256(rawKey) hex + "|" + tenantId}。用 SHA-256 而非 rawKey 明文,防堆转储 / 缓存 dump 泄漏原始
   * 凭据(SHA-256 一次成本相对 PBKDF2 600k 迭代可忽略)。tenantId 拼入是因为验证语义按 (rawKey, tenantId) 双匹配。
   */
  private static String verifyCacheKey(String rawKey, String claimedTenantId) {
    return ApiKeyHasher.legacySha256Hex(rawKey) + "|" + claimedTenantId;
  }

  /**
   * last_used_at 写节流:距上次真实写 <{@link #TOUCH_MIN_INTERVAL}(60s)直接跳过,≥ 才异步写。用 {@code compute} 做 CAS
   * 防并发下同一 key 重复写。
   *
   * <p>last_used_at 的运维语义是<b>僵尸 key 探测</b>(哪些 key 长期未用可回收),分钟级精度足够;同一 key 数百 QPS 打同一行的
   * 写放大对该语义无收益,故节流。map 逐出不做——key 数量 ~= 活跃 key 数,极小。
   */
  private void maybeTouch(Long id) {
    long now = ticker.read();
    boolean[] doWrite = {false};
    lastTouchNanos.compute(
        id,
        (k, prev) -> {
          if (prev != null && now - prev < TOUCH_MIN_INTERVAL_NANOS) {
            return prev; // 窗口内,跳过写库
          }
          doWrite[0] = true;
          return now;
        });
    if (doWrite[0]) {
      self.touchAsync(id);
    }
  }

  /**
   * 同 {@link #verify} 但强制 key.scope 含 {@code requiredScope}(或 {@code "*"} 通配)。
   *
   * @return 命中且 scope 通过的 {@link ApiKeyEntity};否则空(scope 不通过也返空,filter 一律 401,不区分原因)
   */
  public Optional<ApiKeyEntity> verifyWithScope(
      String rawKey, String claimedTenantId, String requiredScope) {
    return verify(rawKey, claimedTenantId).filter(rec -> scopesAllow(rec.scopes(), requiredScope));
  }

  /**
   * 同 {@link #verifyWithScope} 但 key 命中 {@code acceptedScopes} 中**任一**(或 {@code "*"} 通配)即放行。 读端点用
   * {@code (SCOPE_WORKER_READ, SCOPE_WORKER_EXECUTE)} 表达"读 key 或写 key 都可读"。
   *
   * @return 命中且 scope 通过的 {@link ApiKeyEntity};否则空
   */
  public Optional<ApiKeyEntity> verifyWithAnyScope(
      String rawKey, String claimedTenantId, String... acceptedScopes) {
    return verify(rawKey, claimedTenantId)
        .filter(rec -> scopesAllowAny(rec.scopes(), acceptedScopes));
  }

  /** scopes 命中 {@code acceptedScopes} 任一即真;空 accepted 视为不限制(真)。 */
  static boolean scopesAllowAny(String scopesField, String... acceptedScopes) {
    if (acceptedScopes == null || acceptedScopes.length == 0) return true;
    for (String accepted : acceptedScopes) {
      if (scopesAllow(scopesField, accepted)) return true;
    }
    return false;
  }

  /** scopes 字符串解析:逗号/空格 split,trim,去空;{@code "*"} 通配。 */
  static boolean scopesAllow(String scopesField, String requiredScope) {
    if (requiredScope == null || requiredScope.isBlank()) return true;
    if (scopesField == null || scopesField.isBlank()) return false;
    Set<String> scopes =
        Arrays.stream(scopesField.split("[,\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    return scopes.contains(SCOPE_WILDCARD) || scopes.contains(requiredScope);
  }

  @Async
  public void touchAsync(Long id) {
    try {
      mapper.touchLastUsedAt(id);
    } catch (Exception ex) {
      log.debug("touch last_used_at failed for keyId={}: {}", id, ex.getMessage());
    }
  }

  /**
   * P1-1:legacy sha256 行命中后,best-effort 升级为 PBKDF2 + salt。
   *
   * <p>WHERE 守护 {@code algo='sha256' AND key_hash=oldHash} 防并发改写覆盖 — 同时被 console-api revoke 或被另一
   * worker 并发升级的场景下,落败方无副作用退出。
   */
  @Async
  public void upgradeLegacyHashAsync(Long id, String oldHash, String rawKey) {
    try {
      ApiKeyHasher.SaltedHash upgraded = ApiKeyHasher.hashWithSaltKdf(rawKey);
      int rows = mapper.upgradeHashIfLegacy(id, oldHash, upgraded.hash(), upgraded.salt());
      if (rows > 0) {
        log.info("api_key keyId={} upgraded sha256 → pbkdf2", id);
      }
    } catch (Exception ex) {
      log.debug("api_key keyId={} kdf upgrade swallowed: {}", id, ex.getMessage());
    }
  }
}
