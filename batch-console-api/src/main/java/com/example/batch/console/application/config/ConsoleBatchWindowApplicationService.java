package com.example.batch.console.application.config;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.web.request.file.BatchWindowCreateRequest;
import com.example.batch.console.web.request.file.BatchWindowUpdateRequest;
import java.util.Map;

/** 批量窗口应用服务：管理批量执行窗口的 CRUD 及启停操作。 */
public interface ConsoleBatchWindowApplicationService {

  PageResponse<Map<String, Object>> list(
      String tenantId, String windowCode, Boolean enabled, int pageNo, int pageSize);

  Map<String, Object> create(BatchWindowCreateRequest request);

  Map<String, Object> update(Long id, BatchWindowUpdateRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
