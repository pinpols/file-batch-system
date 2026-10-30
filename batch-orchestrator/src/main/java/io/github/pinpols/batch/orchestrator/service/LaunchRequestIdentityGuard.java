package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Objects;
import java.util.Set;

/** 启动和持久等待共用的原请求身份；调用方负责事务、行锁和各入口允许的状态范围。 */
public final class LaunchRequestIdentityGuard {

  private static final Set<String> PENDING_STATUSES = Set.of(
      TriggerRequestStatus.ACCEPTED.code(),
      TriggerRequestStatus.PROCESSING.code(),
      TriggerRequestStatus.WAITING.code());

  private LaunchRequestIdentityGuard() {}

  public static boolean matches(LaunchRequest request, TriggerRequestEntity original) {
    return EmptyChecks.isNotNull(request)
        && EmptyChecks.isNotNull(original)
        && Objects.equals(request.tenantId(), original.getTenantId())
        && Objects.equals(request.requestId(), original.getRequestId())
        && Objects.equals(request.jobCode(), original.getJobCode())
        && Objects.equals(request.workflowDefinitionId(), original.getWorkflowDefinitionId())
        && Objects.equals(request.bizDate(), original.getBizDate())
        && Boolean.TRUE.equals(request.dryRun()) == Boolean.TRUE.equals(original.getDryRun());
  }

  public static void requirePending(LaunchRequest request, TriggerRequestEntity original) {
    boolean acceptedRequestIsStillPending =
        matches(request, original) && PENDING_STATUSES.contains(original.getRequestStatus());
    if (!acceptedRequestIsStillPending) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "Launch request identity or state no longer matches the accepted request");
    }
  }
}
