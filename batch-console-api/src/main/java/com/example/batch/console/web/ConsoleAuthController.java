package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.domain.audit.support.AuditAction;
import com.example.batch.console.service.ConsoleAuthApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.domain.observability.service.SseTicketService;
import com.example.batch.console.support.auth.ConsoleJwtService;
import com.example.batch.console.support.auth.ConsoleLoginKeyPairService;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.example.batch.console.web.request.auth.ConsoleLoginRequest;
import com.example.batch.console.web.response.auth.ConsoleAuthProfileResponse;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控制台认证 REST：签发 JWT、查询当前登录主体信息。 */
@RestController
@Validated
@RequestMapping("/api/console/auth")
@RequiredArgsConstructor
public class ConsoleAuthController {

  private final ConsoleAuthApplicationService authApplicationService;
  private final ConsoleResponseFactory responseFactory;
  private final SseTicketService sseTicketService;
  private final ConsoleSecurityProperties securityProperties;
  private final ConsoleJwtService jwtService;
  private final ConsoleLoginKeyPairService loginKeyPairService;

  private static final String CONSOLE_TOKEN_COOKIE = "batch_console_token";

  /**
   * 使用平台库中的控制台账号进行登录并签发 JWT。
   *
   * <p>ADR-030 §D7：token 走 HttpOnly cookie {@code batch_console_token}。P1-1 (pre-launch audit
   * 2026-05-18) 收尾:响应 body 不再带明文 accessToken,防止 JS / XSS 读取抵消 cookie 防护。
   *
   * <p>2026-05-18 加密路径:FE 走 axios interceptor 用 RSA-OAEP-SHA256 + AES-256-GCM 加密 body, 此处先 decrypt
   * 拿到 {@code {username,password}} 再走原 service。配置守护见 {@code
   * ConsoleSecurityProperties.LoginEncryption} + prod 强制 required=true。
   */
  @PostMapping("/login")
  @AuditAction(
      action = "auth.login",
      aggregateType = "auth",
      aggregateId = "#request.username",
      recordParams = false)
  public CommonResponse<ConsoleAuthTokenResponse> login(
      @RequestBody ConsoleLoginRequest request, HttpServletResponse response) {
    ConsoleLoginRequest resolved = resolveLoginRequest(request);
    ConsoleAuthTokenResponse body = authApplicationService.login(resolved);
    response.addHeader(HttpHeaders.SET_COOKIE, buildTokenCookie(body));
    return responseFactory.success(body.withoutToken());
  }

