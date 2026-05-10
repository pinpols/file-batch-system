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
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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

  /** 使用平台库中的控制台账号进行登录并签发 JWT。 */
  @PostMapping("/login")
  public CommonResponse<ConsoleAuthTokenResponse> login(
      @Valid @RequestBody ConsoleLoginRequest request) {
    return responseFactory.success(authApplicationService.login(request));
  }

  /** 为当前已认证用户签发 JWT。 */
  @PostMapping("/token")
  @PreAuthorize("isAuthenticated()")
  public CommonResponse<ConsoleAuthTokenResponse> token(Authentication authentication) {
    return responseFactory.success(authApplicationService.issueToken(authentication));
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
    String ticket = sseTicketService.issue(principal.username(), principal.tenantId());
    return responseFactory.success(Map.of("ticket", ticket));
  }
}
