package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import org.springframework.web.util.HtmlUtils;

/** 原事件/目标的恢复元数据,不返回渠道凭据或原始业务载荷。 */
public record TerminalDeliveryRecoveryResponse(
    String source,
    Long id,
    String terminalEventKey,
    String eventType,
    String deliveryStatus,
    int attempt,
    Instant nextRetryAt,
    Instant claimUntil,
    String errorMessage,
    Instant createdAt) {

  /**
   * 回显错误信息长度上限，与终态投递错误列中较大的 webhook_delivery_log.response_body
   * VARCHAR(2048)（V45）一致。
   */
  private static final int ERROR_MESSAGE_MAX_LENGTH = 2048;

  public TerminalDeliveryRecoveryResponse {
    // 热表和冷表均由 MyBatis 直接构造此响应；历史错误值也必须在回查边界遮蔽。
    if (EmptyChecks.isNotNull(errorMessage)) {
      String decodedError = HtmlUtils.htmlUnescape(errorMessage);
      String normalizedError = ConsoleTextSanitizer.normalize(decodedError);
      String maskedError =
          SwallowedExceptionLogger.safeMessage(normalizedError, ERROR_MESSAGE_MAX_LENGTH);
      errorMessage = ConsoleTextSanitizer.safeDisplay(maskedError, ERROR_MESSAGE_MAX_LENGTH);
    }
  }
}
