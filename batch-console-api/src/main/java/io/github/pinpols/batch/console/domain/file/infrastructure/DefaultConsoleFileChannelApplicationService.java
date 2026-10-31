package io.github.pinpols.batch.console.domain.file.infrastructure;

import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileChannelType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileChannelApplicationService;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileChannelQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.request.FileChannelCreateRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.request.FileChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileChannelResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileProjectionMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpdateParam;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.query.FileChannelConfigQuery;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
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
  private final TenantIdResolver tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @Override
  public PageResponse<ConsoleFileChannelResponse> list(FileChannelQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    long total = mapper.countByQuery(
        tenantId, request.getChannelCode(), request.getChannelType(), request.getEnabled());
    FileChannelConfigQuery fileChannelConfigQuery = FileChannelConfigQuery.builder()
        .tenantId(tenantId)
        .channelCode(request.getChannelCode())
        .channelType(request.getChannelType())
        .enabled(request.getEnabled())
        .pageRequest(pageRequest)
        .build();
    List<FileChannelConfigQueryView> items = mapper.selectByQuery(fileChannelConfigQuery);
    return new PageResponse<>(
        total,
        pageRequest.pageNo(),
        pageRequest.pageSize(),
        items.stream().map(ConsoleFileProjectionMapper::channelView).toList());
  }

  @Override
  public ConsoleFileChannelResponse get(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    FileChannelConfigQueryView row = mapper.selectById(resolved, id);
    FileChannelConfigQueryView foundRow = Guard.requireFound(row, "file channel not found: " + id);
    return ConsoleFileProjectionMapper.channelView(foundRow);
  }

  @Override
  public ConsoleFileChannelResponse create(FileChannelCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    FileChannelConfigQueryView existing =
        mapper.selectByUniqueKey(tenantId, request.getChannelCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "channel code already exists: " + request.getChannelCode());
    }
    String operator = requestMetadataResolver.current().operatorId();
    FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setChannelCode(request.getChannelCode());
    param.setChannelName(request.getChannelName());
    String requestedChannelType = request.getChannelType();
    String normalizedChannelType = normalizeChannelType(requestedChannelType);
    param.setChannelType(normalizedChannelType);
    param.setTargetEndpoint(request.getTargetEndpoint());
    param.setAuthType(request.getAuthType());
    // DB 这两列 NOT NULL,FE 不传时给安全默认
    String requestedConfigJson = request.getConfigJson();
    String normalizedConfigJson = normalizeConfigJson(requestedConfigJson);
    param.setConfigJson(normalizedConfigJson);
    param.setReceiptPolicy(
        request.getReceiptPolicy() != null ? request.getReceiptPolicy() : "NONE");
    param.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30);
    param.setEnabled(request.getEnabled() == null || request.getEnabled());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    // 软删后重建:活跃同 code 已被上面 selectByUniqueKey 预检拒(CONFLICT);此处用 upsert,
    // 命中软删残留行(is_deleted=true)时 on conflict do update 复活,避免纯 INSERT 撞 UNIQUE → 500。
    // 与 notification/webhook/alert 三张兄弟表的软删复活约定一致。
    mapper.upsertFileChannelConfig(param);
    FileChannelConfigQueryView createdRow =
        mapper.selectByUniqueKey(tenantId, request.getChannelCode());
    return ConsoleFileProjectionMapper.channelView(createdRow);
  }

  @Override
  public ConsoleFileChannelResponse update(Long id, FileChannelUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    FileChannelConfigQueryView existing =
        Guard.requireFound(mapper.selectById(tenantId, id), "file channel not found: " + id);
    String operator = requestMetadataResolver.current().operatorId();
    FileChannelConfigUpdateParam param = new FileChannelConfigUpdateParam();
    param.setTenantId(tenantId);
    param.setId(id);
    param.setChannelName(
        EmptyChecks.isNotNull(request.getChannelName())
            ? request.getChannelName()
            : existing.channelName());
    String updatedChannelType = EmptyChecks.isNotNull(request.getChannelType())
        ? normalizeChannelType(request.getChannelType())
        : existing.channelType();
    param.setChannelType(updatedChannelType);
    String requestedTargetEndpoint = request.getTargetEndpoint();
    String targetEndpoint;
    if (EmptyChecks.isNotNull(requestedTargetEndpoint)) {
      targetEndpoint =
          SecretMasking.preserveMaskedEndpoint(requestedTargetEndpoint, existing.targetEndpoint());
    } else {
      targetEndpoint = existing.targetEndpoint();
    }
    param.setTargetEndpoint(targetEndpoint);
    param.setAuthType(
        EmptyChecks.isNotNull(request.getAuthType()) ? request.getAuthType() : existing.authType());
    String requestedConfigJson = request.getConfigJson();
    String configJson;
    if (EmptyChecks.isNotNull(requestedConfigJson)) {
      configJson = preserveMaskedConfig(requestedConfigJson, existing.configJson());
    } else {
      configJson = EmptyChecks.isNull(existing.configJson())
          ? null
          : existing.configJson().toString();
    }
    param.setConfigJson(configJson);
    param.setReceiptPolicy(
        EmptyChecks.isNotNull(request.getReceiptPolicy())
            ? request.getReceiptPolicy()
            : existing.receiptPolicy());
    param.setTimeoutSeconds(
        request.getTimeoutSeconds() != null
            ? request.getTimeoutSeconds()
            : existing.timeoutSeconds());
    param.setEnabled(
        EmptyChecks.isNotNull(request.getEnabled()) ? request.getEnabled() : existing.enabled());
    param.setUpdatedBy(operator);
    mapper.updateFileChannelConfig(param);
    FileChannelConfigQueryView updatedChannelRow = mapper.selectById(tenantId, id);
    return ConsoleFileProjectionMapper.channelView(updatedChannelRow);
  }

  private static String normalizeChannelType(String channelType) {
    FileChannelType matched = DictEnum.fromCode(FileChannelType.class, channelType);
    if (matched == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.file_channel.unsupported_channel_type",
          channelType,
          DictEnum.codeList(FileChannelType.class));
    }
    return matched.code();
  }

  private static String preserveMaskedConfig(String submitted, Object storedValue) {
    String normalized = normalizeConfigJson(submitted);
    String stored = EmptyChecks.isNull(storedValue) ? null : storedValue.toString();
    return SecretMasking.preserveMaskedJson(normalized, stored);
  }

  private static String normalizeConfigJson(String configJson) {
    if (EmptyChecks.isBlank(configJson)) {
      return "{}";
    }
    try {
      Object parsed = JsonUtils.fromJson(configJson, Object.class);
      if (parsed instanceof Map<?, ?>) {
        return configJson;
      }
    } catch (IllegalArgumentException ignored) {
      // 避免在接口错误中暴露提交的 JSON 解析细节。
    }
    throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.file_channel.invalid_config_json");
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
