package com.example.batch.console.domain.file.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.file.web.query.FileChannelQueryRequest;
import com.example.batch.console.domain.file.web.request.FileChannelCreateRequest;
import com.example.batch.console.domain.file.web.request.FileChannelUpdateRequest;
import java.util.Map;

/**
 * 文件通道应用服务：管理文件通道配置的 CRUD 及启停操作。
 *
 * <p>通道禁用（{@code toggle enabled=false}）后不删除配置，分发调度器会跳过该渠道；
 * 重新启用后立即生效，无需重启。删除通道前必须先禁用，否则正在进行的分发任务可能引用已消失的配置。
 */
public interface ConsoleFileChannelApplicationService {

  PageResponse<Map<String, Object>> list(FileChannelQueryRequest request);

  Map<String, Object> get(Long id, String tenantId);

  Map<String, Object> create(FileChannelCreateRequest request);

  Map<String, Object> update(Long id, FileChannelUpdateRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
