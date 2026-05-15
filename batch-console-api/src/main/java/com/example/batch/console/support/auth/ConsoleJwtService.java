package com.example.batch.console.support.auth;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

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
@Service
@RequiredArgsConstructor
public class ConsoleJwtService {

  private static final String TOKEN_TYPE = "console_access";
  private static final String CLAIM_TENANT_ID = "tenantId";
  private static final String CLAIM_AUTHORITIES = "authorities";
  private static final String CLAIM_TOKEN_TYPE = "tokenType";
  private static final String CLAIM_SESSION_VERSION = "sessionVersion";

  // 与 BatchSecurityProperties.PROD_LIKE_PROFILES 对齐：staging / uat / preprod 也强制校验密钥占位符
  private static final Set<String> PROD_LIKE_PROFILES =
      Set.of("prod", "production", "staging", "uat", "preprod", "pre-prod", "pre-production");

  private final ConsoleSecurityProperties properties;
  private final ConsoleSessionRegistry sessionRegistry;
  private final Environment environment;

  // P2-8：encoder / decoder 在 PostConstruct 一次性构建，避免每次请求重新派生 HMAC key（SHA-256 + SecretKeySpec）。
  // jwt-secret 是 @ConfigurationProperties 字段，运行期不变。
  private NimbusJwtEncoder cachedEncoder;
  private JwtDecoder cachedDecoder;

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
    java.time.Duration skew = properties.getJwtClockSkew();
    if (skew == null || skew.isNegative()) {
      skew = Duration.ofMinutes(1);
    }
    decoder.setJwtValidator(
        new org.springframework.security.oauth2.jwt.JwtTimestampValidator(skew));
    return decoder;
  }

  private boolean isProductionProfile() {
    for (String profile : environment.getActiveProfiles()) {
      if (profile != null && PROD_LIKE_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
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
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.getJwtIssuer())
            .subject(username)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(CLAIM_TENANT_ID, tenantId)
            .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE)
            .claim(CLAIM_SESSION_VERSION, sessionVersion)
            .claim(CLAIM_AUTHORITIES, authorities == null ? List.of() : List.copyOf(authorities))
            .build();
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
    List<String> authorities = jwt.getClaimAsStringList(CLAIM_AUTHORITIES);
    return new ConsolePrincipal(
        username, tenantId, authorities == null ? Set.of() : new LinkedHashSet<>(authorities));
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
