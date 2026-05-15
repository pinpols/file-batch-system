package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.service.ConsoleAuthApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.SseTicketService;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.example.batch.console.web.request.auth.ConsoleLoginRequest;
import com.example.batch.console.web.response.auth.ConsoleAuthProfileResponse;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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

  /**
   * 使用平台库中的控制台账号进行登录并签发 JWT。
   *
   * <p>ADR-030 §D7 双轨：响应 body 仍带 accessToken 兼容老客户端；同时下发 HttpOnly cookie {@code
   * batch_console_token}，新前端不再读 localStorage，XSS 也拿不到 token。
   */
  @PostMapping("/login")
  public CommonResponse<ConsoleAuthTokenResponse> login(
      @Valid @RequestBody ConsoleLoginRequest request, HttpServletResponse response) {
    ConsoleAuthTokenResponse body = authApplicationService.login(request);
    response.addHeader(HttpHeaders.SET_COOKIE, buildTokenCookie(body));
    return responseFactory.success(body);
  }

  /** 为当前已认证用户签发 JWT。 */
  @PostMapping("/token")
  @PreAuthorize("isAuthenticated()")
  public CommonResponse<ConsoleAuthTokenResponse> token(
      Authentication authentication, HttpServletResponse response) {
    ConsoleAuthTokenResponse body = authApplicationService.issueToken(authentication);
    response.addHeader(HttpHeaders.SET_COOKIE, buildTokenCookie(body));
    return responseFactory.success(body);
  }

  /** 登出：把 cookie 设置成 Max-Age=0 立即失效（前端调用此端点替代手动清 localStorage）。 */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletResponse response) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("batch_console_token", "")
            .httpOnly(true)
            .secure(false) // 与 buildTokenCookie 一致；生产由前端反代加 secure
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
    return ResponseEntity.noContent().build();
  }

  /**
   * 构造 HttpOnly token cookie。
   *
   * <ul>
   *   <li>HttpOnly：JS 不可读 → XSS 也无法外泄
   *   <li>SameSite=Lax：跨站 POST/iframe 不带 cookie；同站 GET / 普通跳转保留
   *   <li>Secure=false：与本地 HTTP 开发兼容；生产由反代/网关层强制 HTTPS 并加 Secure flag
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
        .secure(false)
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
