package com.example.batch.console.domain.rbac.support;

import com.example.batch.common.config.BatchProfileSupport;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Hashes;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.domain.rbac.web.response.ConsoleAuthTokenResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 控制台 JWT 签发与校验（HS256，含租户与角色声明）。
 *
 * <p>关键安全约束：
 *
 * <ul>
 *   <li><b>启动期强校验</b>（{@link #validateSecuritySecrets}）：prod profile 下拒绝启动如果 {@code jwt-secret} 仍含
 *       "change-me" 占位或 {@code shared-secret} 仍为默认值 {@code console-secret} （见 {@code
 *       5.3}）——防止占位符被带上生产。
 *   <li><b>单点登录</b>（可选，{@code singleSessionEnabled}）：token 里写入 {@code session_version}， 登录时 {@link
 *       ConsoleSessionRegistry} 递增该用户的 session 版本；旧 token 的 {@code session_version} 失效后 {@code
 *       authenticate} 拒绝，实现"新登录踢旧会话"。
 *   <li><b>token 类型门</b>：{@code token_type="console_access"} 声明，仅接受该类型——防止其他 JWT 服务签发的
 *       token（如内部服务间通信）被误当作控制台凭证。
 *   <li><b>Key 派生</b>：{@code HmacSHA256(SHA-256(jwt-secret))}，先 SHA-256 把任意长度 secret 规范化成 32 字节， 避免
 *       secret 长度不足导致 HS256 初始化失败。
 * </ul>
 */
@Slf4j
@Service
public class ConsoleJwtService {

  private static final String TOKEN_TYPE = "console_access";
  private static final String CLAIM_TENANT_ID = "tenantId";
  private static final String CLAIM_AUTHORITIES = "authorities";
  private static final String CLAIM_TOKEN_TYPE = "tokenType";
  private static final String CLAIM_SESSION_VERSION = "sessionVersion";
  private static final String CLAIM_JTI = "jti";
  private static final String CLAIM_IP_HASH = "ipHash";
  private static final String CLAIM_UA_HASH = "uaHash";

  private final ConsoleSecurityProperties properties;
  private final ConsoleSessionRegistry sessionRegistry;
  private final Environment environment;

  /**
   * P1(2026-05-23 audit / CLAUDE.md §编码细则 #3):Redis 可选依赖改 {@link ObjectProvider} 构造器注入, 替代原
   * {@code @Autowired(required = false)} field 注入。authenticate() / revoke() 内每次调用 {@link
   * ObjectProvider#getIfAvailable()},非 Spring 单测场景或 redis 启动失败时返回 null, 与之前 {@code redisTemplate ==
   * null} 降级路径完全一致。
   */
  private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

  @Autowired
  public ConsoleJwtService(
      ConsoleSecurityProperties properties,
      ConsoleSessionRegistry sessionRegistry,
      Environment environment,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
    this.properties = properties;
    this.sessionRegistry = sessionRegistry;
    this.environment = environment;
    this.redisTemplateProvider = redisTemplateProvider;
  }

  /** 非 Spring 单测构造器:无 Redis,等价 ObjectProvider 永远返回 null。 */
  public ConsoleJwtService(
      ConsoleSecurityProperties properties,
      ConsoleSessionRegistry sessionRegistry,
      Environment environment) {
    this(properties, sessionRegistry, environment, EmptyRedisProvider.INSTANCE);
  }

  /** 非 Spring 测试场景下提供空 {@link ObjectProvider},{@code getIfAvailable()} 始终返回 null。 */
  private static final class EmptyRedisProvider implements ObjectProvider<StringRedisTemplate> {
    static final EmptyRedisProvider INSTANCE = new EmptyRedisProvider();

    @Override
    public StringRedisTemplate getObject() {
      throw new IllegalStateException("no StringRedisTemplate bean (test stub)");
    }

    @Override
    public StringRedisTemplate getObject(Object... args) {
      throw new IllegalStateException("no StringRedisTemplate bean (test stub)");
    }

    @Override
    public StringRedisTemplate getIfAvailable() {
      return null;
    }

    @Override
    public StringRedisTemplate getIfUnique() {
      return null;
    }
  }

  private static final String REVOKED_KEY_PREFIX = "console:revoked:jti:";

  // JWT IP/UA binding drift 日志抑制器:同一 (用户+租户+storedHash→currentHash) 组合
  // 5 分钟内只记一次 WARN,避免 e2e 同 token 多 tab 反复刷屏(实测一轮 e2e 8000+ 行噪音)。
  // 10k 上限够覆盖任何合理量级会话规模。
  private final Cache<String, Boolean> driftLogSuppressor =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10_000).build();

  // P2-8：encoder / decoder 在 PostConstruct 一次性构建，避免每次请求重新派生 HMAC key（SHA-256 + SecretKeySpec）。
  // jwt-secret 是 @ConfigurationProperties 字段，运行期不变。
  // volatile：fallback 路径 encoder()/decoder() 是 DCL 模式,无 volatile JIT 重排序可能让其他线程读到
  // 未完全构造的对象,虽然 SecretKey 重复构造无副作用但仍是 JMM 不安全模式。
  private volatile NimbusJwtEncoder cachedEncoder;
  private volatile JwtDecoder cachedDecoder;

  @PostConstruct
  void validateSecuritySecrets() {
    if (isProductionProfile()) {
      String jwtSecret = properties.getJwtSecret();
      if (!Texts.hasText(jwtSecret)) {
        throw new IllegalStateException(
            "FATAL: batch.console.security.jwt-secret 为空，生产环境必须通过环境变量或密钥管理注入真实密钥");
      }
      String lower = jwtSecret.toLowerCase(Locale.ROOT);
      if (lower.contains("change-me") || lower.contains("change_me")) {
        throw new IllegalStateException(
            "FATAL: batch.console.security.jwt-secret 仍包含默认占位符，" + "生产环境必须通过环境变量或密钥管理注入真实密钥");
      }
      // P1-8：jwt-secret 长度强度兜底（HS256 最小 256 bit / 32 ASCII 字符）
      if (jwtSecret.length() < 32) {
        throw new IllegalStateException(
            "FATAL: batch.console.security.jwt-secret 长度不足 32 字符，HS256 弱密钥风险");
      }
    } else {
      // 非 prod:不 fail-fast(本地/联调要能起),但默认/弱 jwt-secret 仍在用就显式 WARN——
      // 兜 prod fail-fast 的第二层,防"漏开 prod profile 就用默认密钥签发 token"(审计 #4)。
      String jwtSecret = properties.getJwtSecret();
      String lower = jwtSecret == null ? "" : jwtSecret.toLowerCase(Locale.ROOT);
      if (!Texts.hasText(jwtSecret)
          || lower.contains("change-me")
          || lower.contains("change_me")
          || jwtSecret.length() < 32) {
        log.warn(
            "⚠️ 非生产 profile:batch.console.security.jwt-secret 仍为默认/弱密钥,生产前务必经 env / "
                + "secret-manager 注入 ≥32 字符强密钥(prod-like profile 下会 fail-fast 拒绝启动)");
      }
    }
    SecretKey key = signingKey();
    this.cachedEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    this.cachedDecoder = buildDecoder(key);
  }

  /**
   * R4-P2-6：提取 decoder 构建逻辑，让 @PostConstruct 路径和 decoder() lazy-init fallback 共用同一套构造， 包括 clock
   * skew validator。之前 lazy fallback 不带 skew validator，test/非-Spring 场景行为漂移。
   */
  private JwtDecoder buildDecoder(SecretKey key) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    Duration skew = properties.getJwtClockSkew();
    if (skew == null || skew.isNegative()) {
      skew = Duration.ofMinutes(1);
    }
    decoder.setJwtValidator(new JwtTimestampValidator(skew));
    return decoder;
  }

  private boolean isProductionProfile() {
    return BatchProfileSupport.isProductionProfile(environment);
  }

  /** 签发访问令牌及过期时间。 */
  public ConsoleAuthTokenResponse issueToken(
      String username, String tenantId, Set<String> authorities) {
    return issueToken(
        username, tenantId, authorities, sessionRegistry.currentSessionVersion(username, tenantId));
  }

  /** 签发访问令牌及过期时间。 */
  public ConsoleAuthTokenResponse issueToken(
      String username, String tenantId, Set<String> authorities, long sessionVersion) {
    Guard.requireText(username, "username is required");
    if (!Texts.hasText(tenantId)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.tenant.required");
    }
    Instant issuedAt = BatchDateTimeSupport.utcNow();
    Instant expiresAt = issuedAt.plus(properties.getJwtTtl());
    String jti = UUID.randomUUID().toString();
    HttpServletRequest currentRequest = currentRequest();
    JwtClaimsSet.Builder claimsBuilder =
        JwtClaimsSet.builder()
            .issuer(properties.getJwtIssuer())
            .subject(username)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(jti)
            .claim(CLAIM_TENANT_ID, tenantId)
            .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE)
            .claim(CLAIM_SESSION_VERSION, sessionVersion)
            .claim(CLAIM_JTI, jti)
            .claim(CLAIM_AUTHORITIES, authorities == null ? List.of() : List.copyOf(authorities));
    if (currentRequest != null) {
      String ipHash = hashClientIp(currentRequest);
      String uaHash = hashUserAgent(currentRequest);
      if (ipHash != null) {
        claimsBuilder.claim(CLAIM_IP_HASH, ipHash);
      }
      if (uaHash != null) {
        claimsBuilder.claim(CLAIM_UA_HASH, uaHash);
      }
    }
    JwtClaimsSet claims = claimsBuilder.build();
    String token =
        encoder()
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
            .getTokenValue();
    return new ConsoleAuthTokenResponse(
        token,
        "Bearer",
        issuedAt,
        expiresAt,
        username,
        tenantId,
        authorities == null ? Set.of() : new LinkedHashSet<>(authorities));
  }

  public ConsolePrincipal authenticate(String token) {
    Jwt jwt = decoder().decode(token);
    String issuer = jwt.getClaimAsString("iss");
    if (!properties.getJwtIssuer().equals(issuer)) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.console_jwt.invalid");
    }
    String tokenType = jwt.getClaimAsString(CLAIM_TOKEN_TYPE);
    if (!TOKEN_TYPE.equals(tokenType)) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.console_jwt.invalid");
    }
    String username = jwt.getSubject();
    String tenantId = jwt.getClaimAsString(CLAIM_TENANT_ID);
    Long sessionVersion = jwt.getClaim(CLAIM_SESSION_VERSION);
    if (properties.isSingleSessionEnabled()) {
      if (sessionVersion == null
          || !sessionRegistry.isCurrentSession(username, tenantId, sessionVersion)) {
        throw BizException.of(ResultCode.UNAUTHORIZED, "error.console_jwt.invalid");
      }
    }
    // P0-3:logout 后写入的 jti 黑名单,命中即拒绝(token TTL 内即时失效)。
    String jti = jwt.getClaimAsString(CLAIM_JTI);
    StringRedisTemplate authRedis = redisTemplateProvider.getIfAvailable();
    if (jti != null && authRedis != null) {
      Boolean revoked = authRedis.hasKey(REVOKED_KEY_PREFIX + jti);
      if (Boolean.TRUE.equals(revoked)) {
        throw BizException.of(ResultCode.UNAUTHORIZED, "error.console_jwt.invalid");
      }
    }
    // P2-2:IP/UA 软绑定。签发时绑定 hash,异地异机访问只打 WARN(不 deny)— 移动网 IP 抖动 / UA
    // 升级会误伤,真要 deny 需配合风控规则。空 claim = 旧 token 兼容,跳过比对。
    auditClientBindingDrift(jwt, username, tenantId);
    List<String> authorities = jwt.getClaimAsStringList(CLAIM_AUTHORITIES);
    return new ConsolePrincipal(
        username, tenantId, authorities == null ? Set.of() : new LinkedHashSet<>(authorities));
  }

  /**
   * P0-3:把当前 token 加入 revocation 黑名单,TTL = token 剩余生命。 调用方:登出 endpoint 拿到当前 cookie 中的 token 后传入。
   */
  public void revoke(String token) {
    StringRedisTemplate revokeRedis = redisTemplateProvider.getIfAvailable();
    if (revokeRedis == null) {
      return;
    }
    Jwt jwt;
    try {
      jwt = decoder().decode(token);
    } catch (RuntimeException ignored) {
      return; // 解析失败的 token 没必要再 revoke
    }
    String jti = jwt.getClaimAsString(CLAIM_JTI);
    if (jti == null) {
      return; // 旧版 token 无 jti,跳过
    }
    Instant exp = jwt.getExpiresAt();
    if (exp == null) {
      return;
    }
    long ttlSeconds = exp.getEpochSecond() - BatchDateTimeSupport.utcNow().getEpochSecond();
    if (ttlSeconds <= 0) {
      return; // 已过期,无需占用 Redis
    }
    revokeRedis.opsForValue().set(REVOKED_KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
  }

  private void auditClientBindingDrift(Jwt jwt, String username, String tenantId) {
    HttpServletRequest req = currentRequest();
    if (req == null) {
      return;
    }
    String storedIp = jwt.getClaimAsString(CLAIM_IP_HASH);
    String storedUa = jwt.getClaimAsString(CLAIM_UA_HASH);
    if (storedIp == null && storedUa == null) {
      return;
    }
    String currentIp = hashClientIp(req);
    String currentUa = hashUserAgent(req);
    if (storedIp != null && currentIp != null && !storedIp.equals(currentIp)) {
      // 同 (username,tenant,storedIp→currentIp) 组合 5 分钟内只记一次,避免 e2e/同浏览器多 tab 刷屏
      String key = "ip|" + username + "|" + tenantId + "|" + storedIp + "→" + currentIp;
      if (driftLogSuppressor.getIfPresent(key) == null) {
        driftLogSuppressor.put(key, Boolean.TRUE);
        log.warn(
            "JWT IP binding drift: username={} tenantId={} (token issued from different network)",
            username,
            tenantId);
      }
    }
    if (storedUa != null && currentUa != null && !storedUa.equals(currentUa)) {
      String key = "ua|" + username + "|" + tenantId + "|" + storedUa + "→" + currentUa;
      if (driftLogSuppressor.getIfPresent(key) == null) {
        driftLogSuppressor.put(key, Boolean.TRUE);
        log.warn(
            "JWT UA binding drift: username={} tenantId={} (token issued from different browser)",
            username,
            tenantId);
      }
    }
  }

  private HttpServletRequest currentRequest() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      return attrs.getRequest();
    }
    return null;
  }

  private String hashClientIp(HttpServletRequest req) {
    // 软绑定不信任 XFF(即便 trustForwardedHeaders=true 也只取 RemoteAddr 防伪造):
    // 这里是审计用途,假 XFF 反而让攻击者主动选定 token 绑定的 hash。
    return Hashes.sha256Short(req.getRemoteAddr());
  }

  private String hashUserAgent(HttpServletRequest req) {
    return Hashes.sha256Short(req.getHeader("User-Agent"));
  }

  private NimbusJwtEncoder encoder() {
    NimbusJwtEncoder e = cachedEncoder;
    if (e == null) {
      // 单元测试 / 非 Spring 场景 PostConstruct 未触发时的兜底初始化（同 SecretKey 多次构造无副作用）
      synchronized (this) {
        e = cachedEncoder;
        if (e == null) {
          e = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey()));
          cachedEncoder = e;
        }
      }
    }
    return e;
  }

  private JwtDecoder decoder() {
    JwtDecoder d = cachedDecoder;
    if (d == null) {
      synchronized (this) {
        d = cachedDecoder;
        if (d == null) {
          // R4-P2-6：fallback 路径走 buildDecoder，与 @PostConstruct 一致带 clock skew validator
          d = buildDecoder(signingKey());
          cachedDecoder = d;
        }
      }
    }
    return d;
  }

  private SecretKey signingKey() {
    try {
      byte[] keyBytes =
          MessageDigest.getInstance("SHA-256")
              .digest(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
      return new SecretKeySpec(keyBytes, "HmacSHA256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
