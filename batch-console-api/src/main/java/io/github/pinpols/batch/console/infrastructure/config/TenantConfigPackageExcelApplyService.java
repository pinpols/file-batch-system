package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.workflow.application.command.WorkflowVersionSnapshotCommand;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.normalize;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.normalizeEnum;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.parseBoolean;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.parseInteger;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.safeOp;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.*;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.file.param.FileTemplateConfigUpsertParam;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpsertParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpsertParam;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.param.ResourceQueueUpsertParam;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.infrastructure.excel.BatchWindowExcelRowParser;
import io.github.pinpols.batch.console.infrastructure.excel.BatchWindowExcelRowParser.WindowRow;
import io.github.pinpols.batch.console.infrastructure.excel.BusinessCalendarExcelRowParser;
import io.github.pinpols.batch.console.infrastructure.excel.BusinessCalendarExcelRowParser.CalendarRow;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.FileTemplateExcelRowParser;
import io.github.pinpols.batch.console.infrastructure.excel.FileTemplateExcelRowParser.TemplateRow;
import io.github.pinpols.batch.console.infrastructure.excel.ResourceQueueExcelRowParser;
import io.github.pinpols.batch.console.infrastructure.excel.ResourceQueueExcelRowParser.QueueRow;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 租户配置包 Excel apply 阶段的写库逻辑（从 {@link DefaultTenantConfigPackageExcelService}
 * 拆出）。按 resourceQueue → businessCalendar → batchWindow → fileTemplate → channel → job →
 * workflow+node+edge 顺序 upsert；调用方负责事务边界与 totalInvalid 闸门。
 */
@Service
@RequiredArgsConstructor
public class TenantConfigPackageExcelApplyService {

  private static final String KEY_ID = "id";

  private final ResourceQueueMapper resourceQueueMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final CalendarHolidayMapper calendarHolidayMapper;
  private final BatchWindowMapper batchWindowMapper;
  private final FileChannelConfigMapper fileChannelConfigMapper;
  private final FileTemplateConfigMapper fileTemplateConfigMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner;
  private final JobMonitoringDefaultsProperties monitoringDefaults;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final WorkflowDefinitionWriteSupport workflowWriteSupport;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  public record ApplyContext(String tenantId, String operatorId, String reason, String traceId) {}

  public record ApplyCounts(
      int resourceQueueInserted,
      int resourceQueueUpdated,
      int businessCalendarInserted,
      int businessCalendarUpdated,
      int batchWindowInserted,
      int batchWindowUpdated,
      int fileTemplateInserted,
      int fileTemplateUpdated,
      int channelInserted,
      int channelUpdated,
      int jobInserted,
      int jobUpdated,
      int workflowInserted,
      int workflowUpdated) {}

  public ApplyCounts applyAll(PackageValidationResult result, ApplyContext ctx) {
    ApplyStats resourceQueueStats = applyResourceQueues(result.validResourceQueues(), ctx);
    ApplyStats businessCalendarStats = applyBusinessCalendars(result.validBusinessCalendars(), ctx);
    ApplyStats batchWindowStats = applyBatchWindows(result.validBatchWindows(), ctx);
    ApplyStats fileTemplateStats = applyFileTemplates(result.validFileTemplates(), ctx);
    ApplyStats channelStats = applyChannels(result.validChannels(), ctx);
    ApplyStats jobStats = applyJobs(result.validJobs(), ctx);
    applyJobMonitoringPolicies(result.validJobMonitoringPolicies(), ctx);
    ApplyStats wfStats =
        applyWorkflows(result.validWfDefs(), result.validWfNodes(), result.validWfEdges(), ctx);
    invalidateRuntimeCaches(
        ctx.tenantId(),
        resourceQueueStats,
        businessCalendarStats,
        batchWindowStats,
        jobStats,
        wfStats);
    return new ApplyCounts(
        resourceQueueStats.inserted(),
        resourceQueueStats.updated(),
        businessCalendarStats.inserted(),
        businessCalendarStats.updated(),
        batchWindowStats.inserted(),
        batchWindowStats.updated(),
        fileTemplateStats.inserted(),
        fileTemplateStats.updated(),
        channelStats.inserted(),
        channelStats.updated(),
        jobStats.inserted(),
        jobStats.updated(),
        wfStats.inserted(),
        wfStats.updated());
  }

  private void invalidateRuntimeCaches(
      String tenantId,
      ApplyStats resourceQueueStats,
      ApplyStats calendarStats,
      ApplyStats windowStats,
      ApplyStats jobStats,
      ApplyStats workflowStats) {
    if (hasChanges(resourceQueueStats)) {
      cacheInvalidationService.evictMetaOptions(tenantId);
    }
    if (hasChanges(calendarStats)) {
      cacheInvalidationService.evictAllBusinessCalendars(tenantId);
      cacheInvalidationService.evictMetaOptions(tenantId);
    }
    if (hasChanges(windowStats)) {
      cacheInvalidationService.evictAllBatchWindows(tenantId);
      cacheInvalidationService.evictMetaOptions(tenantId);
    }
    if (hasChanges(jobStats)) {
      cacheInvalidationService.evictAllJobDefinitions(tenantId);
    }
    if (jobStats.inserted() > 0 || hasChanges(workflowStats)) {
      cacheInvalidationService.evictAllWorkflowDefinitions(tenantId);
    }
  }

  private static boolean hasChanges(ApplyStats stats) {
    return stats.inserted() > 0 || stats.updated() > 0;
  }

