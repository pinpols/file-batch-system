package com.example.batch.console.support;

import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.web.response.ConsoleAuthTokenResponse;
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
import org.springframework.util.StringUtils;

/** 控制台 JWT 签发与校验（HS256，含租户与角色声明）。 */
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
    // 5.3: 生产环境校验 shared-secret 不是默认值
    String sharedSecret = properties.getSharedSecret();
    if ("console-secret".equals(sharedSecret)) {
      throw new IllegalStateException(
          "FATAL: batch.console.security.shared-secret 仍为默认值 'console-secret'，"
              + "生产环境必须通过环境变量注入强密钥或关闭 legacy-header-auth");
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
    if (!StringUtils.hasText(tenantId)) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, CommonErrorMessages.TENANT_REQUIRED);
    }
    Instant issuedAt = Instant.now();
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
      throw new BizException(ResultCode.UNAUTHORIZED, CommonErrorMessages.INVALID_CONSOLE_JWT);
    }
    String tokenType = jwt.getClaimAsString(CLAIM_TOKEN_TYPE);
    if (!TOKEN_TYPE.equals(tokenType)) {
      throw new BizException(ResultCode.UNAUTHORIZED, CommonErrorMessages.INVALID_CONSOLE_JWT);
    }
    String username = jwt.getSubject();
    String tenantId = jwt.getClaimAsString(CLAIM_TENANT_ID);
    Long sessionVersion = jwt.getClaim(CLAIM_SESSION_VERSION);
    if (properties.isSingleSessionEnabled()) {
      if (sessionVersion == null
          || !sessionRegistry.isCurrentSession(username, tenantId, sessionVersion)) {
        throw new BizException(ResultCode.UNAUTHORIZED, CommonErrorMessages.INVALID_CONSOLE_JWT);
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
