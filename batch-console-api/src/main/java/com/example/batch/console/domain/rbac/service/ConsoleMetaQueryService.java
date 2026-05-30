package com.example.batch.console.domain.rbac.service;

import com.example.batch.common.enums.AiPromptCategory;
import com.example.batch.common.enums.AiPromptDecision;
import com.example.batch.common.enums.AlertSeverity;
import com.example.batch.common.enums.AlertStatus;
import com.example.batch.common.enums.ApprovalCommandStatus;
import com.example.batch.common.enums.ApprovalType;
import com.example.batch.common.enums.BatchType;
import com.example.batch.common.enums.BatchWindowEndStrategy;
import com.example.batch.common.enums.CalendarDayType;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.common.enums.DeadLetterErrorClass;
import com.example.batch.common.enums.DeadLetterReplayStatus;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.ErrorSinkType;
import com.example.batch.common.enums.ExecutionMode;
import com.example.batch.common.enums.FailureClass;
import com.example.batch.common.enums.FileAuditOperationType;
import com.example.batch.common.enums.FileChannelAuthType;
import com.example.batch.common.enums.FileChannelType;
import com.example.batch.common.enums.FileChecksumType;
import com.example.batch.common.enums.FileCompressType;
import com.example.batch.common.enums.FileDispatchRunStatus;
import com.example.batch.common.enums.FileDispatchStatus;
import com.example.batch.common.enums.FileEncryptType;
import com.example.batch.common.enums.FileReceiptPolicy;
import com.example.batch.common.enums.FileReceiptStatus;
import com.example.batch.common.enums.FileStatus;
import com.example.batch.common.enums.FileTemplateFormat;
import com.example.batch.common.enums.FileTemplateType;
import com.example.batch.common.enums.HolidayRollRule;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.LogType;
import com.example.batch.common.enums.NotificationChannelType;
import com.example.batch.common.enums.OperationResult;
import com.example.batch.common.enums.OutOfWindowAction;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.PipelineRunStatus;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.PriorityLevel;
import com.example.batch.common.enums.QueuePriorityPolicy;
import com.example.batch.common.enums.QuotaExceededStrategy;
import com.example.batch.common.enums.ResourceQueueType;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.ScheduleType;
import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.common.enums.SensorTimeoutAction;
import com.example.batch.common.enums.SensorType;
import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.enums.SkipAction;
import com.example.batch.common.enums.SkipThresholdMode;
import com.example.batch.common.enums.StepInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TenantConfigInitAction;
import com.example.batch.common.enums.TenantStatus;
import com.example.batch.common.enums.TriggerMode;
import com.example.batch.common.enums.TriggerResourceType;
import com.example.batch.common.enums.TriggerStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WebhookDeliveryStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.enums.WorkflowDefinitionStatus;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowJoinMode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.console.domain.observability.view.meta.SimpleOptionView;
import com.example.batch.console.domain.rbac.mapper.ConsoleMetaQueryMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.domain.rbac.web.response.ConsoleMetaEnumItem;
import com.example.batch.console.domain.rbac.web.response.ConsoleMetaOption;
import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ConsoleMetaQueryService {

  private final ConsoleMetaQueryMapper repository;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleQueryCacheService cacheService;
  private final MessageSource messageSource;
  private final com.example.batch.console.domain.job.mapper.StepRegistryQueryMapper
      stepRegistryQueryMapper;

  public ConsoleMetaQueryService(
      ConsoleMetaQueryMapper repository,
      ConsoleTenantGuard tenantGuard,
      ConsoleQueryCacheService cacheService,
      MessageSource messageSource,
      com.example.batch.console.domain.job.mapper.StepRegistryQueryMapper stepRegistryQueryMapper) {
    this.repository = repository;
    this.tenantGuard = tenantGuard;
    this.cacheService = cacheService;
    this.messageSource = messageSource;
    this.stepRegistryQueryMapper = stepRegistryQueryMapper;
  }

  /**
   * Pipeline 固定 9 stages 按 jobType 分组（与 {@code ConfigPackageExcelValidator.STAGES_BY_TYPE} 保持一致；该
   * Map 是导入校验白名单的事实源）。
   */
  private static final Map<String, List<String>> PIPELINE_STAGES =
      Map.of(
          "IMPORT", List.of("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK"),
          "EXPORT", List.of("PREPARE", "GENERATE", "STORE", "REGISTER", "COMPLETE"),
          "PROCESS", List.of("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK"),
          "DISPATCH", List.of("PREPARE", "DISPATCH", "ACK", "RETRY", "COMPENSATE", "COMPLETE"));

  public Map<String, List<String>> pipelineStages() {
    return PIPELINE_STAGES;
  }

  public List<String> stepImpls(String module) {
    if (module == null || module.isBlank()) {
      return stepRegistryQueryMapper.selectAllImplCodes();
    }
    return stepRegistryQueryMapper.selectImplCodesByModule(module.toUpperCase(Locale.ROOT));
  }

  @SuppressWarnings("unchecked")
  public Map<String, List<ConsoleMetaEnumItem>> enums() {
    Locale locale = LocaleContextHolder.getLocale();
    return cacheService.getOrLoad(
        "meta:enums:" + locale.toLanguageTag(),
        ConsoleQueryCacheService.META_ENUM_TTL,
        Map.class,
        () -> buildEnums(locale));
  }

  private Map<String, List<ConsoleMetaEnumItem>> buildEnums(Locale locale) {
    Map<String, List<ConsoleMetaEnumItem>> result = new LinkedHashMap<>();
    for (EnumReg<?> reg : REGISTRATIONS) {
      result.put(reg.key(), reg.toItems(messageSource, locale));
    }
    return result;
  }

  /**
   * 按当前 Locale 解析枚举 label。messageSource 命中 {@link DictEnum#messageKey()} 返回翻译值,缺失回退到 {@link
   * DictEnum#label()} (中文硬编码)。
   */
  private static String localizedLabel(DictEnum item, MessageSource messageSource, Locale locale) {
    String key = item.messageKey();
    String fallback = item.label();
    if (key == null || messageSource == null) {
      return fallback;
    }
    return messageSource.getMessage(key, null, fallback, locale);
  }

  @SuppressWarnings("unchecked")
  public List<ConsoleMetaOption> queues(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "meta:" + resolved + ":queues",
        ConsoleQueryCacheService.META_OPTION_TTL,
        List.class,
        () -> toOptions(repository.queueOptions(resolved)));
  }

  @SuppressWarnings("unchecked")
  public List<ConsoleMetaOption> calendars(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "meta:" + resolved + ":calendars",
        ConsoleQueryCacheService.META_OPTION_TTL,
        List.class,
        () -> toOptions(repository.calendarOptions(resolved)));
  }

  @SuppressWarnings("unchecked")
  public List<ConsoleMetaOption> windows(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "meta:" + resolved + ":windows",
        ConsoleQueryCacheService.META_OPTION_TTL,
        List.class,
        () -> toOptions(repository.windowOptions(resolved)));
  }

  @SuppressWarnings("unchecked")
  public List<ConsoleMetaOption> workerGroups(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "meta:" + resolved + ":workerGroups",
        ConsoleQueryCacheService.META_OPTION_TTL,
        List.class,
        () -> toOptions(repository.workerGroupOptions(resolved)));
  }

  @SuppressWarnings("unchecked")
  public List<ConsoleMetaOption> bizTypes(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "meta:" + resolved + ":bizTypes",
        ConsoleQueryCacheService.META_OPTION_TTL,
        List.class,
        () -> toOptions(repository.bizTypeOptions(resolved)));
  }

  private List<ConsoleMetaOption> toOptions(List<SimpleOptionView> rows) {
    return rows.stream().map(row -> new ConsoleMetaOption(row.code(), row.label())).toList();
  }

  /** 供守护测试读取当前已注册的枚举类集合（校验新增枚举必须注册或显式排除）。 */
  static Set<Class<?>> registeredEnumClasses() {
    return REGISTRATIONS.stream().map(EnumReg::enumClass).collect(Collectors.toUnmodifiableSet());
  }

  private record EnumReg<E extends Enum<E> & DictEnum>(String key, Class<E> enumClass) {

    List<ConsoleMetaEnumItem> toItems(MessageSource messageSource, Locale locale) {
      return Arrays.stream(enumClass.getEnumConstants())
          .map(e -> new ConsoleMetaEnumItem(e.code(), localizedLabel(e, messageSource, locale)))
          .toList();
    }
  }

  private static final List<EnumReg<?>> REGISTRATIONS;

  static {
    List<EnumReg<?>> list = new ArrayList<>(64);
    list.add(new EnumReg<>("triggerType", TriggerType.class));
    list.add(new EnumReg<>("scheduleType", ScheduleType.class));
    list.add(new EnumReg<>("triggerMode", TriggerMode.class));
    list.add(new EnumReg<>("catchUpPolicy", CatchUpPolicyType.class));
    list.add(new EnumReg<>("jobType", JobType.class));
    list.add(new EnumReg<>("batchType", BatchType.class));
    list.add(new EnumReg<>("shardStrategy", ShardStrategy.class));
    list.add(new EnumReg<>("executionMode", ExecutionMode.class));
    list.add(new EnumReg<>("retryPolicy", RetryPolicyType.class));
    list.add(new EnumReg<>("taskStatus", TaskStatus.class));
    list.add(new EnumReg<>("partitionStatus", PartitionStatus.class));
    list.add(new EnumReg<>("instanceStatus", JobInstanceStatus.class));
    list.add(new EnumReg<>("workflowType", WorkflowType.class));
    list.add(new EnumReg<>("workflowNodeType", WorkflowNodeType.class));
    // ADR-028 Sensor WAIT 节点字典
    list.add(new EnumReg<>("sensorType", SensorType.class));
    list.add(new EnumReg<>("sensorTimeoutAction", SensorTimeoutAction.class));
    list.add(new EnumReg<>("edgeType", WorkflowEdgeType.class));
    list.add(new EnumReg<>("workflowRunStatus", WorkflowRunStatus.class));
    list.add(new EnumReg<>("pipelineType", PipelineType.class));
    list.add(new EnumReg<>("channelType", FileChannelType.class));
    list.add(new EnumReg<>("authType", FileChannelAuthType.class));
    list.add(new EnumReg<>("receiptPolicy", FileReceiptPolicy.class));
    list.add(new EnumReg<>("fileTemplateType", FileTemplateType.class));
    list.add(new EnumReg<>("fileTemplateFormat", FileTemplateFormat.class));
    list.add(new EnumReg<>("endStrategy", BatchWindowEndStrategy.class));
    list.add(new EnumReg<>("outOfWindowAction", OutOfWindowAction.class));
    list.add(new EnumReg<>("holidayStrategy", HolidayRollRule.class));
    list.add(new EnumReg<>("dayType", CalendarDayType.class));
    list.add(new EnumReg<>("queueType", ResourceQueueType.class));
    list.add(new EnumReg<>("priorityPolicy", QueuePriorityPolicy.class));
    list.add(new EnumReg<>("severity", AlertSeverity.class));
    list.add(new EnumReg<>("alertStatus", AlertStatus.class));
    list.add(new EnumReg<>("approvalStatus", ApprovalCommandStatus.class));
    list.add(new EnumReg<>("approvalType", ApprovalType.class));
    list.add(new EnumReg<>("configStatus", ConfigLifecycleStatus.class));
    list.add(new EnumReg<>("workerStatus", WorkerRegistryStatus.class));
    list.add(new EnumReg<>("outboxPublishStatus", OutboxPublishStatus.class));
    list.add(new EnumReg<>("aiPromptCategory", AiPromptCategory.class));
    list.add(new EnumReg<>("operationType", FileAuditOperationType.class));
    list.add(new EnumReg<>("operationResult", OperationResult.class));
    list.add(new EnumReg<>("fileStatus", FileStatus.class));
    list.add(new EnumReg<>("priorityLevel", PriorityLevel.class));
    list.add(new EnumReg<>("aiPromptDecision", AiPromptDecision.class));
    list.add(new EnumReg<>("checksumType", FileChecksumType.class));
    list.add(new EnumReg<>("workflowJoinMode", WorkflowJoinMode.class));
    list.add(new EnumReg<>("fileDispatchRunStatus", FileDispatchRunStatus.class));
    list.add(new EnumReg<>("fileDispatchStatus", FileDispatchStatus.class));
    list.add(new EnumReg<>("fileReceiptStatus", FileReceiptStatus.class));
    list.add(new EnumReg<>("pipelineRunStatus", PipelineRunStatus.class));
    list.add(new EnumReg<>("compensationStatus", CompensationCommandStatus.class));
    list.add(new EnumReg<>("retryScheduleStatus", RetryScheduleStatus.class));
    list.add(new EnumReg<>("encryptType", FileEncryptType.class));
    list.add(new EnumReg<>("compressType", FileCompressType.class));
    list.add(new EnumReg<>("errorSinkType", ErrorSinkType.class));
    list.add(new EnumReg<>("priorityBand", SchedulingPriorityBand.class));
    list.add(new EnumReg<>("stepInstanceStatus", StepInstanceStatus.class));
    list.add(new EnumReg<>("runMode", RunMode.class));
    list.add(new EnumReg<>("skipAction", SkipAction.class));
    list.add(new EnumReg<>("workflowNodeRunStatus", WorkflowNodeRunStatus.class));
    list.add(new EnumReg<>("deadLetterReplayStatus", DeadLetterReplayStatus.class));
    list.add(new EnumReg<>("deadLetterErrorClass", DeadLetterErrorClass.class));
    list.add(new EnumReg<>("quotaExceededStrategy", QuotaExceededStrategy.class));
    list.add(new EnumReg<>("skipThresholdMode", SkipThresholdMode.class));
    // 前端请求的 8 个补充枚举
    list.add(new EnumReg<>("triggerStatus", TriggerStatus.class));
    list.add(new EnumReg<>("deliveryStatus", WebhookDeliveryStatus.class));
    list.add(new EnumReg<>("notificationChannelType", NotificationChannelType.class));
    list.add(new EnumReg<>("tenantStatus", TenantStatus.class));
    list.add(new EnumReg<>("logType", LogType.class));
    list.add(new EnumReg<>("workflowDefinitionStatus", WorkflowDefinitionStatus.class));
    list.add(new EnumReg<>("tenantConfigInitAction", TenantConfigInitAction.class));
    list.add(new EnumReg<>("triggerResourceType", TriggerResourceType.class));
    list.add(new EnumReg<>("failureClass", FailureClass.class));
    REGISTRATIONS = List.copyOf(list);
  }
}
