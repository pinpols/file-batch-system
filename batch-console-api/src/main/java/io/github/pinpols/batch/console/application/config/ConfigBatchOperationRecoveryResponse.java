package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import java.util.List;
import java.util.Map;

/** 按原目标顺序返回已确认的回执；缺失的目标仍为未知。 */
public record ConfigBatchOperationRecoveryResponse(
    String operationId,
    String operationKind,
    Map<String, Object> responseMetadata,
    Status status,
    List<Target> targets) {

  public enum Status {
    COMPLETE,
    UNKNOWN
  }

  public record Target(int ordinal, String tenantId, Status status, TenantInitResult result) {}
}
