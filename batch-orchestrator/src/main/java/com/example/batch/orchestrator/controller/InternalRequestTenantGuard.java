package com.example.batch.orchestrator.controller;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.config.InternalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * /internal API 的 API-Key 租户边界守卫。
 *
 * <p>legacy X-Internal-Secret 不绑定租户,继续信任请求体;租户 API-Key 由 filter 解析出真实租户后,这里统一和 body/query tenantId
 * 对账。请求未带 tenantId 时使用解析租户,请求显式带了不一致租户时拒绝。
 */
final class InternalRequestTenantGuard {

  private InternalRequestTenantGuard() {}

  static String resolveTenant(HttpServletRequest request, String declaredTenantId) {
    Object resolved = request.getAttribute(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID);
    if (!(resolved instanceof String resolvedTenantId) || !Texts.hasText(resolvedTenantId)) {
      return declaredTenantId;
    }
    if (!Texts.hasText(declaredTenantId)) {
      return resolvedTenantId;
    }
    if (!resolvedTenantId.equals(declaredTenantId)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.common.tenant_id_mismatch");
    }
    return declaredTenantId;
  }
}
