package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.job.web.request.BatchWindowCreateRequest;
import io.github.pinpols.batch.console.domain.job.web.request.BatchWindowUpdateRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchWindowResponse;

/** 批量窗口应用服务：管理批量执行窗口的 CRUD 及启停操作。 */
public interface ConsoleBatchWindowApplicationService {

  PageResponse<ConsoleBatchWindowResponse> list(
      String tenantId, String windowCode, Boolean enabled, int pageNo, int pageSize);

  ConsoleBatchWindowResponse create(BatchWindowCreateRequest request);

  ConsoleBatchWindowResponse update(Long id, BatchWindowUpdateRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
