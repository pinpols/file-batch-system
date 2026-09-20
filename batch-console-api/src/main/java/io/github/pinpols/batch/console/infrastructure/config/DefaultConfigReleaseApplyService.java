package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 复用配置导入的严格事务处理器应用单条发布单。 */
@Service
@RequiredArgsConstructor
public class DefaultConfigReleaseApplyService implements ConfigReleaseApplyService {

  private final ConsoleTenantConfigInitApplicationService tenantConfigInitService;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

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
    evictRuntimeCache(
        release.getTenantId(), canonicalType(release.getConfigType()), release.getConfigKey());
  }

  private TenantConfigBatchInitRequest buildRequest(
      String tenantId, String configType, String configKey, String payload) {
    if (!Texts.hasText(configKey) || !Texts.hasText(payload)) {
      throw invalid("configKey and configPayloadJson are required");
    }
    String type = canonicalType(configType);
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of(tenantId));
    request.setMode(TenantConfigBatchInitRequest.InitMode.UPSERT);
    request.setStrict(true);

    try {
      switch (type) {
        case "JOB" -> {
          JobDefinitionSpec spec = parse(payload, JobDefinitionSpec.class);
          requireKey(configKey, spec.getJobCode(), type);
          request.setJobDefinitions(List.of(spec));
        }
        case "WORKFLOW" -> {
          WorkflowDefinitionSpec spec = parse(payload, WorkflowDefinitionSpec.class);
          requireKey(configKey, spec.getWorkflowCode(), type);
          request.setWorkflowDefinitions(List.of(spec));
        }
        case "PIPELINE" -> {
          PipelineDefinitionSpec spec = parse(payload, PipelineDefinitionSpec.class);
          requirePipelineKey(configKey, spec);
          request.setPipelineDefinitions(List.of(spec));
        }
        case "FILE_CHANNEL" -> {
          FileChannelSpec spec = parse(payload, FileChannelSpec.class);
          requireKey(configKey, spec.getChannelCode(), type);
          request.setFileChannels(List.of(spec));
        }
        case "FILE_TEMPLATE" -> {
          FileTemplateSpec spec = parse(payload, FileTemplateSpec.class);
          requireKey(configKey, spec.getTemplateCode(), type);
          request.setFileTemplates(List.of(spec));
        }
        case "RESOURCE_QUEUE" -> {
          ResourceQueueSpec spec = parse(payload, ResourceQueueSpec.class);
          requireKey(configKey, spec.getQueueCode(), type);
          request.setResourceQueues(List.of(spec));
        }
        case "BATCH_WINDOW" -> {
          BatchWindowSpec spec = parse(payload, BatchWindowSpec.class);
          requireKey(configKey, spec.getWindowCode(), type);
          request.setBatchWindows(List.of(spec));
        }
        case "BUSINESS_CALENDAR" -> {
          BusinessCalendarSpec spec = parse(payload, BusinessCalendarSpec.class);
          requireKey(configKey, spec.getCalendarCode(), type);
          request.setBusinessCalendars(List.of(spec));
        }
        case "QUOTA_POLICY" -> {
          TenantQuotaPolicySpec spec = parse(payload, TenantQuotaPolicySpec.class);
          requireKey(configKey, spec.getPolicyCode(), type);
          request.setQuotaPolicies(List.of(spec));
        }
        case "ALERT_ROUTING" -> {
          AlertRoutingSpec spec = parse(payload, AlertRoutingSpec.class);
          requireKey(configKey, spec.getRouteCode(), type);
          request.setAlertRoutings(List.of(spec));
        }
        default -> throw invalid("unsupported configType: " + configType);
      }
    } catch (IllegalArgumentException exception) {
      throw invalid("invalid " + type + " payload: " + exception.getMessage());
    }
    return request;
  }

  private <T> T parse(String payload, Class<T> type) {
    return JsonUtils.fromJsonStrict(payload, type);
  }

  private void requireKey(String expected, String actual, String type) {
    if (!Texts.hasText(actual) || !expected.equals(actual)) {
      throw invalid(type + " payload identity must equal configKey: " + expected);
    }
  }

  private void requirePipelineKey(String configKey, PipelineDefinitionSpec spec) {
    String jobCode = spec.getJobCode();
    String qualifiedKey =
        Texts.hasText(spec.getPipelineType()) ? jobCode + "." + spec.getPipelineType() : jobCode;
    if (!Texts.hasText(jobCode)
        || (!configKey.equals(jobCode) && !configKey.equals(qualifiedKey))) {
      throw invalid("PIPELINE payload identity must equal configKey: " + configKey);
    }
  }

  @Override
  public String canonicalType(String raw) {
    if (!Texts.hasText(raw)) {
      throw invalid("configType is required");
    }
    return switch (raw.trim().toUpperCase(Locale.ROOT)) {
      case "JOB", "JOB_DEFINITION" -> "JOB";
      case "WORKFLOW", "WORKFLOW_DEFINITION" -> "WORKFLOW";
      case "PIPELINE", "PIPELINE_DEFINITION" -> "PIPELINE";
      case "FILE_CHANNEL" -> "FILE_CHANNEL";
      case "FILE_TEMPLATE" -> "FILE_TEMPLATE";
      case "QUEUE", "RESOURCE_QUEUE" -> "RESOURCE_QUEUE";
      case "WINDOW", "BATCH_WINDOW" -> "BATCH_WINDOW";
      case "CALENDAR", "BUSINESS_CALENDAR" -> "BUSINESS_CALENDAR";
      case "QUOTA", "QUOTA_POLICY" -> "QUOTA_POLICY";
      case "ALERT", "ALERT_ROUTING" -> "ALERT_ROUTING";
      default -> raw.trim().toUpperCase(Locale.ROOT);
    };
  }

  private void evictRuntimeCache(String tenantId, String type, String code) {
    switch (type) {
      case "JOB" -> cacheInvalidationService.evictJobDefinition(tenantId, code);
      case "WORKFLOW" -> cacheInvalidationService.evictWorkflowDefinition(tenantId, code);
      case "BATCH_WINDOW" -> cacheInvalidationService.evictBatchWindow(tenantId, code);
      case "BUSINESS_CALENDAR" -> cacheInvalidationService.evictBusinessCalendar(tenantId, code);
      case "QUOTA_POLICY" -> cacheInvalidationService.evictQuotaPolicies(tenantId);
      default -> {
        // 其他配置类型直接读取数据库，或只使用有界的本地队列缓存，无需主动失效。
      }
    }
  }

  private BizException invalid(String detail) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", detail);
  }
}
