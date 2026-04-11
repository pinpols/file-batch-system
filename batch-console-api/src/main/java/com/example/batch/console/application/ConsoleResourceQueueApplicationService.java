package com.example.batch.console.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.web.request.ResourceQueueCreateRequest;
import com.example.batch.console.web.request.ResourceQueueUpdateRequest;

import java.util.Map;

/** 资源队列应用服务：管理资源队列的 CRUD 及启停操作。 */
public interface ConsoleResourceQueueApplicationService {

    PageResponse<Map<String, Object>> list(
            String tenantId,
            String queueCode,
            String queueType,
            Boolean enabled,
            int pageNo,
            int pageSize);

    Map<String, Object> create(ResourceQueueCreateRequest request);

    Map<String, Object> update(Long id, ResourceQueueUpdateRequest request);

    void toggle(Long id, String tenantId, Boolean enabled);
}