  private ConsoleLoginRequest resolveLoginRequest(ConsoleLoginRequest request) {
    if (request == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.auth.invalid_credentials");
    }
    boolean required = securityProperties.getLoginEncryption().isRequired();
    if (request.isEncrypted()) {
      String plaintext =
          loginKeyPairService.decrypt(
              request.getEncryptedKey(), request.getIv(), request.getCiphertext());
      ConsoleLoginRequest decoded = JsonUtils.fromJson(plaintext, ConsoleLoginRequest.class);
      if (decoded == null) {
        throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.encryption_failed");
      }
      return decoded;
    }
    if (required) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.encryption_required");
    }
    return request;
  }

  /**
   * 返回 RSA 公钥（PEM）供 FE 加密 login body。配 {@code
   * batch.console.security.login-encryption.enabled=false} 时该端点返回 404,FE 走明文路径。
   */
  @GetMapping("/public-key")
  public CommonResponse<Map<String, String>> publicKey() {
    if (!securityProperties.getLoginEncryption().isEnabled()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.auth.encryption_unavailable");
    }
    return responseFactory.success(
        Map.of(
            "algorithm", "RSA-OAEP-256",
            "publicKey", loginKeyPairService.publicKeyPem(),
            "fingerprint", loginKeyPairService.fingerprint()));
  }

  /** 为当前已认证用户签发 JWT。 */
  @PostMapping("/token")
  @PreAuthorize("isAuthenticated()")
  public CommonResponse<ConsoleAuthTokenResponse> token(
      Authentication authentication, HttpServletResponse response) {
    ConsoleAuthTokenResponse body = authApplicationService.issueToken(authentication);
    response.addHeader(HttpHeaders.SET_COOKIE, buildTokenCookie(body));
    return responseFactory.success(body.withoutToken());
  }

  /**
   * 登出：把 cookie 设置成 Max-Age=0 立即失效（前端调用此端点替代手动清 localStorage）。
   *
   * <p>P0-3 (2026-05-18)：把当前 token 的 jti 加入 Redis revocation list,TTL = token 剩余生命; 防止"复制 cookie
   * 到别处继续用"——logout 后服务端立即拒绝该 token。
   */
  @PostMapping("/logout")
  @AuditAction(action = "auth.logout", aggregateType = "auth")
  public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    String currentToken = extractTokenFromCookie(request);
    if (currentToken != null) {
      jwtService.revoke(currentToken);
    }
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(CONSOLE_TOKEN_COOKIE, "")
            .httpOnly(true)
            // R7-A1-P2：与 buildTokenCookie 一致，由 cookie-secure 配置开关。
            .secure(securityProperties.isCookieSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
    return ResponseEntity.noContent().build();
  }

  private String extractTokenFromCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (CONSOLE_TOKEN_COOKIE.equals(cookie.getName())) {
        String value = cookie.getValue();
        return value == null || value.isBlank() ? null : value.trim();
      }
    }
    return null;
  }

  /**
   * 构造 HttpOnly token cookie。
   *
   * <ul>
   *   <li>HttpOnly：JS 不可读 → XSS 也无法外泄
   *   <li>SameSite=Lax：跨站 POST/iframe 不带 cookie；同站 GET / 普通跳转保留
   *   <li>Secure：默认 true（生产 HTTPS 强制），由 {@code batch.console.security.cookie-secure} 开关，本地 /
   *       docker-compose 调试可在 application-local.yml 覆盖为 false。R7-A1-P2 改造前 硬编码 false 完全依赖反代改写，反代
   *       misconfig 即明文传输 token。
   *   <li>Path=/：所有 console API 命中
   * </ul>
   */
  private String buildTokenCookie(ConsoleAuthTokenResponse body) {
    long maxAge = 8 * 3600L; // 默认 8h；与 JWT TTL 同步由签发服务控制
    if (body.expiresAt() != null && body.issuedAt() != null) {
      long delta = body.expiresAt().getEpochSecond() - body.issuedAt().getEpochSecond();
      if (delta > 0) {
        maxAge = delta;
      }
    }
    return ResponseCookie.from("batch_console_token", body.accessToken())
        .httpOnly(true)
        .secure(securityProperties.isCookieSecure())
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }

  /** 当前用户画像（租户、角色、菜单）。 */
  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public CommonResponse<ConsoleAuthProfileResponse> me(Authentication authentication) {
    return responseFactory.success(authApplicationService.profile(authentication));
  }

  // 轻量鉴权探针，专给 nginx auth_request / 反代鉴权用：登录 → 204，未登录 → 401（走 entryPoint）。
  // 不查 DB、不组菜单，避免 /docs/* 反代每次请求都拖累 /me。
  @GetMapping("/check")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> check() {
    return ResponseEntity.noContent().build();
  }

  /** 签发一次性 SSE ticket（5min 有效，用于 EventSource 连接鉴权）。 */
  @PostMapping("/stream/ticket")
  @PreAuthorize("isAuthenticated()")
  public CommonResponse<Map<String, String>> streamTicket(Authentication authentication) {
    Object raw = authentication.getPrincipal();
    if (!(raw instanceof ConsolePrincipal principal)) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.principal_missing");
    }
    // R4-P1-1：签发时带上当前 JWT 的真实角色集，消费端按此还原 ConsolePrincipal，
    // 不再从配置的 defaultAuthorities 兜底（防止低权用户拿 ticket 后被提权）。
    String ticket =
        sseTicketService.issue(principal.username(), principal.tenantId(), principal.authorities());
    return responseFactory.success(Map.of("ticket", ticket));
  }
}
