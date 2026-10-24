package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.mapper.ConfigSyncTargetReceiptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 配置同步专用的目标提交边界；原批量初始化入口不承担同步审计职责。 */
@Component
@RequiredArgsConstructor
class ConfigSyncTargetExecutor {

  private final TenantConfigInitTenantExecutor tenantExecutor;
  private final ConfigSyncTargetReceiptMapper receiptMapper;

  record Target(String auditTenantId, Long logId, String tenantId) {}

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  TenantInitResult execute(Target target, TenantConfigBatchInitRequest request, String operator) {
    TenantInitResult existing = readReceipt(target);
    if (EmptyChecks.isNotNull(existing)) {
      return existing;
    }
    TenantInitResult result = tenantExecutor.applyWithinTransaction(
        target.tenantId(), request, operator, request.isDryRun());
    if (result.success()) {
      insertReceipt(target, result);
    }
    // false 结果对应执行器显式回滚；调用方只能在此事务结束后保存失败收据。
    return result;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void recordRolledBack(Target target, TenantInitResult result) {
    if (result.success()) {
      throw new IllegalArgumentException("Committed results must be recorded with target changes");
    }
    TenantInitResult existing = readReceipt(target);
    if (EmptyChecks.isNotNull(existing)) {
      if (!existing.equals(result)) {
        throw new IllegalStateException("Config sync receipt already records a different outcome");
      }
      return;
    }
    insertReceipt(target, result);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  TenantInitResult findReceipt(Target target) {
    return readReceipt(target);
  }

  private TenantInitResult readReceipt(Target target) {
    receiptMapper.setTenantContext(target.tenantId());
    String json = receiptMapper.selectResult(
        target.auditTenantId(), target.logId(), target.tenantId());
    if (EmptyChecks.isNull(json)) {
      return null;
    }
    TenantInitResult result = JsonUtils.fromJsonStrict(json, TenantInitResult.class);
    if (!target.tenantId().equals(result.tenantId())) {
      throw new IllegalStateException("Config sync receipt tenant does not match its target");
    }
    return result;
  }

  private void insertReceipt(Target target, TenantInitResult result) {
    String resultJson = JsonUtils.toJson(result);
    int rows = receiptMapper.insert(
        target.auditTenantId(), target.logId(), target.tenantId(), resultJson);
    if (rows != 1) {
      throw new IllegalStateException("Config sync receipt must affect exactly one row");
    }
  }
}
