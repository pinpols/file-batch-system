package io.github.pinpols.batch.console.domain.audit.support;

import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Hashes;
import io.github.pinpols.batch.console.domain.audit.mapper.OperationAuditMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 记录 OIDC 登录结果；审计只含平台账号与请求摘要，不写 subject、claims、code 或令牌。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleOidcLoginAudit {

  private final OperationAuditMapper auditMapper;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  public void succeeded(String tenantId, String username) {
    record(tenantId, username, "SUCCESS", null);
  }

  public void failed(String tenantId, String errorCode) {
    record(tenantId, "anonymous", "FAILED", errorCode);
  }

  private void record(String tenantId, String operatorId, String result, String errorCode) {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    OperationAuditEvent event = new OperationAuditEvent(
        tenantId,
        "auth",
        operatorId,
        "auth.oidc.login",
        operatorId,
        "OIDC",
        result,
        errorCode,
        EmptyChecks.isNull(errorCode) ? null : "OIDC authentication failed",
        null,
        metadata.traceId(),
        metadata.requestId(),
        Hashes.sha256Short(metadata.clientIp()),
        null,
        1,
        BatchDateTimeSupport.utcNow());
    try {
      auditMapper.insert(event);
    } catch (RuntimeException exception) {
      log.warn(
          "OIDC login audit insert failed tenant={} result={}",
          LogSanitizer.value(tenantId),
          LogSanitizer.value(result));
    }
  }
}
