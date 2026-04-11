package com.example.batch.console.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.FileTemplateCreateRequest;
import com.example.batch.console.web.request.FileTemplateUpdateRequest;

import java.util.Map;

/** 文件模板应用服务：管理文件模板配置的 CRUD 及启停操作。 */
public interface ConsoleFileTemplateApplicationService {

    PageResponse<Map<String, Object>> list(FileTemplateQueryRequest request);

    Map<String, Object> get(Long id, String tenantId);

    Map<String, Object> create(FileTemplateCreateRequest request);

    Map<String, Object> update(Long id, FileTemplateUpdateRequest request);

    void toggle(Long id, String tenantId, Boolean enabled);
}
