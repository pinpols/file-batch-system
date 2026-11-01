package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyPrincipal;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Trigger 请求授权策略，保证外部事件密钥只能访问其授权租户和事件源。 */
@Service
@RequiredArgsConstructor
public class TriggerRequestPolicy {

  private final TriggerApiKeyVerifier apiKeyVerifier;

  public void authorizeLaunch(
      TriggerApiKeyPrincipal principal,
      TriggerType triggerType,
      String tenantId,
      String externalEventSource) {
    if (EmptyChecks.isNotNull(principal)
        && (triggerType != TriggerType.EVENT
            || !apiKeyVerifier.authorizesExternalEvent(principal, tenantId, externalEventSource))) {
      throw forbidden();
    }
  }

  public void authorizeExternalEvent(
      TriggerApiKeyPrincipal principal, String tenantId, String eventSource) {
    if (EmptyChecks.isNull(principal)
        || !apiKeyVerifier.authorizesExternalEvent(principal, tenantId, eventSource)) {
      throw forbidden();
    }
  }

  public void authorizeExternalEvent(TriggerApiKeyPrincipal principal, String tenantId) {
    authorizeExternalEvent(
        principal, tenantId, EmptyChecks.isNull(principal) ? null : principal.keyName());
  }

  private static BizException forbidden() {
    return BizException.of(ResultCode.FORBIDDEN, "error.trigger.external_event_source_forbidden");
  }
}
