package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.web.request.config.QuotaPolicySaveRequest;
import io.github.pinpols.batch.console.web.response.config.QuotaPolicyResponse;

/** 配额策略应用服务：管理租户配额策略的 CRUD 及启停操作。 */
public interface ConsoleQuotaPolicyApplicationService {

  PageResponse<QuotaPolicyResponse> list(
      String tenantId, String policyCode, Boolean enabled, int pageNo, int pageSize);

  QuotaPolicyResponse create(QuotaPolicySaveRequest request);

  QuotaPolicyResponse update(Long id, QuotaPolicySaveRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