  private ApplyStats applyResourceQueues(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      QueueRow queue = ResourceQueueExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (EmptyChecks.isNotEmpty(issues)) {
        throw invalidParsedRow(RESOURCE_QUEUE_SHEET, issues);
      }
      Map<String, Object> existing =
          resourceQueueMapper.selectByUniqueKey(ctx.tenantId(), queue.queueCode());
      ResourceQueueUpsertParam queueParam =
          ResourceQueueExcelRowParser.toUpsertParam(queue, ctx.operatorId());
      resourceQueueMapper.upsertResourceQueue(queueParam);
      if (EmptyChecks.isEmpty(existing)) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyBusinessCalendars(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      CalendarRow calendar =
          BusinessCalendarExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (EmptyChecks.isNotEmpty(issues)) {
        throw invalidParsedRow(BUSINESS_CALENDAR_SHEET, issues);
      }
      Map<String, Object> existing = businessCalendarMapper.selectByTenantAndCalendarCode(
          ctx.tenantId(), calendar.calendarCode());
      String calendarOperatorId = safeOp(ctx.operatorId());
      BusinessCalendarUpsertParam calendarParam =
          BusinessCalendarExcelRowParser.toUpsertParam(calendar, calendarOperatorId);
      if (EmptyChecks.isNotNull(existing)) {
        preserveCalendarOptionalValues(row, existing, calendarParam);
      }
      businessCalendarMapper.upsertBusinessCalendar(calendarParam);
      applyCalendarHolidays(ctx.tenantId(), calendar);
      if (EmptyChecks.isEmpty(existing)) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyBatchWindows(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      WindowRow window = BatchWindowExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (EmptyChecks.isNotEmpty(issues)) {
        throw invalidParsedRow(BATCH_WINDOW_SHEET, issues);
      }
      Map<String, Object> existing =
          batchWindowMapper.selectByUniqueKey(ctx.tenantId(), window.windowCode());
      BatchWindowUpsertParam windowParam = BatchWindowExcelRowParser.toUpsertParam(window);
      if (EmptyChecks.isNotNull(existing)) {
        preserveWindowOptionalValues(row, existing, windowParam);
      }
      batchWindowMapper.upsertBatchWindow(windowParam);
      if (EmptyChecks.isEmpty(existing)) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private void applyCalendarHolidays(String tenantId, CalendarRow calendar) {
    Map<String, Object> saved =
        businessCalendarMapper.selectByTenantAndCalendarCode(tenantId, calendar.calendarCode());
    if (saved == null || saved.get(KEY_ID) == null) {
      return;
    }
    Long calendarId = ((Number) saved.get(KEY_ID)).longValue();
    if (EmptyChecks.isEmpty(calendar.holidays())) {
      return;
    }
    List<Map<String, Object>> params = new ArrayList<>();
    for (LocalDate holiday : calendar.holidays()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("tenantId", tenantId); // NOT NULL 列必填，与 V*__tenant_isolation 加固一致
      item.put("calendarId", calendarId);
      item.put("bizDate", holiday);
      item.put("dayType", "HOLIDAY");
      item.put("holidayName", null);
      item.put(COL_DESCRIPTION, calendar.description());
      params.add(item);
    }
    calendarHolidayMapper.upsertBatch(params);
  }

  private static void preserveCalendarOptionalValues(
      Map<String, String> row, Map<String, Object> existing, BusinessCalendarUpsertParam target) {
    if (!Texts.hasText(row.get(ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAY_ROLL_RULE))) {
      target.setHolidayRollRule((String) existing.get("holidayRollRule"));
    }
    if (!Texts.hasText(row.get(ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_POLICY))) {
      target.setCatchUpPolicy((String) existing.get("catchUpPolicy"));
    }
    if (!Texts.hasText(row.get(ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_MAX_DAYS))) {
      Object existingCatchUpMaxDays = existing.get("catchUpMaxDays");
      Integer catchUpMaxDays = numberValue(existingCatchUpMaxDays);
      target.setCatchUpMaxDays(catchUpMaxDays);
    }
    if (!Texts.hasText(row.get(COL_ENABLED))) {
      target.setEnabled((Boolean) existing.get(COL_ENABLED));
    }
    if (!Texts.hasText(row.get(COL_DESCRIPTION))) {
      target.setDescription((String) existing.get(COL_DESCRIPTION));
    }
  }

  private static void preserveWindowOptionalValues(
      Map<String, String> row, Map<String, Object> existing, BatchWindowUpsertParam target) {
    if (!Texts.hasText(row.get(ConfigPackageExcelSchema.BatchWindow.COL_END_STRATEGY))) {
      target.setEndStrategy((String) existing.get("end_strategy"));
    }
    if (!Texts.hasText(row.get(ConfigPackageExcelSchema.BatchWindow.COL_OUT_OF_WINDOW_ACTION))) {
      target.setOutOfWindowAction((String) existing.get("out_of_window_action"));
    }
    if (!Texts.hasText(row.get(ConfigPackageExcelSchema.BatchWindow.COL_ALLOW_CROSS_DAY))) {
      target.setAllowCrossDay((Boolean) existing.get("allow_cross_day"));
    }
    if (!Texts.hasText(row.get(COL_ENABLED))) {
      target.setEnabled((Boolean) existing.get(COL_ENABLED));
    }
    if (!Texts.hasText(row.get(COL_DESCRIPTION))) {
      target.setDescription((String) existing.get(COL_DESCRIPTION));
    }
  }

  private static Integer numberValue(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static BizException invalidParsedRow(String sheetName, List<String> issues) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT,
        ResultCode.INVALID_ARGUMENT.detailKey(),
        "invalid " + sheetName + " row: " + issues);
  }

  private ApplyStats applyJobs(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : rows) {
      boolean insertedJob = applyJob(row, ctx);
      if (insertedJob) {
        inserted++;
      } else {
        updated++;
      }
    }
    // 逐项失败使用保存点；租户阶段失败时，必须丢弃该租户的全部写入。
    // 等所有 Job 行创建完成后再替换依赖，这样包内向前引用
    for (Map<String, String> row : rows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      // 不会受工作簿行顺序影响，且满足规范化关系的外键约束。
      // 旧工作簿只包含标量列。更新时保留空值表示不修改的旧语义；
      boolean dependencyColumnsAreEmpty = !Texts.hasText(row.get(COL_DEPENDS_ON_JOB_CODES))
          && !Texts.hasText(row.get(COL_DEPENDS_ON_JOB_CODE))
          && !Texts.hasText(row.get(ConfigPackageExcelSchema.COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS));
      if (dependencyColumnsAreEmpty) {
        continue;
      }
      List<JobDependencyRuleRequest> dependencies = parseJobDependencyRules(row);
      jobDefinitionMapper.deleteDependencies(ctx.tenantId(), jobCode);
      if (EmptyChecks.isNotEmpty(dependencies)) {
        jobDefinitionMapper.insertDependencyRules(ctx.tenantId(), jobCode, dependencies);
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private List<String> parseJobDependencies(Map<String, String> row) {
    if (Texts.hasText(row.get(COL_DEPENDS_ON_JOB_CODES))) {
      return splitDependencies(row.get(COL_DEPENDS_ON_JOB_CODES));
    }
    return splitDependencies(row.get(COL_DEPENDS_ON_JOB_CODE));
  }

  private List<JobDependencyRuleRequest> parseJobDependencyRules(Map<String, String> row) {
    List<String> codes = parseJobDependencies(row);
    String configuredAlignments =
        row.get(ConfigPackageExcelSchema.COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS);
    List<String> alignments = Texts.hasText(configuredAlignments)
        ? Arrays.stream(configuredAlignments.split(","))
            .map(String::trim)
            .filter(Texts::hasText)
            .toList()
        : List.of();
    return IntStream.range(0, codes.size())
        .mapToObj(index -> {
          String dependencyCode = codes.get(index);
          String businessDateAlignment =
              index < alignments.size() ? alignments.get(index) : "SAME_DAY";
          return new JobDependencyRuleRequest(dependencyCode, businessDateAlignment);
        })
        .toList();
  }

  private List<String> splitDependencies(String configured) {
    if (!Texts.hasText(configured)) {
      return List.of();
    }
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(Texts::hasText)
        .distinct()
        .toList();
  }

  private boolean applyJob(Map<String, String> row, ApplyContext ctx) {
    String jobCode = normalize(row.get(COL_JOB_CODE));
    JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode);
    if (EmptyChecks.isNull(existing)) {
      insertJob(row, ctx, jobCode);
      return true;
    }
    updateJob(row, ctx, jobCode, existing);
    return false;
  }

  private void insertJob(Map<String, String> row, ApplyContext ctx, String jobCode) {
    JobDefinitionEntity entity = newJobEntity(row, ctx, jobCode);
    jobDefinitionMapper.insert(entity);
    singleJobWorkflowProvisioner.provision(entity);
    JobMonitoringPolicyUpsertParam.JobMonitoringPolicyUpsertParamBuilder monitoringPolicyBuilder = JobMonitoringPolicyUpsertParam.builder()
        .tenantId(ctx.tenantId())
        .jobDefinitionId(entity.getId())
        .softRuntimeSeconds(monitoringDefaults.getSoftRuntimeSeconds())
        .softRuntimeSeverity("WARN");
    boolean startGraceMonitoringEnabled = isStartGraceMonitored(entity);
    int startGraceSeconds =
        startGraceMonitoringEnabled ? monitoringDefaults.getStartGraceSeconds() : 0;
    monitoringPolicyBuilder.startGraceSeconds(startGraceSeconds);
    monitoringPolicyBuilder.startGraceSeverity("WARN");
    monitoringPolicyBuilder.completionDeadlineLocalTime(null);
    monitoringPolicyBuilder.completionDeadlineDayOffset(0);
    monitoringPolicyBuilder.dependencyCompletionWindowSeconds(0);
    monitoringPolicyBuilder.completionDeadlineSeverity("WARN");
    String updatedBy = safeOp(ctx.operatorId());
    monitoringPolicyBuilder.updatedBy(updatedBy);
    JobMonitoringPolicyUpsertParam monitoringPolicy = monitoringPolicyBuilder.build();
    jobDefinitionMapper.upsertJobMonitoringPolicy(monitoringPolicy);
  }

  private JobDefinitionEntity newJobEntity(
      Map<String, String> row, ApplyContext ctx, String jobCode) {
    JobDefinitionEntity entity = new JobDefinitionEntity();
    entity.setTenantId(ctx.tenantId());
    entity.setJobCode(jobCode);
    entity.setJobName(normalize(row.get(COL_JOB_NAME)));
    entity.setJobType(normalizeEnum(row.get(COL_JOB_TYPE)));
    entity.setBizType(normalize(row.get(COL_BIZ_TYPE)));
    entity.setQueueCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_QUEUE_CODE)));
    entity.setWorkerGroup(CodeNormalizer.toUpperOrNull(row.get(COL_WORKER_GROUP)));
    entity.setScheduleType(normalizeEnum(row.get(COL_SCHEDULE_TYPE)));
    entity.setScheduleExpr(normalize(row.get(COL_SCHEDULE_EXPR)));
    List<String> dependencies = parseJobDependencies(row);
    entity.setDependsOnJobCode(EmptyChecks.isEmpty(dependencies) ? null : dependencies.getFirst());
    entity.setCalendarCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_CALENDAR_CODE)));
    entity.setWindowCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_WINDOW_CODE)));
    entity.setRetryPolicy(normalizeEnum(row.get(COL_RETRY_POLICY)));
    String retryMaxCountValue = row.get(COL_RETRY_MAX_COUNT);
    Integer retryMaxCount = parseInteger(retryMaxCountValue);
    entity.setRetryMaxCount(retryMaxCount);
    String timeoutSecondsValue = row.get(COL_TIMEOUT_SECONDS);
    Integer timeoutSeconds = parseInteger(timeoutSecondsValue);
    entity.setTimeoutSeconds(timeoutSeconds);
    entity.setPriority(5);
    entity.setShardStrategy(normalizeEnum(row.get(COL_SHARD_STRATEGY)));
    entity.setExecutionMode(resolveExecutionMode(row));
    entity.setWatermarkField(normalize(row.get(COL_WATERMARK_FIELD)));
    entity.setExecutionHandler(normalize(row.get(COL_EXECUTION_HANDLER)));
    String normalizedParamSchema = normalize(row.get(COL_PARAM_SCHEMA));
    String paramSchema =
        TenantConfigReferenceResolver.normalizeConfigReferences(normalizedParamSchema);
    entity.setParamSchema(paramSchema);
    String normalizedDefaultParams = normalize(row.get(COL_DEFAULT_PARAMS));
    String defaultParams =
        TenantConfigReferenceResolver.normalizeConfigReferences(normalizedDefaultParams);
    rejectUnresolvedSecretMasks(defaultParams);
    entity.setDefaultParams(defaultParams);
    String enabledValue = row.get(COL_ENABLED);
    boolean enabled = parseBoolean(enabledValue, true);
    entity.setEnabled(enabled);
    entity.setDescription(normalize(row.get(COL_DESCRIPTION)));
    entity.setCreatedBy(safeOp(ctx.operatorId()));
    entity.setUpdatedBy(safeOp(ctx.operatorId()));
    return entity;
  }

  private void updateJob(
      Map<String, String> row, ApplyContext ctx, String jobCode, JobDefinitionEntity existing) {
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(ctx.tenantId());
    param.setJobCode(jobCode);
    String mergedJobName = mergeText(row, COL_JOB_NAME, existing.getJobName());
    param.setJobName(mergedJobName);
    String mergedQueueCode = mergeConfigCode(row, COL_QUEUE_CODE, existing.getQueueCode());
    param.setQueueCode(mergedQueueCode);
    String mergedWorkerGroup = mergeWorkerGroup(row, COL_WORKER_GROUP, existing.getWorkerGroup());
    param.setWorkerGroup(mergedWorkerGroup);
    String mergedScheduleExpr = mergeText(row, COL_SCHEDULE_EXPR, existing.getScheduleExpr());
    param.setScheduleExpr(mergedScheduleExpr);
    if (Texts.hasText(row.get(COL_DEPENDS_ON_JOB_CODES))) {
      List<String> dependencies = parseJobDependencies(row);
      param.setDependsOnJobCode(EmptyChecks.isEmpty(dependencies) ? null : dependencies.getFirst());
    } else {
      param.setDependsOnJobCode(
          mergeText(row, COL_DEPENDS_ON_JOB_CODE, existing.getDependsOnJobCode()));
    }
    String mergedCalendarCode = mergeConfigCode(row, COL_CALENDAR_CODE, existing.getCalendarCode());
    param.setCalendarCode(mergedCalendarCode);
    String mergedWindowCode = mergeConfigCode(row, COL_WINDOW_CODE, existing.getWindowCode());
    param.setWindowCode(mergedWindowCode);
    String mergedRetryPolicy = mergeEnum(row, COL_RETRY_POLICY, existing.getRetryPolicy());
    param.setRetryPolicy(mergedRetryPolicy);
    Integer mergedRetryMaxCount =
        mergeInteger(row, COL_RETRY_MAX_COUNT, existing.getRetryMaxCount());
    param.setRetryMaxCount(mergedRetryMaxCount);
    Integer mergedTimeoutSeconds =
        mergeInteger(row, COL_TIMEOUT_SECONDS, existing.getTimeoutSeconds());
    param.setTimeoutSeconds(mergedTimeoutSeconds);
    param.setPriority(existing.getPriority());
    String mergedShardStrategy = mergeEnum(row, COL_SHARD_STRATEGY, existing.getShardStrategy());
    param.setShardStrategy(mergedShardStrategy);
    String mergedExecutionMode = mergeEnum(row, COL_EXECUTION_MODE, existing.getExecutionMode());
    param.setExecutionMode(mergedExecutionMode);
    String mergedWatermarkField = mergeText(row, COL_WATERMARK_FIELD, existing.getWatermarkField());
    param.setWatermarkField(mergedWatermarkField);
    updateDefaultParams(row, existing, param);
    Boolean enabled = parseBoolean(row.get(COL_ENABLED), existing.getEnabled());
    param.setEnabled(enabled);
    String mergedDescription = mergeText(row, COL_DESCRIPTION, existing.getDescription());
    param.setDescription(mergedDescription);
    String operatorId = safeOp(ctx.operatorId());
    param.setUpdatedBy(operatorId);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
  }

  private void updateDefaultParams(
      Map<String, String> row,
      JobDefinitionEntity existing,
      JobDefinitionMaintenanceUpdateParam param) {
    String requestedDefaultParams = normalize(row.get(COL_DEFAULT_PARAMS));
    String defaultParams =
        TenantConfigReferenceResolver.normalizeConfigReferences(requestedDefaultParams);
    if (!Texts.hasText(defaultParams)) {
      return;
    }
    defaultParams = SecretMasking.preserveMaskedJson(defaultParams, existing.getDefaultParams());
    rejectUnresolvedSecretMasks(defaultParams);
    param.setDefaultParams(defaultParams);
    param.setDefaultParamsSpecified(true);
  }

  private static String mergeText(Map<String, String> row, String key, String currentValue) {
    String requested = normalize(row.get(key));
    return Texts.hasText(requested) ? requested : currentValue;
  }

  private static String mergeEnum(Map<String, String> row, String key, String currentValue) {
    String requested = normalizeEnum(row.get(key));
    return Texts.hasText(requested) ? requested : currentValue;
  }

  private static Integer mergeInteger(Map<String, String> row, String key, Integer currentValue) {
    Integer requested = parseInteger(row.get(key));
    return EmptyChecks.isNull(requested) ? currentValue : requested;
  }

  private static String mergeConfigCode(Map<String, String> row, String key, String currentValue) {
    String requested = row.get(key);
    return Texts.hasText(requested) ? CodeNormalizer.toConfigFormOrNull(requested) : currentValue;
  }

  private static String mergeWorkerGroup(Map<String, String> row, String key, String currentValue) {
    String requested = row.get(key);
    return Texts.hasText(requested) ? CodeNormalizer.toUpperOrNull(requested) : currentValue;
  }

  private void applyJobMonitoringPolicies(List<Map<String, String>> rows, ApplyContext ctx) {
    for (Map<String, String> row : rows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      JobDefinitionEntity job = jobDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode);
      if (EmptyChecks.isNull(job)) {
        throw invalidParsedRow(
            JOB_MONITORING_POLICY_SHEET, List.of("job_code does not exist for tenant: " + jobCode));
      }
      String tenantId = ctx.tenantId();
      Long jobDefinitionId = job.getId();
      Integer softRuntimeSeconds = valueOrExisting(
          row,
          COL_SOFT_RUNTIME_SECONDS,
          job.getSoftRuntimeSeconds(),
          TenantConfigPackageExcelApplyService::parseNonNegativeMonitoringValue);
      String softRuntimeSeverity = valueOrExisting(
          row,
          COL_SOFT_RUNTIME_SEVERITY,
          job.getSoftRuntimeSeverity(),
          TenantConfigPackageExcelApplyService::resolveMonitoringSeverity);
      Integer startGraceSeconds = valueOrExisting(
          row,
          COL_START_GRACE_SECONDS,
          job.getStartGraceSeconds(),
          value -> parseStartGraceSeconds(value, job));
      String startGraceSeverity = valueOrExisting(
          row,
          COL_START_GRACE_SEVERITY,
          job.getStartGraceSeverity(),
          TenantConfigPackageExcelApplyService::resolveMonitoringSeverity);
      LocalTime completionDeadlineLocalTime = valueOrExisting(
          row,
          COL_COMPLETION_DEADLINE_LOCAL_TIME,
          job.getCompletionDeadlineLocalTime(),
          TenantConfigPackageExcelApplyService::parseMonitoringTime);
      Integer completionDeadlineDayOffset = valueOrExisting(
          row,
          COL_COMPLETION_DEADLINE_DAY_OFFSET,
          job.getCompletionDeadlineDayOffset(),
          TenantConfigPackageExcelApplyService::parseNonNegativeMonitoringValue);
      Integer dependencyCompletionWindowSeconds = valueOrExisting(
          row,
          COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS,
          job.getDependencyCompletionWindowSeconds(),
          TenantConfigPackageExcelApplyService::parseNonNegativeMonitoringValue);
      String completionDeadlineSeverity = valueOrExisting(
          row,
          COL_COMPLETION_DEADLINE_SEVERITY,
          job.getCompletionDeadlineSeverity(),
          TenantConfigPackageExcelApplyService::resolveMonitoringSeverity);
      String operatorId = safeOp(ctx.operatorId());

      JobMonitoringPolicyUpsertParam param = JobMonitoringPolicyUpsertParam.builder()
          .tenantId(tenantId)
          .jobDefinitionId(jobDefinitionId)
          .softRuntimeSeconds(softRuntimeSeconds)
          .softRuntimeSeverity(softRuntimeSeverity)
          .startGraceSeconds(startGraceSeconds)
          .startGraceSeverity(startGraceSeverity)
          .completionDeadlineLocalTime(completionDeadlineLocalTime)
          .completionDeadlineDayOffset(completionDeadlineDayOffset)
          .dependencyCompletionWindowSeconds(dependencyCompletionWindowSeconds)
          .completionDeadlineSeverity(completionDeadlineSeverity)
          .updatedBy(operatorId)
          .build();
      jobDefinitionMapper.upsertJobMonitoringPolicy(param);
    }
  }

  private static <T> T valueOrExisting(
      Map<String, String> row, String key, T existingValue, Function<String, T> parser) {
    if (!row.containsKey(key)) {
      return existingValue;
    }
    String rawValue = row.get(key);
    return parser.apply(rawValue);
  }

  private static Integer parseNonNegativeMonitoringValue(String value) {
    Integer parsed = parseInteger(value);
    return EmptyChecks.isNull(parsed) ? 0 : parsed;
  }

  private int parseStartGraceSeconds(String value, JobDefinitionEntity job) {
    Integer configured = parseInteger(value);
    if (EmptyChecks.isNotNull(configured)) {
      return configured;
    }
    return isStartGraceMonitored(job) ? monitoringDefaults.getStartGraceSeconds() : 0;
  }

  private boolean isStartGraceMonitored(JobDefinitionEntity entity) {
    return "CRON".equals(entity.getScheduleType()) || Texts.hasText(entity.getDependsOnJobCode());
  }

  private static LocalTime parseMonitoringTime(String value) {
    String normalized = normalize(value);
    if (!Texts.hasText(normalized)) {
      return null;
    }
    try {
      return LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HH:mm"));
    } catch (DateTimeParseException exception) {
      throw invalidParsedRow(
          JOB_MONITORING_POLICY_SHEET,
          List.of(COL_COMPLETION_DEADLINE_LOCAL_TIME + " must use HH:mm (00:00-23:59)"));
    }
  }

  private static String resolveMonitoringSeverity(String severity) {
    String normalized = normalizeEnum(severity);
    return Texts.hasText(normalized) ? normalized : "WARN";
  }

  private static String resolveExecutionMode(Map<String, String> row) {
    String value = normalizeEnum(row.get(COL_EXECUTION_MODE));
    return Texts.hasText(value) ? value : "FULL";
  }

  private ApplyStats applyChannels(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : rows) {
      String code = normalize(row.get(COL_CHANNEL_CODE));
      Map<String, Object> existing =
          fileChannelConfigMapper.selectByUniqueKey(ctx.tenantId(), code);
      Map<String, String> values =
          mergeExistingValues(row, existing, ConfigPackageExcelSchema.FileChannel.COLUMNS);
      FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
      param.setTenantId(ctx.tenantId());
      param.setChannelCode(code);
      param.setChannelName(normalize(values.get(COL_CHANNEL_NAME)));
      param.setChannelType(normalizeEnum(values.get(COL_CHANNEL_TYPE)));
      param.setTargetEndpoint(
          normalize(values.get(ConfigPackageExcelSchema.FileChannel.COL_TARGET_ENDPOINT)));
      param.setAuthType(normalizeEnum(values.get(COL_AUTH_TYPE)));
      String configJson = values.get(COL_CONFIG_JSON);
      if (!Texts.hasText(configJson)) {
        if (EmptyChecks.isNull(existing) && !"NONE".equals(param.getAuthType())) {
          throw BizException.of(
              ResultCode.INVALID_ARGUMENT, "error.config_package.channel_config_required");
        }
        configJson = "{}";
      }
      param.setConfigJson(configJson);
      param.setReceiptPolicy(normalizeEnum(values.get(COL_RECEIPT_POLICY)));
      String timeoutSecondsValue = values.get(COL_TIMEOUT_SECONDS);
      Integer timeoutSeconds = parseInteger(timeoutSecondsValue);
      param.setTimeoutSeconds(timeoutSeconds);
      String enabledValue = values.get(COL_ENABLED);
      boolean enabled = parseBoolean(enabledValue, true);
      param.setEnabled(enabled);
      param.setCreatedBy(safeOp(ctx.operatorId()));
      param.setUpdatedBy(safeOp(ctx.operatorId()));
      fileChannelConfigMapper.upsertFileChannelConfig(param);
      if (EmptyChecks.isEmpty(existing)) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyFileTemplates(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : rows) {
      List<String> issues = new ArrayList<>();
      TemplateRow template = FileTemplateExcelRowParser.parseRow(ctx.tenantId(), 0, row, issues);
      if (EmptyChecks.isNotEmpty(issues)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ResultCode.INVALID_ARGUMENT.detailKey(),
            "invalid file_template_config row: " + issues);
      }
      Map<String, Object> existing = fileTemplateConfigMapper.selectByUniqueKey(
          ctx.tenantId(), template.templateCode(), template.version());
      Map<String, String> values =
          mergeExistingValues(row, existing, ConfigPackageExcelSchema.FileTemplate.COLUMNS);
      template = FileTemplateExcelRowParser.parseRow(ctx.tenantId(), 0, values, issues);
      FileTemplateConfigUpsertParam templateParam =
          FileTemplateExcelRowParser.toUpsertParam(ctx.tenantId(), template, ctx.operatorId());
      fileTemplateConfigMapper.upsertFileTemplateConfig(templateParam);
      if (EmptyChecks.isEmpty(existing)) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyWorkflows(
      List<Map<String, String>> defRows,
      List<Map<String, String>> nodeRows,
      List<Map<String, String>> edgeRows,
      ApplyContext ctx) {
    Map<String, List<Map<String, String>>> nodesByWf = nodeRows.stream()
        .collect(Collectors.groupingBy(r -> normalize(r.get(COL_WORKFLOW_CODE))
            + KEY_SEP_COLON
            + normalize(r.get(COL_WORKFLOW_VERSION))));
    Map<String, List<Map<String, String>>> edgesByWf = edgeRows.stream()
        .collect(Collectors.groupingBy(r -> normalize(r.get(COL_WORKFLOW_CODE))
            + KEY_SEP_COLON
            + normalize(r.get(COL_WORKFLOW_VERSION))));
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : defRows) {
      String sourceWorkflowCode = normalize(row.get(COL_WORKFLOW_CODE));
      String sourceVersionText = normalize(row.get(COL_VERSION));
      int sourceVersion = Integer.parseInt(sourceVersionText);
      String singleJobCode = normalize(row.get(COL_SINGLE_JOB_CODE));
      JobDefinitionEntity singleJob = Texts.hasText(singleJobCode)
          ? jobDefinitionMapper.selectByUniqueKey(ctx.tenantId(), singleJobCode)
          : null;
      if (Texts.hasText(singleJobCode) && EmptyChecks.isNull(singleJob)) {
        throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");
      }
      WorkflowDefinitionEntity existing = workflowDefinitionMapper.selectByUniqueKey(
          ctx.tenantId(), sourceWorkflowCode, sourceVersion);
      if (EmptyChecks.isNull(existing) && EmptyChecks.isNotNull(singleJob)) {
        existing = workflowDefinitionMapper.selectBySingleJobDefinitionId(
            ctx.tenantId(), singleJob.getId());
      }
      boolean existingSingleJobBindingWouldChange = EmptyChecks.isNotNull(existing)
          && EmptyChecks.isNotNull(singleJob)
          && !singleJob.getId().equals(existing.getSingleJobDefinitionId());
      if (existingSingleJobBindingWouldChange) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, "error.workflow.single_job_relation_conflict");
      }
      String targetWorkflowCode =
          EmptyChecks.isNull(existing) ? sourceWorkflowCode : existing.getWorkflowCode();
      int currentVersion = EmptyChecks.isNull(existing) ? sourceVersion : existing.getVersion();
      WorkflowDefinitionUpsertParam defParam = new WorkflowDefinitionUpsertParam();
      defParam.setTenantId(ctx.tenantId());
      defParam.setWorkflowCode(targetWorkflowCode);
      defParam.setVersion(currentVersion);
      populateWorkflowDefinitionFields(defParam, row, existing, singleJob, ctx.operatorId());
      if (EmptyChecks.isNotNull(existing)) {
        String existingCalendarCode =
            CodeNormalizer.toConfigFormOrNull(existing.getCalendarCode());
        String requestedCalendarCode =
            CodeNormalizer.toConfigFormOrNull(defParam.getCalendarCode());
        boolean calendarCodeChanged = !Objects.equals(existingCalendarCode, requestedCalendarCode);
        if (calendarCodeChanged
            && workflowDefinitionMapper.hasCalendarScopedExpectations(
                ctx.tenantId(), existing.getId())) {
          throw BizException.of(
              ResultCode.STATE_CONFLICT, "error.workflow.calendar_has_expectations");
        }
      }
      if (existing == null) {
        workflowDefinitionMapper.upsertWorkflowDefinition(defParam);
        inserted++;
      } else {
        WorkflowDefinitionVersionUpdateParam versionedUpdate =
            WorkflowDefinitionVersionUpdateParam.builder()
                .tenantId(ctx.tenantId())
                .id(existing.getId())
                .expectedVersion(currentVersion)
                .workflowName(defParam.getWorkflowName())
                .workflowType(defParam.getWorkflowType())
                .enabled(defParam.getEnabled())
                .scheduleEnabled(defParam.getScheduleEnabled())
                .scheduleType(defParam.getScheduleType())
                .scheduleExpr(defParam.getScheduleExpr())
                .timezone(defParam.getTimezone())
                .triggerMode(defParam.getTriggerMode())
                .calendarCode(defParam.getCalendarCode())
                .dependsOnJobCode(defParam.getDependsOnJobCode())
                .build();
        int rows = workflowDefinitionMapper.updateAndBumpVersion(versionedUpdate);
        if (rows == 0) {
          throw BizException.of(
              ResultCode.STATE_CONFLICT, "error.workflow.definition_version_conflict");
        }
        updated++;
      }

      WorkflowDefinitionEntity saved = EmptyChecks.isNull(existing)
          ? workflowDefinitionMapper.selectByUniqueKey(
              ctx.tenantId(), targetWorkflowCode, currentVersion)
          : existing;
      if (saved == null || saved.getId() == null) {
        continue;
      }
      Long defId = saved.getId();
      String wfKey = sourceWorkflowCode + KEY_SEP_COLON + sourceVersion;
      applyWfNodes(ctx.tenantId(), defId, nodesByWf.getOrDefault(wfKey, List.of()));
      applyWfEdges(ctx.tenantId(), defId, edgesByWf.getOrDefault(wfKey, List.of()));
      cacheInvalidationService.evictWorkflowDefinition(ctx.tenantId(), targetWorkflowCode);
      int snapshotVersion = EmptyChecks.isNull(existing) ? currentVersion : currentVersion + 1;
      appendAppliedWorkflowSnapshot(ctx, defId, targetWorkflowCode, snapshotVersion, defParam);
    }
    return new ApplyStats(inserted, updated);
  }

  /** 节点与边应用完成后再写版本快照，保证审批和运行读取到同一份完整定义。 */
  private void appendAppliedWorkflowSnapshot(
      ApplyContext ctx,
      Long defId,
      String targetWorkflowCode,
      int snapshotVersion,
      WorkflowDefinitionUpsertParam defParam) {
    WorkflowDefinitionSaveRequest snapshot = new WorkflowDefinitionSaveRequest();
    snapshot.setTenantId(ctx.tenantId());
    snapshot.setWorkflowCode(targetWorkflowCode);
    snapshot.setWorkflowName(defParam.getWorkflowName());
    snapshot.setWorkflowType(defParam.getWorkflowType());
    snapshot.setEnabled(defParam.getEnabled());
    snapshot.setScheduleType(defParam.getScheduleType());
    snapshot.setScheduleExpr(defParam.getScheduleExpr());
    snapshot.setTimezone(defParam.getTimezone());
    snapshot.setTriggerMode(defParam.getTriggerMode());
    snapshot.setCalendarCode(defParam.getCalendarCode());
    snapshot.setDependsOnJobCode(defParam.getDependsOnJobCode());
    WorkflowVersionSnapshotCommand snapshotCommand = WorkflowVersionSnapshotCommand.builder()
        .tenantId(ctx.tenantId())
        .definitionId(defId)
        .workflowCode(targetWorkflowCode)
        .version(snapshotVersion)
        .body(snapshot)
        .scheduleEnabled(defParam.getScheduleEnabled())
        .savedBy(ctx.operatorId())
        .build();
    workflowWriteSupport.appendVersionSnapshot(snapshotCommand);
  }

  /** 省略列沿用当前定义，显式列按本次配置包值写入；不在字段装配阶段修改图或版本。 */
  private void populateWorkflowDefinitionFields(
      WorkflowDefinitionUpsertParam defParam,
      Map<String, String> row,
      WorkflowDefinitionEntity existing,
      JobDefinitionEntity singleJob,
      String operatorId) {
    String currentWorkflowName = EmptyChecks.isNull(existing) ? null : existing.getWorkflowName();
    String workflowName = mergeText(row, COL_WORKFLOW_NAME, currentWorkflowName);
    defParam.setWorkflowName(workflowName);
    String currentWorkflowType = EmptyChecks.isNull(existing) ? null : existing.getWorkflowType();
    String workflowType = mergeEnum(row, COL_WORKFLOW_TYPE, currentWorkflowType);
    defParam.setWorkflowType(workflowType);

    String requestedEnabled = row.get(COL_ENABLED);
    boolean currentEnabled = EmptyChecks.isNull(existing) || existing.getEnabled();
    boolean enabled = parseBoolean(requestedEnabled, currentEnabled);
    defParam.setEnabled(enabled);

    Long workflowDefinitionId = EmptyChecks.isNull(singleJob)
        ? (EmptyChecks.isNull(existing) ? null : existing.getSingleJobDefinitionId())
        : singleJob.getId();
    defParam.setSingleJobDefinitionId(workflowDefinitionId);

    String requestedScheduleEnabled = row.get(COL_SCHEDULE_ENABLED);
    boolean currentScheduleEnabled =
        EmptyChecks.isNotNull(existing) && Boolean.TRUE.equals(existing.getScheduleEnabled());
    boolean scheduleEnabled = parseBoolean(requestedScheduleEnabled, currentScheduleEnabled);
    defParam.setScheduleEnabled(scheduleEnabled);

    String currentScheduleType =
        EmptyChecks.isNull(existing) ? "MANUAL" : existing.getScheduleType();
    String scheduleType = mergeEnum(row, COL_SCHEDULE_TYPE, currentScheduleType);
    defParam.setScheduleType(scheduleType);
    String currentScheduleExpr = EmptyChecks.isNull(existing) ? null : existing.getScheduleExpr();
    String scheduleExpr = mergeText(row, COL_SCHEDULE_EXPR, currentScheduleExpr);
    defParam.setScheduleExpr(scheduleExpr);
    String currentTimezone =
        EmptyChecks.isNull(existing) ? CommonConstants.DEFAULT_TIMEZONE_ID : existing.getTimezone();
    String timezone = mergeText(row, COL_TIMEZONE, currentTimezone);
    defParam.setTimezone(timezone);
    String currentTriggerMode = EmptyChecks.isNull(existing) ? "MANUAL" : existing.getTriggerMode();
    String triggerMode = mergeEnum(row, COL_TRIGGER_MODE, currentTriggerMode);
    defParam.setTriggerMode(triggerMode);
    String currentCalendarCode = EmptyChecks.isNull(existing) ? null : existing.getCalendarCode();
    String mergedCalendarCode = mergeText(row, COL_CALENDAR_CODE, currentCalendarCode);
    String calendarCode = CodeNormalizer.toConfigFormOrNull(mergedCalendarCode);
    defParam.setCalendarCode(calendarCode);
    String currentDependsOnJobCode =
        EmptyChecks.isNull(existing) ? null : existing.getDependsOnJobCode();
    String dependsOnJobCode = mergeText(row, COL_DEPENDS_ON_JOB_CODE, currentDependsOnJobCode);
    defParam.setDependsOnJobCode(dependsOnJobCode);
    String currentDescription = EmptyChecks.isNull(existing) ? null : existing.getDescription();
    String description = mergeText(row, COL_DESCRIPTION, currentDescription);
    defParam.setDescription(description);
    String createdBy = safeOp(operatorId);
    defParam.setCreatedBy(createdBy);
    String updatedBy = safeOp(operatorId);
    defParam.setUpdatedBy(updatedBy);
  }

  private void applyWfNodes(String tenantId, Long defId, List<Map<String, String>> nodes) {
    for (Map<String, String> node : nodes) {
      String nodeCode = normalize(node.get(COL_NODE_CODE));
      WorkflowNodeEntity existing = workflowNodeMapper.selectByUniqueKey(defId, nodeCode);
      Map<String, String> values = mergeExistingValues(
          node, workflowNodeValues(existing), ConfigPackageExcelSchema.WorkflowNode.COLUMNS);
      WorkflowNodeUpsertParam p = new WorkflowNodeUpsertParam();
      p.setTenantId(tenantId);
      p.setWorkflowDefinitionId(defId);
      p.setNodeCode(normalize(node.get(COL_NODE_CODE)));
      p.setNodeName(mergeText(
          values,
          COL_NODE_NAME,
          existing == null ? null : existing.getNodeName())); // empty-check: allow - Sonar S2259
      p.setNodeType(mergeEnum(
          values,
          COL_NODE_TYPE,
          existing == null ? null : existing.getNodeType())); // empty-check: allow - Sonar S2259
      p.setRelatedJobCode(mergeText(
          values,
          COL_RELATED_JOB_CODE,
          existing == null // empty-check: allow - Sonar S2259
              ? null
              : existing.getRelatedJobCode())); // empty-check: allow - Sonar S2259
      p.setWorkerGroup(mergeWorkerGroup(
          values,
          COL_WORKER_GROUP,
          existing == null ? null : existing.getWorkerGroup())); // empty-check: allow - Sonar S2259
      p.setWindowCode(mergeConfigCode(
          values,
          COL_WINDOW_CODE,
          existing == null ? null : existing.getWindowCode())); // empty-check: allow - Sonar S2259
      p.setNodeOrder(mergeInteger(
          values,
          COL_NODE_ORDER,
          existing == null ? null : existing.getNodeOrder())); // empty-check: allow - Sonar S2259
      p.setRetryPolicy(mergeEnum(
          values,
          COL_RETRY_POLICY,
          existing == null ? null : existing.getRetryPolicy())); // empty-check: allow - Sonar S2259
      p.setRetryMaxCount(mergeInteger(
          values,
          COL_RETRY_MAX_COUNT,
          existing == null // empty-check: allow - Sonar S2259
              ? null
              : existing.getRetryMaxCount())); // empty-check: allow - Sonar S2259
      p.setTimeoutSeconds(mergeInteger(
          values,
          COL_TIMEOUT_SECONDS,
          existing == null // empty-check: allow - Sonar S2259
              ? null
              : existing.getTimeoutSeconds())); // empty-check: allow - Sonar S2259
      String requestedNodeParams = values.get(COL_NODE_PARAMS);
      String existingNodeParams = EmptyChecks.isNull(existing) ? null : existing.getNodeParams();
      String mergedNodeParams = mergeNodeParams(requestedNodeParams, existingNodeParams);
      String normalizedNodeParams =
          TenantConfigReferenceResolver.normalizeConfigReferences(mergedNodeParams);
      p.setNodeParams(normalizedNodeParams); // empty-check: allow - Sonar S2259
      String configuredEnabled = values.get(COL_ENABLED);
      boolean existingEnabled = EmptyChecks.isNull(existing) || existing.getEnabled();
      p.setEnabled(
          parseBoolean(configuredEnabled, existingEnabled)); // empty-check: allow - Sonar S2259
      p.setCrossDayDependencies(
          existing == null // empty-check: allow - Sonar S2259
              ? null
              : existing.getCrossDayDependencies()); // empty-check: allow - Sonar S2259
      p.setCrossDayDependencyTimeoutSeconds(
          existing == null // empty-check: allow - Sonar S2259
              ? null
              : existing.getCrossDayDependencyTimeoutSeconds()); // empty-check: allow - Sonar S2259
      workflowNodeMapper.upsertWorkflowNode(p);
    }
  }

  private void applyWfEdges(String tenantId, Long defId, List<Map<String, String>> edges) {
    for (Map<String, String> edge : edges) {
      String fromNodeCode = normalize(edge.get(COL_FROM_NODE_CODE));
      String toNodeCode = normalize(edge.get(COL_TO_NODE_CODE));
      String edgeType = normalizeEnum(edge.get(COL_EDGE_TYPE));
      WorkflowEdgeEntity existing = workflowEdgeMapper.selectByUniqueKey(
          defId, fromNodeCode, toNodeCode, Texts.hasText(edgeType) ? edgeType : "SUCCESS");
      Map<String, String> values = mergeExistingValues(
          edge, workflowEdgeValues(existing), ConfigPackageExcelSchema.WorkflowEdge.COLUMNS);
      WorkflowEdgeUpsertParam p = new WorkflowEdgeUpsertParam();
      p.setTenantId(tenantId);
      p.setWorkflowDefinitionId(defId);
      p.setFromNodeCode(fromNodeCode);
      p.setToNodeCode(toNodeCode);
      p.setEdgeType(mergeEnum(
          values,
          COL_EDGE_TYPE,
          existing == null ? null : existing.getEdgeType())); // empty-check: allow - Sonar S2259
      p.setConditionExpr(mergeText(
          values,
          COL_CONDITION_EXPR,
          existing == null // empty-check: allow - Sonar S2259
              ? null
              : existing.getConditionExpr())); // empty-check: allow - Sonar S2259
      String configuredEnabled = values.get(COL_ENABLED);
      boolean existingEnabled = EmptyChecks.isNull(existing) || existing.getEnabled();
      p.setEnabled(
          parseBoolean(configuredEnabled, existingEnabled)); // empty-check: allow - Sonar S2259
      workflowEdgeMapper.upsertWorkflowEdge(p);
    }
  }

  private static Map<String, Object> workflowNodeValues(WorkflowNodeEntity node) {
    if (node == null) { // empty-check: allow - Sonar S2259
      return Map.of();
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(COL_NODE_CODE, node.getNodeCode());
    values.put(COL_NODE_NAME, node.getNodeName());
    values.put(COL_NODE_TYPE, node.getNodeType());
    values.put(COL_RELATED_JOB_CODE, node.getRelatedJobCode());
    values.put(COL_WORKER_GROUP, node.getWorkerGroup());
    values.put(COL_WINDOW_CODE, node.getWindowCode());
    values.put(COL_NODE_ORDER, node.getNodeOrder());
    values.put(COL_RETRY_POLICY, node.getRetryPolicy());
    values.put(COL_RETRY_MAX_COUNT, node.getRetryMaxCount());
    values.put(COL_TIMEOUT_SECONDS, node.getTimeoutSeconds());
    values.put(COL_NODE_PARAMS, node.getNodeParams());
    values.put(COL_ENABLED, node.getEnabled());
    return values;
  }

  private static Map<String, Object> workflowEdgeValues(WorkflowEdgeEntity edge) {
    if (edge == null) { // empty-check: allow - Sonar S2259
      return Map.of();
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(COL_FROM_NODE_CODE, edge.getFromNodeCode());
    values.put(COL_TO_NODE_CODE, edge.getToNodeCode());
    values.put(COL_EDGE_TYPE, edge.getEdgeType());
    values.put(COL_CONDITION_EXPR, edge.getConditionExpr());
    values.put(COL_ENABLED, edge.getEnabled());
    return values;
  }

  private static Map<String, String> mergeExistingValues(
      Map<String, String> incoming, @Nullable Map<String, ?> existing, List<String> columns) {
    Map<String, String> merged = new LinkedHashMap<>(incoming);
    if (existing == null) { // empty-check: allow - Sonar S2259
      return merged;
    }
    for (String column : columns) {
      String value = incoming.get(column);
      if (Texts.hasText(value)) {
        continue;
      }
      Object current = existing.get(column);
      if (EmptyChecks.isNull(current)) {
        current = existing.get(toDatabaseColumn(column));
      }
      if (EmptyChecks.isNotNull(current)) {
        merged.put(
            column,
            current instanceof Map<?, ?> || current instanceof List<?>
                ? JsonUtils.toJson(current)
                : current.toString());
      }
    }
    return merged;
  }

  private static String mergeNodeParams(String incoming, String stored) {
    if (!Texts.hasText(incoming)) {
      return stored;
    }
    String merged = SecretMasking.preserveMaskedJson(incoming, stored);
    rejectUnresolvedSecretMasks(merged);
    return merged;
  }

  private static void rejectUnresolvedSecretMasks(String json) {
    if (SecretMasking.containsMaskedSensitiveValue(json)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.config.masked_secret_requires_value");
    }
  }

  private static String toDatabaseColumn(String column) {
    StringBuilder result = new StringBuilder(column.length() + 4);
    for (int i = 0; i < column.length(); i++) {
      char current = column.charAt(i);
      if (Character.isUpperCase(current)) {
        result.append('_').append(Character.toLowerCase(current));
      } else {
        result.append(current);
      }
    }
    return result.toString();
  }

  private record ApplyStats(int inserted, int updated) {}
}
