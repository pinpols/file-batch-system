package io.github.pinpols.batch.console.domain.audit.application.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.audit.service.ConsoleAiAuthorizationService;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 活动 AI 请求的身份、冲突与取消归属。 */
@Service
@RequiredArgsConstructor
public class ConsoleAiStreamRegistry {
  private final ConsoleAiAuthorizationService authorizationService;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final ConcurrentMap<String, Registration> streams = new ConcurrentHashMap<>();

  public Registration register(String requestId, Runnable cancel) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata identity = currentIdentity();
    Registration registration =
        new Registration(identity.tenantId(), identity.operatorId(), cancel);
    if (EmptyChecks.isNotNull(streams.putIfAbsent(requestId, registration))) {
      throw BizException.of(ResultCode.CONFLICT, "error.common.state_conflict");
    }
    return registration;
  }

  public void cancel(String requestId) {
    authorizationService.assertAllowed();
    ConsoleRequestMetadata identity = currentIdentity();
    Registration registration = streams.get(requestId);
    if (EmptyChecks.isNull(registration)
        || !Objects.equals(registration.tenantId, identity.tenantId())
        || !Objects.equals(registration.operatorId, identity.operatorId())) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }
    registration.cancel.run();
  }

  private ConsoleRequestMetadata currentIdentity() {
    ConsoleRequestMetadata metadata = metadataResolver.current();
    if (EmptyChecks.isNull(metadata)
        || !Texts.hasText(metadata.tenantId())
        || !Texts.hasText(metadata.operatorId())) {
      throw BizException.of(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.detailKey());
    }
    return metadata;
  }

  public void remove(String requestId, Registration registration) {
    streams.remove(requestId, registration);
  }

  public static final class Registration {
    private final String tenantId;
    private final String operatorId;
    private final Runnable cancel;

    private Registration(String tenantId, String operatorId, Runnable cancel) {
      this.tenantId = tenantId;
      this.operatorId = operatorId;
      this.cancel = cancel;
    }
  }
}
