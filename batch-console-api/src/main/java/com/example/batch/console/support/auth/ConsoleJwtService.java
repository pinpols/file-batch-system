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

  private final ConsoleSecurityProperties properties;
  private final ConsoleSessionRegistry sessionRegistry;
  private final Environment environment;

  @PostConstruct
  void validateSecuritySecrets() {
    if (!isProductionProfile()) {
      return;
    }
    String jwtSecret = properties.getJwtSecret();
    if (jwtSecret != null) {
      String lower = jwtSecret.toLowerCase(Locale.ROOT);
      if (lower.contains("change-me") || lower.contains("change_me")) {
        throw new IllegalStateException(
            "FATAL: batch.console.security.jwt-secret 仍包含默认占位符，" + "生产环境必须通过环境变量或密钥管理注入真实密钥");
      }
    }
  }

  private boolean isProductionProfile() {
    for (String profile : environment.getActiveProfiles()) {
      if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
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
    return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey()));
  }

  private JwtDecoder decoder() {
    return NimbusJwtDecoder.withSecretKey(signingKey()).macAlgorithm(MacAlgorithm.HS256).build();
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
