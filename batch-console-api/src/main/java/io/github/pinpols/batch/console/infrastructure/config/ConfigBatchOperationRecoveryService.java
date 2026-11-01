package io.github.pinpols.batch.console.infrastructure.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationContext;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse.Status;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRecoveryResponse.Target;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationRequest;
import io.github.pinpols.batch.console.application.config.ConsoleConfigBatchOperationService;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.domain.config.param.ConfigBatchOperationInsertParam;
import io.github.pinpols.batch.console.domain.config.view.ConfigBatchOperationRow;
import io.github.pinpols.batch.console.mapper.ConfigBatchOperationMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConfigBatchOperationRecoveryService implements ConsoleConfigBatchOperationService {

  private final ConfigBatchOperationMapper mapper;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ConfigBatchOperationContext begin(ConfigBatchOperationRequest request) {
    mapper.setTenantContext(request.auditTenantId());
    String targetsJson = writeJson(request.targets());
    JsonNode metadata = request.responseMetadata();
    String metadataJson =
        writeJson(EmptyChecks.isNull(metadata) ? objectMapper.createObjectNode() : metadata);
    ConfigBatchOperationInsertParam params = new ConfigBatchOperationInsertParam(
        request.auditTenantId(),
        request.operationId(),
        request.requestIdentityHash(),
        request.operationKind(),
        targetsJson,
        metadataJson);
    if (mapper.insertOperation(params) != 1) {
      throw BizException.of(ResultCode.CONFLICT, ResultCode.CONFLICT.detailKey());
    }
    return new ConfigBatchOperationContext(request.auditTenantId(), request.operationId());
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public ConfigBatchOperationRecoveryResponse find(
      String auditTenantId, String requestIdentityHash, String operationKind) {
    mapper.setTenantContext(auditTenantId);
    ConfigBatchOperationRow operation = mapper.selectOperation(auditTenantId, requestIdentityHash);
    if (EmptyChecks.isNull(operation)
        || !Objects.equals(operationKind, operation.operationKind())) {
      throw BizException.of(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.detailKey());
    }
    List<String> targetIds = readTargets(operation.targetsJson());
    List<Target> targets = new ArrayList<>(targetIds.size());
    Status status = Status.COMPLETE;
    for (int ordinal = 0; ordinal < targetIds.size(); ordinal++) {
      String tenantId = targetIds.get(ordinal);
      mapper.setTenantContext(tenantId);
      String receiptJson =
          mapper.selectReceipt(auditTenantId, operation.operationId(), ordinal, tenantId);
      TenantInitResult receipt = EmptyChecks.isNull(receiptJson) ? null : readReceipt(receiptJson);
      Status targetStatus = Status.COMPLETE;
      if (EmptyChecks.isNull(receipt) || !tenantId.equals(receipt.tenantId())) {
        receipt = null;
        targetStatus = Status.UNKNOWN;
        status = Status.UNKNOWN;
      }
      targets.add(new Target(ordinal, tenantId, targetStatus, receipt));
    }
    return new ConfigBatchOperationRecoveryResponse(
        operation.operationId(),
        operation.operationKind(),
        readMetadata(operation.responseMetadataJson()),
        status,
        targets);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("配置批操作信息序列化失败", exception);
    }
  }

  private TenantInitResult readReceipt(String value) {
    try {
      return objectMapper.readValue(value, TenantInitResult.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("配置批操作回执解析失败", exception);
    }
  }

  private Map<String, Object> readMetadata(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("配置批操作响应元数据解析失败", exception);
    }
  }

  private List<String> readTargets(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("配置批操作目标解析失败", exception);
    }
  }
}
