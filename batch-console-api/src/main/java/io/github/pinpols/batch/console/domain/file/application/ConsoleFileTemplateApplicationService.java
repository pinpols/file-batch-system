package io.github.pinpols.batch.console.domain.file.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.file.web.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.web.request.FileTemplateCreateRequest;
import io.github.pinpols.batch.console.domain.file.web.request.FileTemplateUpdateRequest;
import io.github.pinpols.batch.console.domain.file.web.response.ConsoleFileTemplateResponse;

/** 文件模板应用服务：管理文件模板配置的 CRUD 及启停操作。 */
public interface ConsoleFileTemplateApplicationService {

  PageResponse<ConsoleFileTemplateResponse> list(FileTemplateQueryRequest request);

  ConsoleFileTemplateResponse get(Long id, String tenantId);

  ConsoleFileTemplateResponse create(FileTemplateCreateRequest request);

  ConsoleFileTemplateResponse update(Long id, FileTemplateUpdateRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);

  FileTemplateMappingDraftResult draftMapping(FileTemplateMappingDraftCommand command);
}
