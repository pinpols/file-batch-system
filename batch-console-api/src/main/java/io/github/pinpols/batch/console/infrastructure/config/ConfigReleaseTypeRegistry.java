package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** 配置发布类型注册表：集中维护 configType 的别名、payload 解析、身份校验和运行时缓存失效。 */
@Component
class ConfigReleaseTypeRegistry {

  private static final String TYPE_JOB = "JOB";
  private static final String TYPE_WORKFLOW = "WORKFLOW";
  private static final String TYPE_PIPELINE = "PIPELINE";
  private static final String TYPE_BATCH_WINDOW = "BATCH_WINDOW";
  private static final String TYPE_BUSINESS_CALENDAR = "BUSINESS_CALENDAR";
  private static final String TYPE_QUOTA_POLICY = "QUOTA_POLICY";

  private final Map<String, ConfigReleaseTypeHandler> handlersByCanonicalType;
  private final Map<String, String> aliases;

  ConfigReleaseTypeRegistry() {
    Map<String, ConfigReleaseTypeHandler> handlers = new LinkedHashMap<>();
    register(
        handlers,
        specHandler(
            TYPE_JOB,
            JobDefinitionSpec.class,
            JobDefinitionSpec::getJobCode,
            TenantConfigBatchInitRequest::setJobDefinitions));
    register(
        handlers,
        specHandler(
            TYPE_WORKFLOW,
            WorkflowDefinitionSpec.class,
            WorkflowDefinitionSpec::getWorkflowCode,
            TenantConfigBatchInitRequest::setWorkflowDefinitions));
    register(handlers, pipelineHandler());
    register(
        handlers,
        specHandler(
            "FILE_CHANNEL",
            FileChannelSpec.class,
            FileChannelSpec::getChannelCode,
            TenantConfigBatchInitRequest::setFileChannels));
    register(
        handlers,
        specHandler(
            "FILE_TEMPLATE",
            FileTemplateSpec.class,
            FileTemplateSpec::getTemplateCode,
            TenantConfigBatchInitRequest::setFileTemplates));
    register(
        handlers,
        specHandler(
            "RESOURCE_QUEUE",
            ResourceQueueSpec.class,
            ResourceQueueSpec::getQueueCode,
            TenantConfigBatchInitRequest::setResourceQueues));
    register(
        handlers,
        specHandler(
            TYPE_BATCH_WINDOW,
            BatchWindowSpec.class,
            BatchWindowSpec::getWindowCode,
            TenantConfigBatchInitRequest::setBatchWindows));
    register(
        handlers,
        specHandler(
            TYPE_BUSINESS_CALENDAR,
            BusinessCalendarSpec.class,
            BusinessCalendarSpec::getCalendarCode,
            TenantConfigBatchInitRequest::setBusinessCalendars));
    register(
        handlers,
        specHandler(
            TYPE_QUOTA_POLICY,
            TenantQuotaPolicySpec.class,
            TenantQuotaPolicySpec::getPolicyCode,
            TenantConfigBatchInitRequest::setQuotaPolicies));
    register(
        handlers,
        specHandler(
            "ALERT_ROUTING",
            AlertRoutingSpec.class,
            AlertRoutingSpec::getRouteCode,
            TenantConfigBatchInitRequest::setAlertRoutings));
    this.handlersByCanonicalType = Map.copyOf(handlers);
    this.aliases = Map.ofEntries(
        Map.entry("JOB_DEFINITION", TYPE_JOB),
        Map.entry("WORKFLOW_DEFINITION", TYPE_WORKFLOW),
        Map.entry("PIPELINE_DEFINITION", TYPE_PIPELINE),
        Map.entry("QUEUE", "RESOURCE_QUEUE"),
        Map.entry("WINDOW", TYPE_BATCH_WINDOW),
        Map.entry("CALENDAR", TYPE_BUSINESS_CALENDAR),
        Map.entry("QUOTA", TYPE_QUOTA_POLICY),
        Map.entry("ALERT", "ALERT_ROUTING"));
  }

  String canonicalType(String raw) {
    if (!Texts.hasText(raw)) {
      throw invalid("configType is required");
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    return aliases.getOrDefault(normalized, normalized);
  }

  void applyToRequest(
      String configType, String configKey, String payload, TenantConfigBatchInitRequest request) {
    String type = canonicalType(configType);
    ConfigReleaseTypeHandler handler = handlersByCanonicalType.get(type);
    if (EmptyChecks.isNull(handler)) {
      throw invalid("unsupported configType: " + configType);
    }
    try {
      handler.apply(configKey, payload, request);
    } catch (IllegalArgumentException exception) {
      throw invalid("invalid " + type + " payload: " + exception.getMessage());
    }
  }

  void evictRuntimeCache(
      ConsoleConfigCacheInvalidationService cacheService,
      String tenantId,
      String configType,
      String code) {
    String type = canonicalType(configType);
    switch (type) {
      case TYPE_JOB -> cacheService.evictJobDefinition(tenantId, code);
      case TYPE_WORKFLOW -> cacheService.evictWorkflowDefinition(tenantId, code);
      case TYPE_BATCH_WINDOW -> cacheService.evictBatchWindow(tenantId, code);
      case TYPE_BUSINESS_CALENDAR -> cacheService.evictBusinessCalendar(tenantId, code);
      case TYPE_QUOTA_POLICY -> cacheService.evictQuotaPolicies(tenantId);
      default -> {
        // 其他配置类型直接读取数据库，或只使用有界的本地队列缓存，无需主动失效。
      }
    }
  }

  private void register(
      Map<String, ConfigReleaseTypeHandler> handlers, ConfigReleaseTypeHandler handler) {
    handlers.put(handler.type(), handler);
  }

  private <T> ConfigReleaseTypeHandler specHandler(
      String type,
      Class<T> specClass,
      Function<T, String> codeExtractor,
      BiConsumer<TenantConfigBatchInitRequest, List<T>> requestSetter) {
    return new ConfigReleaseTypeHandler(type, (configKey, payload, request) -> {
      T spec = JsonUtils.fromJsonStrict(payload, specClass);
      requireKey(configKey, codeExtractor.apply(spec), type);
      requestSetter.accept(request, List.of(spec));
    });
  }

  private ConfigReleaseTypeHandler pipelineHandler() {
    return new ConfigReleaseTypeHandler(TYPE_PIPELINE, (configKey, payload, request) -> {
      PipelineDefinitionSpec spec = JsonUtils.fromJsonStrict(payload, PipelineDefinitionSpec.class);
      String jobCode = spec.getJobCode();
      String qualifiedKey =
          Texts.hasText(spec.getPipelineType()) ? jobCode + "." + spec.getPipelineType() : jobCode;
      if (!Texts.hasText(jobCode)
          || (!configKey.equals(jobCode) && !configKey.equals(qualifiedKey))) {
        throw invalid("PIPELINE payload identity must equal configKey: " + configKey);
      }
      request.setPipelineDefinitions(List.of(spec));
    });
  }

  private void requireKey(String expected, String actual, String type) {
    if (!Texts.hasText(actual) || !expected.equals(actual)) {
      throw invalid(type + " payload identity must equal configKey: " + expected);
    }
  }

  private BizException invalid(String detail) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", detail);
  }

  private record ConfigReleaseTypeHandler(String type, RequestApplier applier) {
    void apply(String configKey, String payload, TenantConfigBatchInitRequest request) {
      applier.apply(configKey, payload, request);
    }
  }

  @FunctionalInterface
  private interface RequestApplier {
    void apply(String configKey, String payload, TenantConfigBatchInitRequest request);
  }
}
