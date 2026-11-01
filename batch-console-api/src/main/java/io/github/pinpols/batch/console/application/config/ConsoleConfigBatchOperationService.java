package io.github.pinpols.batch.console.application.config;

/** 配置批操作的登记与原请求结果回查。 */
public interface ConsoleConfigBatchOperationService {

  ConfigBatchOperationContext begin(ConfigBatchOperationRequest request);

  ConfigBatchOperationRecoveryResponse find(
      String auditTenantId, String requestIdentityHash, String operationKind);
}
