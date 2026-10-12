package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.normalize;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.normalizeEnum;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.parseBoolean;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.parseInteger;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.safeOp;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelWorkbookWriter.*;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpsertParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpsertParam;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 租户配置包 Excel apply 阶段的写库逻辑（从 {@link DefaultTenantConfigPackageExcelService}
 * 拆出）。按 resourceQueue → businessCalendar → batchWindow → fileTemplate → channel → job →
 * pipeline+step → workflow+node+edge 顺序 upsert；调用方负责事务边界与 totalInvalid 闸门。
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
  private final JobMonitoringDefaultsProperties monitoringDefaults;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;

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
      int pipelineInserted,
      int pipelineUpdated,
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
    ApplyStats pipelineStats = applyPipelines(result.validPipelines(), result.validSteps(), ctx);
    ApplyStats wfStats =
        applyWorkflows(result.validWfDefs(), result.validWfNodes(), result.validWfEdges(), ctx);
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
        pipelineStats.inserted(),
        pipelineStats.updated(),
        wfStats.inserted(),
        wfStats.updated());
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
      resourceQueueMapper.upsertResourceQueue(
          ResourceQueueExcelRowParser.toUpsertParam(queue, ctx.operatorId()));
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
      BusinessCalendarUpsertParam calendarParam =
          BusinessCalendarExcelRowParser.toUpsertParam(calendar, safeOp(ctx.operatorId()));
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
      target.setCatchUpMaxDays(numberValue(existing.get("catchUpMaxDays")));
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
    return new ApplyStats(inserted, updated);
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
    jobDefinitionMapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
        .tenantId(ctx.tenantId())
        .jobDefinitionId(entity.getId())
        .softRuntimeSeconds(monitoringDefaults.getSoftRuntimeSeconds())
        .softRuntimeSeverity("WARN")
        .startGraceSeconds(
            isStartGraceMonitored(entity) ? monitoringDefaults.getStartGraceSeconds() : 0)
        .startGraceSeverity("WARN")
        .completionDeadlineLocalTime(null)
        .completionDeadlineDayOffset(0)
        .dependencyCompletionWindowSeconds(0)
        .completionDeadlineSeverity("WARN")
        .updatedBy(safeOp(ctx.operatorId()))
        .build());
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
    entity.setDependsOnJobCode(normalize(row.get(COL_DEPENDS_ON_JOB_CODE)));
    entity.setCalendarCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_CALENDAR_CODE)));
    entity.setWindowCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_WINDOW_CODE)));
    entity.setRetryPolicy(normalizeEnum(row.get(COL_RETRY_POLICY)));
    entity.setRetryMaxCount(parseInteger(row.get(COL_RETRY_MAX_COUNT)));
    entity.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
    entity.setShardStrategy(normalizeEnum(row.get(COL_SHARD_STRATEGY)));
    entity.setExecutionMode(resolveExecutionMode(row));
    entity.setWatermarkField(normalize(row.get(COL_WATERMARK_FIELD)));
    entity.setExecutionHandler(normalize(row.get(COL_EXECUTION_HANDLER)));
    entity.setParamSchema(TenantConfigReferenceResolver.normalizeConfigReferences(
        normalize(row.get(COL_PARAM_SCHEMA))));
    String defaultParams = TenantConfigReferenceResolver.normalizeConfigReferences(
        normalize(row.get(COL_DEFAULT_PARAMS)));
    rejectUnresolvedSecretMasks(defaultParams);
    entity.setDefaultParams(defaultParams);
    entity.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
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
    param.setJobName(mergeText(row, COL_JOB_NAME, existing.getJobName()));
    param.setQueueCode(mergeConfigCode(row, COL_QUEUE_CODE, existing.getQueueCode()));
    param.setWorkerGroup(mergeWorkerGroup(row, COL_WORKER_GROUP, existing.getWorkerGroup()));
    param.setScheduleExpr(mergeText(row, COL_SCHEDULE_EXPR, existing.getScheduleExpr()));
    param.setDependsOnJobCode(
        mergeText(row, COL_DEPENDS_ON_JOB_CODE, existing.getDependsOnJobCode()));
    param.setCalendarCode(mergeConfigCode(row, COL_CALENDAR_CODE, existing.getCalendarCode()));
    param.setWindowCode(mergeConfigCode(row, COL_WINDOW_CODE, existing.getWindowCode()));
    param.setRetryPolicy(mergeEnum(row, COL_RETRY_POLICY, existing.getRetryPolicy()));
    param.setRetryMaxCount(mergeInteger(row, COL_RETRY_MAX_COUNT, existing.getRetryMaxCount()));
    param.setTimeoutSeconds(mergeInteger(row, COL_TIMEOUT_SECONDS, existing.getTimeoutSeconds()));
    param.setShardStrategy(mergeEnum(row, COL_SHARD_STRATEGY, existing.getShardStrategy()));
    param.setExecutionMode(mergeEnum(row, COL_EXECUTION_MODE, existing.getExecutionMode()));
    param.setWatermarkField(mergeText(row, COL_WATERMARK_FIELD, existing.getWatermarkField()));
    updateDefaultParams(row, existing, param);
    param.setEnabled(parseBoolean(row.get(COL_ENABLED), existing.getEnabled()));
    param.setDescription(mergeText(row, COL_DESCRIPTION, existing.getDescription()));
    param.setUpdatedBy(safeOp(ctx.operatorId()));
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
  }

  private void updateDefaultParams(
      Map<String, String> row,
      JobDefinitionEntity existing,
      JobDefinitionMaintenanceUpdateParam param) {
    String defaultParams = TenantConfigReferenceResolver.normalizeConfigReferences(
        normalize(row.get(COL_DEFAULT_PARAMS)));
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
      jobDefinitionMapper.upsertJobMonitoringPolicy(JobMonitoringPolicyUpsertParam.builder()
          .tenantId(ctx.tenantId())
          .jobDefinitionId(job.getId())
          .softRuntimeSeconds(valueOrExisting(
              row,
              COL_SOFT_RUNTIME_SECONDS,
              job.getSoftRuntimeSeconds(),
              TenantConfigPackageExcelApplyService::parseNonNegativeMonitoringValue))
          .softRuntimeSeverity(valueOrExisting(
              row,
              COL_SOFT_RUNTIME_SEVERITY,
              job.getSoftRuntimeSeverity(),
              TenantConfigPackageExcelApplyService::resolveMonitoringSeverity))
          .startGraceSeconds(valueOrExisting(
              row,
              COL_START_GRACE_SECONDS,
              job.getStartGraceSeconds(),
              value -> parseStartGraceSeconds(value, job)))
          .startGraceSeverity(valueOrExisting(
              row,
              COL_START_GRACE_SEVERITY,
              job.getStartGraceSeverity(),
              TenantConfigPackageExcelApplyService::resolveMonitoringSeverity))
          .completionDeadlineLocalTime(valueOrExisting(
              row,
              COL_COMPLETION_DEADLINE_LOCAL_TIME,
              job.getCompletionDeadlineLocalTime(),
              TenantConfigPackageExcelApplyService::parseMonitoringTime))
          .completionDeadlineDayOffset(valueOrExisting(
              row,
              COL_COMPLETION_DEADLINE_DAY_OFFSET,
              job.getCompletionDeadlineDayOffset(),
              TenantConfigPackageExcelApplyService::parseNonNegativeMonitoringValue))
          .dependencyCompletionWindowSeconds(valueOrExisting(
              row,
              COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS,
              job.getDependencyCompletionWindowSeconds(),
              TenantConfigPackageExcelApplyService::parseNonNegativeMonitoringValue))
          .completionDeadlineSeverity(valueOrExisting(
              row,
              COL_COMPLETION_DEADLINE_SEVERITY,
              job.getCompletionDeadlineSeverity(),
              TenantConfigPackageExcelApplyService::resolveMonitoringSeverity))
          .updatedBy(safeOp(ctx.operatorId()))
          .build());
    }
  }

  private static <T> T valueOrExisting(
      Map<String, String> row,
      String key,
      T existingValue,
      java.util.function.Function<String, T> parser) {
    return row.containsKey(key) ? parser.apply(row.get(key)) : existingValue;
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
      param.setTimeoutSeconds(parseInteger(values.get(COL_TIMEOUT_SECONDS)));
      param.setEnabled(parseBoolean(values.get(COL_ENABLED), true));
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
      fileTemplateConfigMapper.upsertFileTemplateConfig(
          FileTemplateExcelRowParser.toUpsertParam(ctx.tenantId(), template, ctx.operatorId()));
      if (EmptyChecks.isEmpty(existing)) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private ApplyStats applyPipelines(
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> stepRows,
      ApplyContext ctx) {
    Map<String, List<Map<String, String>>> stepsByKey = stepRows.stream()
        .collect(Collectors.groupingBy(
            r -> normalize(r.get(COL_JOB_CODE)) + KEY_SEP_COLON + normalize(r.get(COL_VERSION))));
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : pipelineRows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      int version = Integer.parseInt(normalize(row.get(COL_VERSION)));
      Map<String, Object> existing =
          pipelineDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode, version);
      Long pipelineId;
      if (EmptyChecks.isEmpty(existing)) {
        Map<String, Object> params = buildPipelineInsertParams(row, ctx);
        pipelineDefinitionMapper.insert(params);
        pipelineId = ((Number) params.get(KEY_ID)).longValue();
        inserted++;
      } else {
        pipelineId = ((Number) existing.get(KEY_ID)).longValue();
        Map<String, String> values =
            mergeExistingValues(row, existing, ConfigPackageExcelSchema.PipelineDefinition.COLUMNS);
        pipelineDefinitionMapper.update(buildPipelineUpdateParams(pipelineId, values, ctx));
        updated++;
      }
      List<Map<String, String>> stepsForPipeline =
          stepsByKey.getOrDefault(jobCode + KEY_SEP_COLON + version, List.of());
      if (EmptyChecks.isNotEmpty(stepsForPipeline)) {
        // Excel 导入大批量场景:同一 pipeline 平均 5-10 step,百级 pipeline 导入时
        // 单插循环放大 5-10x;批量插入折成 1 次往返。
        Map<String, Map<String, Object>> existingStepsByCode =
            pipelineStepDefinitionMapper.selectByPipelineDefinitionId(pipelineId).stream()
                .collect(Collectors.toMap(
                    step -> String.valueOf(step.get(COL_STEP_CODE)),
                    step -> step,
                    (first, ignored) -> first));
        List<Map<String, Object>> batchStepRows = new ArrayList<>(stepsForPipeline.size());
        for (Map<String, String> step : stepsForPipeline) {
          Map<String, Object> existingStep =
              existingStepsByCode.get(normalize(step.get(COL_STEP_CODE)));
          Map<String, String> values = mergeExistingValues(
              step, existingStep, ConfigPackageExcelSchema.PipelineStep.COLUMNS);
          String stepParams = TenantConfigReferenceResolver.normalizeConfigReferences(
              normalize(values.get("step_params")));
          if (Texts.hasText(stepParams)) {
            String storedStepParams =
                EmptyChecks.isNull(existingStep) ? null : jsonText(existingStep.get("step_params"));
            stepParams = SecretMasking.preserveMaskedJson(stepParams, storedStepParams);
            rejectUnresolvedSecretMasks(stepParams);
            values.put("step_params", stepParams);
          }
          batchStepRows.add(buildStepInsertParams(pipelineId, values));
        }
        pipelineStepDefinitionMapper.upsertBatch(batchStepRows);
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
      String wfCode = normalize(row.get(COL_WORKFLOW_CODE));
      int version = Integer.parseInt(normalize(row.get(COL_VERSION)));
      WorkflowDefinitionEntity existing =
          workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
      WorkflowDefinitionUpsertParam defParam = new WorkflowDefinitionUpsertParam();
      defParam.setTenantId(ctx.tenantId());
      defParam.setWorkflowCode(wfCode);
      defParam.setWorkflowName(mergeText(
          row,
          COL_WORKFLOW_NAME,
          EmptyChecks.isNull(existing) ? null : existing.getWorkflowName()));
      defParam.setWorkflowType(mergeEnum(
          row,
          COL_WORKFLOW_TYPE,
          EmptyChecks.isNull(existing) ? null : existing.getWorkflowType()));
      defParam.setVersion(version);
      defParam.setEnabled(parseBoolean(
          row.get(COL_ENABLED), EmptyChecks.isNull(existing) || existing.getEnabled()));
      defParam.setDescription(mergeText(
          row, COL_DESCRIPTION, EmptyChecks.isNull(existing) ? null : existing.getDescription()));
      defParam.setCreatedBy(safeOp(ctx.operatorId()));
      defParam.setUpdatedBy(safeOp(ctx.operatorId()));
      workflowDefinitionMapper.upsertWorkflowDefinition(defParam);
      if (existing == null) {
        inserted++;
      } else {
        updated++;
      }

      WorkflowDefinitionEntity saved =
          workflowDefinitionMapper.selectByUniqueKey(ctx.tenantId(), wfCode, version);
      if (saved == null || saved.getId() == null) {
        continue;
      }
      Long defId = saved.getId();
      String wfKey = wfCode + KEY_SEP_COLON + version;
      applyWfNodes(ctx.tenantId(), defId, nodesByWf.getOrDefault(wfKey, List.of()));
      applyWfEdges(ctx.tenantId(), defId, edgesByWf.getOrDefault(wfKey, List.of()));
    }
    return new ApplyStats(inserted, updated);
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
      p.setRelatedPipelineCode(mergeText(
          values,
          COL_RELATED_PIPELINE_CODE,
          existing == null // empty-check: allow - Sonar S2259
              ? null
              : existing.getRelatedPipelineCode())); // empty-check: allow - Sonar S2259
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
      p.setNodeParams(TenantConfigReferenceResolver.normalizeConfigReferences(mergeNodeParams(
          values.get(COL_NODE_PARAMS),
          existing == null ? null : existing.getNodeParams()))); // empty-check: allow - Sonar S2259
      p.setEnabled(parseBoolean(
          values.get(COL_ENABLED),
          existing == null || existing.getEnabled())); // empty-check: allow - Sonar S2259
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
      p.setEnabled(parseBoolean(
          values.get(COL_ENABLED),
          existing == null || existing.getEnabled())); // empty-check: allow - Sonar S2259
      workflowEdgeMapper.upsertWorkflowEdge(p);
    }
  }

  private Map<String, Object> buildPipelineInsertParams(Map<String, String> row, ApplyContext ctx) {
    // PipelineDefinitionMapper.xml 的 insert/update 绑定是 snake_case（#{tenant_id} 等），
    // 这里 key 必须与之一致，否则 MyBatis 找不到变量 → 绑 null → 撞 NOT NULL 约束 500。
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("tenant_id", ctx.tenantId());
    p.put("job_code", normalize(row.get(COL_JOB_CODE)));
    p.put("pipeline_name", normalize(row.get(COL_PIPELINE_NAME)));
    p.put("pipeline_type", normalizeEnum(row.get(COL_PIPELINE_TYPE)));
    p.put("biz_type", normalize(row.get(COL_BIZ_TYPE)));
    p.put("worker_group", CodeNormalizer.toUpperOrNull(row.get(COL_WORKER_GROUP)));
    p.put(COL_VERSION, parseInteger(row.get(COL_VERSION)));
    p.put(COL_ENABLED, parseBoolean(row.get(COL_ENABLED), true));
    p.put(COL_DESCRIPTION, normalize(row.get(COL_DESCRIPTION)));
    p.put("created_by", safeOp(ctx.operatorId()));
    p.put("updated_by", safeOp(ctx.operatorId()));
    p.put(KEY_ID, null);
    return p;
  }

  private Map<String, Object> buildPipelineUpdateParams(
      Long id, Map<String, String> row, ApplyContext ctx) {
    Map<String, Object> p = buildPipelineInsertParams(row, ctx);
    p.put(KEY_ID, id);
    return p;
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
    values.put(COL_RELATED_PIPELINE_CODE, node.getRelatedPipelineCode());
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

  private Map<String, Object> buildStepInsertParams(Long pipelineId, Map<String, String> step) {
    // PipelineStepDefinitionMapper.xml 的 insert 绑定也是 snake_case
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("pipeline_definition_id", pipelineId);
    p.put("step_code", normalize(step.get(COL_STEP_CODE)));
    p.put("step_name", normalize(step.get(COL_STEP_NAME)));
    p.put("stage_code", normalizeEnum(step.get(COL_STAGE_CODE)));
    p.put("step_order", parseInteger(step.get("step_order")));
    p.put("impl_code", normalize(step.get("impl_code")));
    p.put("step_params", normalize(step.get("step_params")));
    p.put("timeout_seconds", parseInteger(step.get(COL_TIMEOUT_SECONDS)));
    p.put("retry_policy", normalizeEnum(step.get(COL_RETRY_POLICY)));
    p.put("retry_max_count", parseInteger(step.get(COL_RETRY_MAX_COUNT)));
    p.put(COL_ENABLED, parseBoolean(step.get(COL_ENABLED), true));
    return p;
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

  private static String jsonText(Object value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    return value instanceof Map<?, ?> || value instanceof List<?>
        ? JsonUtils.toJson(value)
        : value.toString();
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
