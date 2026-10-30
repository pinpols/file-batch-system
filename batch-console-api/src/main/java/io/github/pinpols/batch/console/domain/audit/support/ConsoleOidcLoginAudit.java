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
    String errorDescription = EmptyChecks.isNull(errorCode) ? null : "OIDC authentication failed";
    String traceId = metadata.traceId();
    String requestId = metadata.requestId();
    String clientIp = metadata.clientIp();
    String clientIpHash = Hashes.sha256Short(clientIp);
    OperationAuditEvent event = OperationAuditEvent.builder()
        .tenantId(tenantId)
        .aggregateType("auth")
        .aggregateId(operatorId)
        .action("auth.oidc.login")
        .operatorId(operatorId)
        .operatorRole("OIDC")
        .result(result)
        .errorCode(errorCode)
        .errorMessage(errorDescription)
        .traceId(traceId)
        .requestId(requestId)
        .ipHash(clientIpHash)
        .eventVersion(1)
        .createdAt(BatchDateTimeSupport.utcNow())
        .build();
    try {
      auditMapper.insert(event);
    } catch (RuntimeException exception) {
      String sanitizedTenantId = LogSanitizer.value(tenantId);
      String sanitizedResult = LogSanitizer.value(result);
      log.warn(
          "OIDC login audit insert failed tenant={} result={}", sanitizedTenantId, sanitizedResult);
    }
  }
}
