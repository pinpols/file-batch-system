package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.normalize;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.normalizeEnum;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.parseBoolean;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.parseInteger;
import static io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelValueSupport.safeOp;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.file.param.FileChannelConfigUpsertParam;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
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
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.FileTemplateExcelRowParser;
import io.github.pinpols.batch.console.infrastructure.excel.FileTemplateExcelRowParser.TemplateRow;
import io.github.pinpols.batch.console.infrastructure.excel.ResourceQueueExcelRowParser;
import io.github.pinpols.batch.console.infrastructure.excel.ResourceQueueExcelRowParser.QueueRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 租户配置包 Excel apply 阶段的写库逻辑（从 {@link DefaultConsoleTenantConfigPackageExcelApplicationService}
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
      if (!issues.isEmpty()) {
        throw invalidParsedRow(RESOURCE_QUEUE_SHEET, issues);
      }
      Map<String, Object> existing =
          resourceQueueMapper.selectByUniqueKey(ctx.tenantId(), queue.queueCode());
      resourceQueueMapper.upsertResourceQueue(
          ResourceQueueExcelRowParser.toUpsertParam(queue, ctx.operatorId()));
      if (existing == null || existing.isEmpty()) {
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
      if (!issues.isEmpty()) {
        throw invalidParsedRow(BUSINESS_CALENDAR_SHEET, issues);
      }
      Map<String, Object> existing = businessCalendarMapper.selectActiveByTenantAndCalendarCode(
          ctx.tenantId(), calendar.calendarCode());
      businessCalendarMapper.upsertBusinessCalendar(
          BusinessCalendarExcelRowParser.toUpsertParam(calendar, safeOp(ctx.operatorId())));
      applyCalendarHolidays(ctx.tenantId(), calendar);
      if (existing == null || existing.isEmpty()) {
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
      if (!issues.isEmpty()) {
        throw invalidParsedRow(BATCH_WINDOW_SHEET, issues);
      }
      Map<String, Object> existing =
          batchWindowMapper.selectByUniqueKey(ctx.tenantId(), window.windowCode());
      batchWindowMapper.upsertBatchWindow(BatchWindowExcelRowParser.toUpsertParam(window));
      if (existing == null || existing.isEmpty()) {
        inserted++;
      } else {
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
  }

  private void applyCalendarHolidays(String tenantId, CalendarRow calendar) {
    Map<String, Object> saved = businessCalendarMapper.selectActiveByTenantAndCalendarCode(
        tenantId, calendar.calendarCode());
    if (saved == null || saved.get(KEY_ID) == null) {
      return;
    }
    Long calendarId = ((Number) saved.get(KEY_ID)).longValue();
    calendarHolidayMapper.deleteByCalendarId(calendarId);
    if (calendar.holidays() == null || calendar.holidays().isEmpty()) {
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
    calendarHolidayMapper.batchInsert(params);
  }

  private static BizException invalidParsedRow(String sheetName, List<String> issues) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT,
        "error.common.invalid_argument_detail",
        "invalid " + sheetName + " row: " + issues);
  }

  private ApplyStats applyJobs(List<Map<String, String>> rows, ApplyContext ctx) {
    int inserted = 0;
    int updated = 0;
    for (Map<String, String> row : rows) {
      String jobCode = normalize(row.get(COL_JOB_CODE));
      JobDefinitionEntity existing = jobDefinitionMapper.selectByUniqueKey(ctx.tenantId(), jobCode);
      if (existing == null) {
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
        entity.setParamSchema(normalize(row.get(COL_PARAM_SCHEMA)));
        entity.setDefaultParams(normalize(row.get(COL_DEFAULT_PARAMS)));
        entity.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
        entity.setDescription(normalize(row.get(COL_DESCRIPTION)));
        entity.setCreatedBy(safeOp(ctx.operatorId()));
        entity.setUpdatedBy(safeOp(ctx.operatorId()));
        jobDefinitionMapper.insert(entity);
        inserted++;
      } else {
        JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
        param.setTenantId(ctx.tenantId());
        param.setJobCode(jobCode);
        param.setJobName(normalize(row.get(COL_JOB_NAME)));
        param.setQueueCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_QUEUE_CODE)));
        param.setWorkerGroup(CodeNormalizer.toUpperOrNull(row.get(COL_WORKER_GROUP)));
        param.setScheduleExpr(normalize(row.get(COL_SCHEDULE_EXPR)));
        param.setDependsOnJobCode(
            row.containsKey(COL_DEPENDS_ON_JOB_CODE)
                ? normalize(row.get(COL_DEPENDS_ON_JOB_CODE))
                : existing.getDependsOnJobCode());
        param.setCalendarCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_CALENDAR_CODE)));
        param.setWindowCode(CodeNormalizer.toConfigFormOrNull(row.get(COL_WINDOW_CODE)));
        param.setRetryPolicy(normalizeEnum(row.get(COL_RETRY_POLICY)));
        param.setRetryMaxCount(parseInteger(row.get(COL_RETRY_MAX_COUNT)));
        param.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
        param.setShardStrategy(normalizeEnum(row.get(COL_SHARD_STRATEGY)));
        param.setExecutionMode(resolveExecutionMode(row));
        param.setWatermarkField(normalize(row.get(COL_WATERMARK_FIELD)));
        param.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
        param.setDescription(normalize(row.get(COL_DESCRIPTION)));
        param.setUpdatedBy(safeOp(ctx.operatorId()));
        jobDefinitionMapper.updateJobDefinitionMaintenance(param);
        updated++;
      }
    }
    return new ApplyStats(inserted, updated);
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
      FileChannelConfigUpsertParam param = new FileChannelConfigUpsertParam();
      param.setTenantId(ctx.tenantId());
      param.setChannelCode(code);
      param.setChannelName(normalize(row.get(COL_CHANNEL_NAME)));
      param.setChannelType(normalizeEnum(row.get(COL_CHANNEL_TYPE)));
      param.setTargetEndpoint(normalize(row.get("target_endpoint")));
      param.setAuthType(normalizeEnum(row.get(COL_AUTH_TYPE)));
      param.setConfigJson(row.get(COL_CONFIG_JSON));
      param.setReceiptPolicy(normalizeEnum(row.get(COL_RECEIPT_POLICY)));
      param.setTimeoutSeconds(parseInteger(row.get(COL_TIMEOUT_SECONDS)));
      param.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
      param.setCreatedBy(safeOp(ctx.operatorId()));
      param.setUpdatedBy(safeOp(ctx.operatorId()));
      fileChannelConfigMapper.upsertFileChannelConfig(param);
      if (existing == null || existing.isEmpty()) {
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
      if (!issues.isEmpty()) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            "error.common.invalid_argument_detail",
            "invalid file_template_config row: " + issues);
      }
      Map<String, Object> existing = fileTemplateConfigMapper.selectByUniqueKey(
          ctx.tenantId(), template.templateCode(), template.version());
      fileTemplateConfigMapper.upsertFileTemplateConfig(
          FileTemplateExcelRowParser.toUpsertParam(ctx.tenantId(), template, ctx.operatorId()));
      if (existing == null || existing.isEmpty()) {
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
      if (existing == null || existing.isEmpty()) {
        Map<String, Object> params = buildPipelineInsertParams(row, ctx);
        pipelineDefinitionMapper.insert(params);
        pipelineId = ((Number) params.get(KEY_ID)).longValue();
        inserted++;
      } else {
        pipelineId = ((Number) existing.get(KEY_ID)).longValue();
        pipelineDefinitionMapper.update(buildPipelineUpdateParams(pipelineId, row, ctx));
        updated++;
      }
      pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(pipelineId);
      List<Map<String, String>> stepsForPipeline =
          stepsByKey.getOrDefault(jobCode + KEY_SEP_COLON + version, List.of());
      if (!stepsForPipeline.isEmpty()) {
        // Excel 导入大批量场景:同一 pipeline 平均 5-10 step,百级 pipeline 导入时
        // 单插循环放大 5-10x;批量插入折成 1 次往返。
        List<Map<String, Object>> batchStepRows = new ArrayList<>(stepsForPipeline.size());
        for (Map<String, String> step : stepsForPipeline) {
          batchStepRows.add(buildStepInsertParams(pipelineId, step));
        }
        pipelineStepDefinitionMapper.insertBatch(batchStepRows);
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
      defParam.setWorkflowName(normalize(row.get(COL_WORKFLOW_NAME)));
      defParam.setWorkflowType(normalizeEnum(row.get(COL_WORKFLOW_TYPE)));
      defParam.setVersion(version);
      defParam.setEnabled(parseBoolean(row.get(COL_ENABLED), true));
      defParam.setDescription(normalize(row.get(COL_DESCRIPTION)));
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
      WorkflowNodeUpsertParam p = new WorkflowNodeUpsertParam();
      p.setTenantId(tenantId);
      p.setWorkflowDefinitionId(defId);
      p.setNodeCode(normalize(node.get(COL_NODE_CODE)));
      p.setNodeName(normalize(node.get(COL_NODE_NAME)));
      p.setNodeType(normalizeEnum(node.get(COL_NODE_TYPE)));
      p.setRelatedJobCode(normalize(node.get(COL_RELATED_JOB_CODE)));
      p.setRelatedPipelineCode(normalize(node.get(COL_RELATED_PIPELINE_CODE)));
      p.setWorkerGroup(CodeNormalizer.toUpperOrNull(node.get(COL_WORKER_GROUP)));
      p.setWindowCode(CodeNormalizer.toConfigFormOrNull(node.get(COL_WINDOW_CODE)));
      p.setNodeOrder(parseInteger(node.get(COL_NODE_ORDER)));
      p.setRetryPolicy(normalizeEnum(node.get(COL_RETRY_POLICY)));
      p.setRetryMaxCount(parseInteger(node.get(COL_RETRY_MAX_COUNT)));
      p.setTimeoutSeconds(parseInteger(node.get(COL_TIMEOUT_SECONDS)));
      p.setNodeParams(normalize(node.get(COL_NODE_PARAMS)));
      p.setEnabled(parseBoolean(node.get(COL_ENABLED), true));
      workflowNodeMapper.upsertWorkflowNode(p);
    }
  }

  private void applyWfEdges(String tenantId, Long defId, List<Map<String, String>> edges) {
    for (Map<String, String> edge : edges) {
      WorkflowEdgeUpsertParam p = new WorkflowEdgeUpsertParam();
      p.setTenantId(tenantId);
      p.setWorkflowDefinitionId(defId);
      p.setFromNodeCode(normalize(edge.get(COL_FROM_NODE_CODE)));
      p.setToNodeCode(normalize(edge.get(COL_TO_NODE_CODE)));
      p.setEdgeType(normalizeEnum(edge.get(COL_EDGE_TYPE)));
      p.setConditionExpr(normalize(edge.get(COL_CONDITION_EXPR)));
      p.setEnabled(parseBoolean(edge.get(COL_ENABLED), true));
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

  private record ApplyStats(int inserted, int updated) {}
}
