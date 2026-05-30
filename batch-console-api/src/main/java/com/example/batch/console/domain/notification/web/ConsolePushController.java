package com.example.batch.console.domain.notification.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.notification.service.ConsolePushSubscriptionService;
import com.example.batch.console.domain.notification.web.request.ConsolePushSubscribeRequest;
import com.example.batch.console.domain.notification.web.request.ConsolePushUnsubscribeRequest;
import com.example.batch.console.domain.notification.web.response.ConsolePushVapidPublicKeyResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PWA Web Push 订阅 / 取消订阅 / VAPID 公钥三端点。
 *
 * <p>前端 {@code src/composables/useWebPush.ts}(2026-05-16)对接;Service Worker 收到 push event 后在 {@code
 * public/push-handler.js} 显通知 + click 导航。
 */
@RestController
@Validated
@RequestMapping("/api/console/push")
@RequiredArgsConstructor
public class ConsolePushController {

  private final ConsolePushSubscriptionService subscriptionService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  /**
   * VAPID 公钥 — 前端 push API 注册时作为 applicationServerKey。
   *
   * <p>**公开端点**(无 @PreAuthorize):前端订阅前还没登录态时也可能调到;且公钥本身公开。
   */
  @GetMapping("/vapid-public-key")
  public CommonResponse<ConsolePushVapidPublicKeyResponse> vapidPublicKey() {
    return responseFactory.success(
        new ConsolePushVapidPublicKeyResponse(subscriptionService.vapidPublicKey()));
  }

  /**
   * 订阅 — 前端浏览器授权 push 后,把 PushSubscription.toJSON() 完整发过来。
   *
   * <p>同 (tenant, user, endpoint) 重复发 → UPSERT,刷新 keys + last_seen_at,不报错。
   */
  @PostMapping("/subscribe")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<CommonResponse<Void>> subscribe(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @Valid @RequestBody ConsolePushSubscribeRequest request,
      HttpServletRequest httpRequest) {
    String username = requestMetadataResolver.current().operatorId();
    String ua = httpRequest.getHeader("User-Agent");
    subscriptionService.subscribe(tenantId, username, request, ua);
    return ResponseEntity.status(201).body(responseFactory.success(null));
  }

  /** 取消订阅:按 endpoint 精确删,不影响同用户其它设备。 */
  @PostMapping("/unsubscribe")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<CommonResponse<Void>> unsubscribe(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @Valid @RequestBody ConsolePushUnsubscribeRequest request) {
    String username = requestMetadataResolver.current().operatorId();
    subscriptionService.unsubscribe(tenantId, username, request.endpoint());
    // 与 subscribe 对齐,统一返 CommonResponse(违反 CLAUDE.md §Java 规则 #6 的裸 ResponseEntity 已移除)
    return ResponseEntity.ok(responseFactory.success(null));
  }
}
