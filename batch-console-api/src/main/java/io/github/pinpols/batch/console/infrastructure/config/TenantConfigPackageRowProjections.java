package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_CONFIG_JSON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_DEPENDS_ON_JOB_CODES;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.FileChannel.COL_TARGET_ENDPOINT;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_BIZ_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CALENDAR_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_COMPLETION_DEADLINE_DAY_OFFSET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_COMPLETION_DEADLINE_LOCAL_TIME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_COMPLETION_DEADLINE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONDITION_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEFAULT_PARAMS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEPENDS_ON_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DESCRIPTION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EDGE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_ENABLED;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EXECUTION_HANDLER;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EXECUTION_MODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_FROM_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_NAME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_ORDER;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_PARAMS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PARAM_SCHEMA;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_QUEUE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RELATED_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_MAX_COUNT;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_POLICY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_EXPR;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_TYPE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SHARD_STRATEGY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SOFT_RUNTIME_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SOFT_RUNTIME_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_START_GRACE_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_START_GRACE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TENANT_ID;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TIMEOUT_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TO_NODE_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_VERSION;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WATERMARK_FIELD;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WINDOW_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKER_GROUP;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_VERSION;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.job.view.JobDependencyRuleView;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: 把租户配置包导出时各 entity → Map row 的投影逻辑集中在一处。
 *
 * <p>覆盖原 service ~125 行 toJobRows / toWfDefRows / collectPipelineSteps / collectWorkflowNodes /
 * collectWorkflowEdges。投影后的 Map 直接喂给 {@link ConfigPackageExcelWorkbookWriter} 写盘。
 */
@Component
@RequiredArgsConstructor
public class TenantConfigPackageRowProjections {

  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;

