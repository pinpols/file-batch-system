package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.SecretComparator;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService;
import io.github.pinpols.batch.console.domain.notification.service.AlertmanagerNotifyService.AmNotifyOutcome;
import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertmanager webhook 出口接收端(internal 内网端点,非 console 用户 API,故不叫 {@code Console*Controller}, 也不纳入
 * console-api.openapi.yaml —— 见任务报告认证/契约小节)。
 *
 * <p>{@code POST /internal/am-notify/{receiver}}:AM 独立进程 → console-api。AM 够不到 console 的 cookie/JWT
 * (ADR-030 §D7 单一 cookie 认证域),故本端点在 {@code ConsoleSecurityConfiguration} 里 permitAll + CSRF 豁免,
 * 由本控制器用共享密钥(bearer token,与 AM receiver {@code http_config.authorization} 对应)自校验。fail-closed:
 * 未配置密钥一律 401。
 *
 * <p>与 orchestrator 的 {@code X-Internal-Secret} 内网通道同思路(共享密钥 header),差异是 AM 官方只支持 {@code
 * Authorization: Bearer},故用 bearer 而非自定义 header。
 */
@RestController
@RequestMapping("/internal/am-notify")
@Slf4j
@RequiredArgsConstructor
public class AlertmanagerNotifyController {

  private static final String BEARER_PREFIX = "Bearer ";

  private final AlertmanagerNotifyProperties properties;
  private final AlertmanagerNotifyService notifyService;

  /** 接住某 receiver 的一批 AM 告警,鉴权后投递到真实渠道并落投递日志。 */
  @PostMapping("/{receiver}")
  public CommonResponse<AmNotifyOutcome> receive(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String receiver,
      @RequestBody AlertmanagerWebhookPayload payload) {
    if (!properties.isEnabled()) {
      throw BizException.of(
          ResultCode.SERVICE_UNAVAILABLE, "error.common.unauthorized_detail", "am-notify disabled");
    }
    authenticate(authorization);
    AmNotifyOutcome outcome = notifyService.deliver(receiver, payload);
    return CommonResponse.success(outcome);
  }

  /** fail-closed 共享密钥校验:未配置密钥 / 缺 header / 密钥不匹配一律 401(常量时间比对,不泄漏长度)。 */
  private void authenticate(String authorization) {
    String expected = properties.getBearerToken();
    if (!Texts.hasText(expected)) {
      log.warn("am-notify rejected: bearer token not configured (fail-closed)");
      throw BizException.of(
          ResultCode.UNAUTHORIZED, "error.common.unauthorized_detail", "am-notify not configured");
    }
    String provided =
        authorization != null && authorization.startsWith(BEARER_PREFIX)
            ? authorization.substring(BEARER_PREFIX.length()).trim()
            : null;
    if (!SecretComparator.constantTimeEquals(expected, provided)) {
      log.warn("am-notify rejected: invalid or missing bearer token");
      throw BizException.of(
          ResultCode.UNAUTHORIZED, "error.common.unauthorized_detail", "invalid am-notify token");
    }
  }
}
