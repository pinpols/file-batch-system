package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 复用配置导入的严格事务处理器应用单条发布单。 */
@Service
@RequiredArgsConstructor
public class DefaultConfigReleaseApplyService implements ConfigReleaseApplyService {

  private final ConsoleTenantConfigInitApplicationService tenantConfigInitService;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;
  private final ConfigReleaseTypeRegistry typeRegistry;

  @Override
  public void validate(String configType, String configKey, String configPayloadJson) {
    buildRequest("validation", configType, configKey, configPayloadJson);
  }

  @Override
  public void apply(ConfigReleaseEntity release, String operatorId, String operationId) {
    if (EmptyChecks.isNull(release) || !Texts.hasText(release.getTenantId())) {
      throw invalid("release tenant is required");
    }
    TenantConfigBatchInitRequest request = buildRequest(
        release.getTenantId(),
        release.getConfigType(),
        release.getConfigKey(),
        release.getConfigPayload());
    TenantConfigBatchInitResponse result =
        tenantConfigInitService.batchInit(request, operatorId, operationId);
    if (result.failureTenants() != 0 || result.successTenants() != 1) {
      String detail = EmptyChecks.isEmpty(result.results())
          ? "configuration apply returned no tenant result"
          : result.results().getFirst().errorMessage();
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.config.release_apply_failed",
          Texts.hasText(detail) ? detail : "configuration apply failed");
    }
    typeRegistry.evictRuntimeCache(
        cacheInvalidationService,
        release.getTenantId(),
        release.getConfigType(),
        release.getConfigKey());
  }

  private TenantConfigBatchInitRequest buildRequest(
      String tenantId, String configType, String configKey, String payload) {
    if (!Texts.hasText(configKey) || !Texts.hasText(payload)) {
      throw invalid("configKey and configPayloadJson are required");
    }
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenantId));
    request.setMode(TenantConfigBatchInitRequest.InitMode.UPSERT);
    request.setStrict(true);
    typeRegistry.applyToRequest(configType, configKey, payload, request);
    return request;
  }

  @Override
  public String canonicalType(String raw) {
    return typeRegistry.canonicalType(raw);
  }

  private BizException invalid(String detail) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), detail);
  }
}
