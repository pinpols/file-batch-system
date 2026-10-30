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

  public TerminalDeliveryRecoveryResponse {
    // 热表和冷表均由 MyBatis 直接构造此响应；历史错误值也必须在回查边界遮蔽。
    if (EmptyChecks.isNotNull(errorMessage)) {
      String decodedError = HtmlUtils.htmlUnescape(errorMessage);
      String normalizedError = ConsoleTextSanitizer.normalize(decodedError);
      String maskedError = SwallowedExceptionLogger.safeMessage(normalizedError, 2048);
      errorMessage = ConsoleTextSanitizer.safeDisplay(maskedError, 2048);
    }
  }
}
