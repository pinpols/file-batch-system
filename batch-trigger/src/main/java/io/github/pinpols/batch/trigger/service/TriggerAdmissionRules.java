package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.mapper.TenantStatusMapper;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 触发请求共用的租户准入、排程版本口径及稳定错误身份。
 *
 * <p>这里只核验现有规则，不创建请求、不推进 occurrence，也不改变调用方事务。 */
@Component
@RequiredArgsConstructor
final class TriggerAdmissionRules {

  static final String EXTERNAL_EVENT_IDENTITY_CONFLICT =
      "error.trigger.external_event_identity_conflict";
  static final String REQUEST_BODY_REQUIRED = "error.common.request_body_required";
  static final String CLOSED_BATCH_DAY_SQL_STATE = "BDA01";

  private final TenantStatusMapper tenantStatusMapper;

  void assertTenantActive(String tenantId) {
    String status = tenantStatusMapper.selectStatus(tenantId);
    if ("SUSPENDED".equals(status)) {
      // R-arch-audit-2026-05-23 P1: 用 ResultCode.TENANT_SUSPENDED 替代 BUSINESS_ERROR + 字符串后缀，
      // 让 QuartzLaunchJob 能通过 getCode() 枚举比较识别租户暂停语义，
      // 不再依赖脆弱的 e.getMessage().contains("tenant is suspended")。
      throw BizException.of(
          ResultCode.TENANT_SUSPENDED, "error.trigger.tenant_suspended", tenantId);
    }
  }

  int workflowVersion(TriggerDescriptor descriptor) {
    return EmptyChecks.isNull(descriptor.getWorkflowVersion())
        ? 0
        : descriptor.getWorkflowVersion();
  }
}
