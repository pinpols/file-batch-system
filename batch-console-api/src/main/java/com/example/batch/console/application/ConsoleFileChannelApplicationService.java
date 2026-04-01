package com.example.batch.console.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.FileChannelCreateRequest;
import com.example.batch.console.web.request.FileChannelUpdateRequest;
import java.util.Map;

/**
 * 文件通道应用服务：管理文件通道配置的 CRUD 及启停操作。
 */
public interface ConsoleFileChannelApplicationService {

    PageResponse<Map<String, Object>> list(FileChannelQueryRequest request);

    Map<String, Object> get(Long id, String tenantId);

    Map<String, Object> create(FileChannelCreateRequest request);

    Map<String, Object> update(Long id, FileChannelUpdateRequest request);

    void delete(Long id, String tenantId);

    void toggle(Long id, String tenantId, Boolean enabled);
}
