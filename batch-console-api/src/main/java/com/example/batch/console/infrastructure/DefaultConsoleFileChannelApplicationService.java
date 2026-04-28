package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ConsoleFileChannelApplicationService;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.mapper.param.FileChannelConfigUpdateParam;
import com.example.batch.console.mapper.param.FileChannelConfigUpsertParam;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.FileChannelCreateRequest;
import com.example.batch.console.web.request.FileChannelUpdateRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleFileChannelApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleFileChannelApplicationService
    implements ConsoleFileChannelApplicationService {

  private final FileChannelConfigMapper mapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @Override
  public PageResponse<Map<String, Object>> list(FileChannelQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    long total =
        mapper.countByQuery(
            tenantId, request.getChannelCode(), request.getChannelType(), request.getEnabled());
    List<Map<String, Object>> items =
        mapper.selectByQuery(
            tenantId,
            request.getChannelCode(),
            request.getChannelType(),
            request.getEnabled(),
            pageRequest);
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public Map<String, Object> get(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return Guard.requireFound(mapper.selectById(resolved, id), "file channel not found: " + id);
  }

  @Override
  public Map<String, Object> create(FileChannelCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing = mapper.selectByUniqueKey(tenantId, request.getChannelCode());
    if (existing != null) {
      throw new BizException(
          ResultCode.CONFLICT, "channel code already exists: " + request.getChannelCode());
    }
    String operator = requestMetadataResolver.current().operatorId();
    FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setChannelCode(request.getChannelCode());
    param.setChannelName(request.getChannelName());
    param.setChannelType(request.getChannelType());
    param.setTargetEndpoint(request.getTargetEndpoint());
    param.setAuthType(request.getAuthType());
    param.setConfigJson(request.getConfigJson());
    param.setReceiptPolicy(request.getReceiptPolicy());
    param.setTimeoutSeconds(request.getTimeoutSeconds());
    param.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    mapper.insertFileChannelConfig(param);
    return mapper.selectByUniqueKey(tenantId, request.getChannelCode());
  }

  @Override
  public Map<String, Object> update(Long id, FileChannelUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(mapper.selectById(tenantId, id), "file channel not found: " + id);
    String operator = requestMetadataResolver.current().operatorId();
    FileChannelConfigUpdateParam param = new FileChannelConfigUpdateParam();
    param.setTenantId(tenantId);
    param.setId(id);
    param.setChannelName(
        request.getChannelName() != null
            ? request.getChannelName()
            : (String) existing.get("channel_name"));
    param.setChannelType(
        request.getChannelType() != null
            ? request.getChannelType()
            : (String) existing.get("channel_type"));
    param.setTargetEndpoint(
        request.getTargetEndpoint() != null
            ? request.getTargetEndpoint()
            : (String) existing.get("target_endpoint"));
    param.setAuthType(
        request.getAuthType() != null ? request.getAuthType() : (String) existing.get("auth_type"));
    param.setConfigJson(
        request.getConfigJson() != null
            ? request.getConfigJson()
            : existing.get("config_json") != null ? existing.get("config_json").toString() : null);
    param.setReceiptPolicy(
        request.getReceiptPolicy() != null
            ? request.getReceiptPolicy()
            : (String) existing.get("receipt_policy"));
    param.setTimeoutSeconds(
        request.getTimeoutSeconds() != null
            ? request.getTimeoutSeconds()
            : (Integer) existing.get("timeout_seconds"));
    param.setEnabled(
        request.getEnabled() != null ? request.getEnabled() : (Boolean) existing.get("enabled"));
    param.setUpdatedBy(operator);
    mapper.updateFileChannelConfig(param);
    return mapper.selectById(tenantId, id);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = mapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file_channel.not_found", id);
    }
  }
}
