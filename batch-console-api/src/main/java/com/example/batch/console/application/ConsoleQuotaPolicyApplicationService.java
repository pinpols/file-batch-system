package com.example.batch.console.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.web.request.QuotaPolicySaveRequest;
import java.util.Map;

/** 配额策略应用服务：管理租户配额策略的 CRUD 及启停操作。 */
public interface ConsoleQuotaPolicyApplicationService {

  PageResponse<Map<String, Object>> list(
      String tenantId, String policyCode, Boolean enabled, int pageNo, int pageSize);

  Map<String, Object> create(QuotaPolicySaveRequest request);

  Map<String, Object> update(Long id, QuotaPolicySaveRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