  List<Map<String, Object>> toJobRows(
      List<JobDefinitionView> entities, boolean includePlatformFields) {
    return entities.stream()
        .map(e -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put(COL_TENANT_ID, e.getTenantId());
          m.put(COL_JOB_CODE, e.getJobCode());
          m.put(COL_JOB_NAME, e.getJobName());
          m.put(COL_JOB_TYPE, e.getJobType());
          m.put(COL_BIZ_TYPE, e.getBizType());
          m.put(COL_QUEUE_CODE, includePlatformFields ? e.getQueueCode() : null);
          m.put(COL_WORKER_GROUP, includePlatformFields ? e.getWorkerGroup() : null);
          m.put(COL_SCHEDULE_TYPE, e.getScheduleType());
          m.put(COL_SCHEDULE_EXPR, e.getScheduleExpr());
          m.put(COL_DEPENDS_ON_JOB_CODE, e.getDependsOnJobCode());
          m.put(
              COL_DEPENDS_ON_JOB_CODES,
              EmptyChecks.isNull(e.getDependsOnJobCodesCsv())
                  ? e.getDependsOnJobCode()
                  : e.getDependsOnJobCodesCsv().replace('\u001f', ','));
          m.put(COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS, dependencyBizDateAlignments(e));
          m.put(COL_CALENDAR_CODE, e.getCalendarCode());
          m.put(COL_WINDOW_CODE, e.getWindowCode());
          m.put(COL_RETRY_POLICY, e.getRetryPolicy());
          m.put(COL_RETRY_MAX_COUNT, e.getRetryMaxCount());
          m.put(COL_TIMEOUT_SECONDS, e.getTimeoutSeconds());
          m.put(COL_SHARD_STRATEGY, e.getShardStrategy());
          m.put(COL_EXECUTION_MODE, e.getExecutionMode());
          m.put(COL_WATERMARK_FIELD, e.getWatermarkField());
          m.put(COL_EXECUTION_HANDLER, e.getExecutionHandler());
          m.put(COL_PARAM_SCHEMA, e.getParamSchema());
          String maskedDefaultParams = SecretMasking.maskSensitiveJson(e.getDefaultParams());
          m.put(COL_DEFAULT_PARAMS, maskedDefaultParams);
          m.put(COL_ENABLED, e.getEnabled());
          m.put(COL_DESCRIPTION, e.getDescription());
          return m;
        })
        .collect(Collectors.toList());
  }

  private String dependencyBizDateAlignments(JobDefinitionView entity) {
    List<JobDependencyRuleView> dependencyRules = entity.getJobDependencyRules();
    boolean dependencyRulesAreMissing =
        EmptyChecks.isNull(dependencyRules) || EmptyChecks.isEmpty(dependencyRules);
    if (dependencyRulesAreMissing) {
      return Texts.hasText(entity.getDependsOnJobCode()) ? "SAME_DAY" : null;
    }
    return dependencyRules.stream()
        .map(rule -> rule.bizDateAlignment())
        .collect(Collectors.joining(","));
  }

  List<Map<String, Object>> toJobMonitoringPolicyRows(
      List<JobDefinitionView> entities, boolean includePlatformFields) {
    return entities.stream()
        .map(entity -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put(COL_TENANT_ID, entity.getTenantId());
          row.put(COL_JOB_CODE, entity.getJobCode());
          row.put(
              COL_SOFT_RUNTIME_SECONDS,
              includePlatformFields ? entity.getSoftRuntimeSeconds() : null);
          row.put(
              COL_SOFT_RUNTIME_SEVERITY,
              includePlatformFields ? entity.getSoftRuntimeSeverity() : null);
          row.put(
              COL_START_GRACE_SECONDS,
              includePlatformFields ? entity.getStartGraceSeconds() : null);
          row.put(
              COL_START_GRACE_SEVERITY,
              includePlatformFields ? entity.getStartGraceSeverity() : null);
          row.put(COL_COMPLETION_DEADLINE_LOCAL_TIME, entity.getCompletionDeadlineLocalTime());
          row.put(COL_COMPLETION_DEADLINE_DAY_OFFSET, entity.getCompletionDeadlineDayOffset());
          row.put(
              COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS,
              entity.getDependencyCompletionWindowSeconds());
          row.put(COL_COMPLETION_DEADLINE_SEVERITY, entity.getCompletionDeadlineSeverity());
          return row;
        })
        .toList();
  }

  List<Map<String, Object>> withoutPlatformWorkerGroups(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(row -> {
          Map<String, Object> projected = new LinkedHashMap<>(row);
          projected.put(COL_WORKER_GROUP, null);
          return projected;
        })
        .toList();
  }

  List<Map<String, Object>> toExportableChannelRows(List<FileChannelConfigQueryView> channels) {
    return channels.stream()
        .map(channel -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", channel.id());
          row.put("tenant_id", channel.tenantId());
          row.put("channel_code", channel.channelCode());
          row.put("channel_name", channel.channelName());
          row.put("channel_type", channel.channelType());
          row.put(COL_TARGET_ENDPOINT, "");
          row.put("auth_type", channel.authType());
          row.put(COL_CONFIG_JSON, "");
          row.put("receipt_policy", channel.receiptPolicy());
          row.put("timeout_seconds", channel.timeoutSeconds());
          row.put("enabled", channel.enabled());
          row.put("created_at", channel.createdAt());
          row.put("updated_at", channel.updatedAt());
          return row;
        })
        .toList();
  }

  List<Map<String, Object>> toExcelFileTemplateRows(List<FileTemplateConfigQueryView> templates) {
    return templates.stream()
        .map(template -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", template.id());
          row.put("tenant_id", template.tenantId());
          row.put("template_code", template.templateCode());
          row.put("template_name", template.templateName());
          row.put("template_type", template.templateType());
          row.put("biz_type", template.bizType());
          row.put("file_format_type", template.fileFormatType());
          row.put("charset", template.charset());
          row.put("target_charset", template.targetCharset());
          row.put("with_bom", template.withBom());
          row.put("line_separator", template.lineSeparator());
          row.put("delimiter", template.delimiter());
          row.put("quote_char", template.quoteChar());
          row.put("escape_char", template.escapeChar());
          row.put("record_length", template.recordLength());
          row.put("header_rows", template.headerRows());
          row.put("footer_rows", template.footerRows());
          row.put("header_template", template.headerTemplate());
          row.put("trailer_template", template.trailerTemplate());
          row.put("checksum_type", template.checksumType());
          row.put("compress_type", template.compressType());
          row.put("encrypt_type", template.encryptType());
          row.put("naming_rule", template.namingRule());
          row.put("field_mappings", template.fieldMappings());
          row.put("validation_rule_set", template.validationRuleSet());
          row.put("default_query_code", template.defaultQueryCode());
          row.put("default_query_sql", template.defaultQuerySql());
          row.put("query_param_schema", template.queryParamSchema());
          row.put("streaming_enabled", template.streamingEnabled());
          row.put("page_size", template.pageSize());
          row.put("fetch_size", template.fetchSize());
          row.put("chunk_size", template.chunkSize());
          row.put("preview_masking_enabled", template.previewMaskingEnabled());
          row.put("error_line_masking_enabled", template.errorLineMaskingEnabled());
          row.put("log_masking_enabled", template.logMaskingEnabled());
          row.put("content_encryption_enabled", template.contentEncryptionEnabled());
          row.put("encryption_key_ref", template.encryptionKeyRef());
          row.put("download_requires_approval", template.downloadRequiresApproval());
          row.put("masking_rule_set", template.maskingRuleSet());
          row.put("load_target_ref", template.loadTargetRef());
          row.put("export_data_ref", template.exportDataRef());
          row.put("enabled", template.enabled());
          row.put("version", template.version());
          row.put("description", template.description());
          row.put("created_by", template.createdBy());
          row.put("updated_by", template.updatedBy());
          row.put("created_at", template.createdAt());
          row.put("updated_at", template.updatedAt());
          return row;
        })
        .toList();
  }

  List<Map<String, Object>> toWfDefRows(
      List<WorkflowDefinitionEntity> entities, Map<Long, String> jobCodeById) {
    return entities.stream()
        .map(e -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put(COL_TENANT_ID, e.getTenantId());
          m.put(COL_WORKFLOW_CODE, e.getWorkflowCode());
          m.put("workflow_name", e.getWorkflowName());
          m.put("workflow_type", e.getWorkflowType());
          m.put(COL_VERSION, e.getVersion());
          m.put(
              ConfigPackageExcelSchema.COL_SINGLE_JOB_CODE,
              jobCodeById.get(e.getSingleJobDefinitionId()));
          m.put(COL_ENABLED, e.getEnabled());
          m.put(ConfigPackageExcelSchema.COL_SCHEDULE_ENABLED, e.getScheduleEnabled());
          m.put(COL_SCHEDULE_TYPE, e.getScheduleType());
          m.put(COL_SCHEDULE_EXPR, e.getScheduleExpr());
          m.put(ConfigPackageExcelSchema.COL_TIMEZONE, e.getTimezone());
          m.put(ConfigPackageExcelSchema.COL_TRIGGER_MODE, e.getTriggerMode());
          m.put(COL_CALENDAR_CODE, e.getCalendarCode());
          m.put(COL_DEPENDS_ON_JOB_CODE, e.getDependsOnJobCode());
          m.put(COL_DESCRIPTION, e.getDescription());
          return m;
        })
        .collect(Collectors.toList());
  }

  List<Map<String, Object>> collectWorkflowNodes(
      String tenantId, List<WorkflowDefinitionEntity> defs) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (WorkflowDefinitionEntity def : defs) {
      WorkflowNodeQuery nodeQuery = WorkflowNodeQuery.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(def.getId())
          .workflowCode(def.getWorkflowCode())
          .build();
      List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByQuery(nodeQuery);
      for (WorkflowNodeEntity node : nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(COL_TENANT_ID, tenantId);
        m.put(COL_WORKFLOW_CODE, def.getWorkflowCode());
        m.put(COL_WORKFLOW_VERSION, def.getVersion());
        m.put(COL_NODE_CODE, node.getNodeCode());
        m.put(COL_NODE_NAME, node.getNodeName());
        m.put(COL_NODE_TYPE, node.getNodeType());
        m.put(COL_RELATED_JOB_CODE, node.getRelatedJobCode());
        m.put(COL_WORKER_GROUP, node.getWorkerGroup());
        m.put(COL_WINDOW_CODE, node.getWindowCode());
        m.put(COL_NODE_ORDER, node.getNodeOrder());
        m.put(COL_RETRY_POLICY, node.getRetryPolicy());
        m.put(COL_RETRY_MAX_COUNT, node.getRetryMaxCount());
        m.put(COL_TIMEOUT_SECONDS, node.getTimeoutSeconds());
        String maskedNodeParams = SecretMasking.maskSensitiveJson(node.getNodeParams());
        m.put(COL_NODE_PARAMS, maskedNodeParams);
        m.put(COL_ENABLED, node.getEnabled());
        result.add(m);
      }
    }
    return result;
  }

  List<Map<String, Object>> collectWorkflowEdges(
      String tenantId, List<WorkflowDefinitionEntity> defs) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (WorkflowDefinitionEntity def : defs) {
      WorkflowEdgeQuery edgeQuery = WorkflowEdgeQuery.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(def.getId())
          .workflowCode(def.getWorkflowCode())
          .build();
      List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectByQuery(edgeQuery);
      for (WorkflowEdgeEntity edge : edges) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(COL_TENANT_ID, tenantId);
        m.put(COL_WORKFLOW_CODE, def.getWorkflowCode());
        m.put(COL_WORKFLOW_VERSION, def.getVersion());
        m.put(COL_FROM_NODE_CODE, edge.getFromNodeCode());
        m.put(COL_TO_NODE_CODE, edge.getToNodeCode());
        m.put(COL_EDGE_TYPE, edge.getEdgeType());
        m.put(COL_CONDITION_EXPR, edge.getConditionExpr());
        m.put(COL_ENABLED, edge.getEnabled());
        result.add(m);
      }
    }
    return result;
  }
}
