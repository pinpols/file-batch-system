package com.example.batch.console.infrastructure.query;

import static com.example.batch.console.infrastructure.query.ConsoleQuerySupport.*;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.i18n.LocalizedErrorRenderer;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.ContentMaskingUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.entity.FileArrivalGroupEntity;
import com.example.batch.console.domain.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.query.FileArrivalGroupQuery;
import com.example.batch.console.domain.query.FileDispatchRecordQuery;
import com.example.batch.console.domain.query.FileErrorRecordQuery;
import com.example.batch.console.domain.query.FilePipelineQuery;
import com.example.batch.console.domain.query.FileRecordQuery;
import com.example.batch.console.domain.query.FileTemplateConfigQuery;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.querymap.ConsoleFileQueryMappers;
import com.example.batch.console.web.query.FileArrivalGroupQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FileErrorRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.response.file.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.web.response.file.ConsoleFileChannelResponse;
import com.example.batch.console.web.response.file.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.web.response.file.ConsoleFileErrorRecordResponse;
import com.example.batch.console.web.response.file.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.file.ConsoleFilePipelineStepResponse;
import com.example.batch.console.web.response.file.ConsoleFileRecordResponse;
import com.example.batch.console.web.response.file.ConsoleFileTemplateResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文件相关查询子服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleFileQueryService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_TENANT_ID = "tenant_id";
  private static final String KEY_CREATED_AT = "created_at";
  private static final String KEY_UPDATED_AT = "updated_at";
  private static final String KEY_FROM_TIME = "fromTime";
  private static final String KEY_TO_TIME = "toTime";
  private static final String KEY_ID = "id";

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleFileQueryMappers fileMappers;
  private final BatchSecurityProperties batchSecurityProperties;
  private final LocalizedErrorRenderer localizedErrorRenderer;

  public PageResponse<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    FileRecordQuery query =
        new FileRecordQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getBizType() == null || request.getBizType().isBlank()
                ? request.getPipelineType()
                : request.getBizType(),
            request.getFileStatus(),
            parseLong(request.getFileId(), "fileId"),
            request.getFileName(),
            request.getTraceId(),
            parseInstant(request.getFromTime(), KEY_FROM_TIME),
            parseInstant(request.getToTime(), KEY_TO_TIME),
            pageRequest);
    List<FileRecordEntity> rows = fileMappers.fileRecordMapper.selectByQuery(query);
    long total = fileMappers.fileRecordMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toFileRecordResponse);
  }

  public PageResponse<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    FilePipelineQuery q =
        FilePipelineQuery.builder()
            .tenantId(tenantId)
            .fileId(request.getFileId())
            .pipelineInstanceId(request.getPipelineInstanceId())
            .pipelineType(request.getPipelineType())
            .runStatus(request.getRunStatus())
            .traceId(request.getTraceId())
            .fromTime(parseInstant(request.getFromTime(), KEY_FROM_TIME))
            .toTime(parseInstant(request.getToTime(), KEY_TO_TIME))
            .pageRequest(pageRequest)
            .build();
    List<Map<String, Object>> rows = fileMappers.filePipelineMapper.selectByQuery(q);
    long total = fileMappers.filePipelineMapper.countByQuery(q.withoutPage());
    return page(pageRequest, total, rows, this::toFilePipelineResponse);
  }

  public PageResponse<ConsoleFilePipelineStepResponse> filePipelineSteps(
      FilePipelineStepQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    List<Map<String, Object>> rows =
        fileMappers.filePipelineStepRunMapper.selectByQuery(
            tenantId,
            request.getPipelineInstanceId(),
            request.getStepCode(),
            request.getStageCode(),
            request.getStepStatus(),
            pageRequest);
    long total =
        fileMappers.filePipelineStepRunMapper.countByQuery(
            tenantId,
            request.getPipelineInstanceId(),
            request.getStepCode(),
            request.getStageCode(),
            request.getStepStatus());
    return page(pageRequest, total, rows, this::toFilePipelineStepResponse);
  }

  public PageResponse<ConsoleFileDispatchRecordResponse> fileDispatchRecords(
      FileDispatchRecordQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    FileDispatchRecordQuery q =
        new FileDispatchRecordQuery(
            tenantId,
            request.getFileId(),
            request.getChannelCode(),
            request.getDispatchStatus(),
            request.getReceiptStatus(),
            parseInstant(request.getFromTime(), KEY_FROM_TIME),
            parseInstant(request.getToTime(), KEY_TO_TIME),
            pageRequest);
    List<Map<String, Object>> rows = fileMappers.fileDispatchRecordMapper.selectByQuery(q);
    long total = fileMappers.fileDispatchRecordMapper.countByQuery(q.withoutPage());
    return page(pageRequest, total, rows, this::toFileDispatchRecordResponse);
  }

  public PageResponse<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<Map<String, Object>> rows =
        fileMappers.fileChannelConfigMapper.selectByQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getChannelCode(),
            request.getChannelType(),
            request.getEnabled(),
            pageRequest);
    long total =
        fileMappers.fileChannelConfigMapper.countByQuery(
            resolveTenant(tenantGuard, request.getTenantId()),
            request.getChannelCode(),
            request.getChannelType(),
            request.getEnabled());
    return page(pageRequest, total, rows, this::toFileChannelResponse);
  }

  public PageResponse<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    FileTemplateConfigQuery q =
        FileTemplateConfigQuery.builder()
            .tenantId(tenantId)
            .keyword(request.getKeyword())
            .templateCode(request.getTemplateCode())
            .templateName(request.getTemplateName())
            .templateType(request.getTemplateType())
            .bizType(request.getBizType())
            .enabled(request.getEnabled())
            .pageRequest(pageRequest)
            .build();
    List<Map<String, Object>> rows = fileMappers.fileTemplateConfigMapper.selectByQuery(q);
    long total = fileMappers.fileTemplateConfigMapper.countByQuery(q);
    return page(pageRequest, total, rows, this::toFileTemplateResponse);
  }

  public PageResponse<ConsoleFileArrivalGroupResponse> fileArrivalGroups(
      FileArrivalGroupQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<FileArrivalGroupEntity> rows =
        fileMappers.fileArrivalGroupMapper.selectByQuery(
            new FileArrivalGroupQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getFileGroupCode(),
                request.getArrivalState(),
                null,
                null,
                pageRequest));
    long total =
        fileMappers.fileArrivalGroupMapper.countByQuery(
            new FileArrivalGroupQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getFileGroupCode(),
                request.getArrivalState(),
                null,
                null,
                pageRequest));
    return page(pageRequest, total, rows, this::toFileArrivalGroupResponse);
  }

  public PageResponse<ConsoleFileErrorRecordResponse> fileErrorRecords(
      FileErrorRecordQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    List<FileErrorRecordEntity> rows =
        fileMappers.fileErrorRecordMapper.selectByQuery(
            new FileErrorRecordQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getFileId(),
                request.getErrorStage(),
                request.getErrorCode(),
                request.getSkipped(),
                pageRequest));
    applyErrorLineMasking(
        resolveTenant(tenantGuard, request.getTenantId()), request.getFileId(), rows);
    long total =
        fileMappers.fileErrorRecordMapper.countByQuery(
            new FileErrorRecordQuery(
                resolveTenant(tenantGuard, request.getTenantId()),
                request.getFileId(),
                request.getErrorStage(),
                request.getErrorCode(),
                request.getSkipped(),
                pageRequest));
    return page(pageRequest, total, rows, this::toFileErrorRecordResponse);
  }

  public Map<String, Object> fileChannelDetail(String tenantId, String channelCode) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    return requireRow(
        fileMappers.fileChannelConfigMapper.selectByUniqueKey(resolved, channelCode),
        "file channel not found: " + channelCode);
  }

  public Map<String, Object> fileTemplateDetail(
      String tenantId, String templateCode, Integer version) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    Integer ver = version != null ? version : 1;
    return requireRow(
        fileMappers.fileTemplateConfigMapper.selectByUniqueKey(resolved, templateCode, ver),
        "file template not found: " + templateCode);
  }

  public Map<String, Object> fileRecordDetail(String tenantId, Long fileId) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    return requireRow(
        fileMappers.fileRecordMapper.selectFileRecordById(resolved, fileId),
        "file record not found: " + fileId);
  }

  public ConsoleFilePipelineResponse filePipelineDetail(String tenantId, Long id) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    PageRequest pageSingle = new PageRequest(1, 1);
    FilePipelineQuery detailQuery = FilePipelineQuery.ofPipeline(resolved, id, pageSingle);
    List<Map<String, Object>> rows = fileMappers.filePipelineMapper.selectByQuery(detailQuery);
    if (rows.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.pipeline.instance_not_found", id);
    }
    return toFilePipelineResponse(rows.get(0));
  }

  private boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value == null) {
      return false;
    }
    return "true".equalsIgnoreCase(String.valueOf(value));
  }

  private void applyErrorLineMasking(
      String tenantId, Long fileId, List<FileErrorRecordEntity> rows) {
    if (batchSecurityProperties.isBypassMode()
        || rows == null
        || rows.isEmpty()
        || fileId == null
        || !Texts.hasText(tenantId)) {
      return;
    }
    String templateCode = fileMappers.fileRecordMapper.selectTemplateCodeByFileId(tenantId, fileId);
    if (!Texts.hasText(templateCode)) {
      return;
    }
    Map<String, Object> sec =
        fileMappers.fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(
            tenantId, templateCode);
    if (sec == null || !truthy(sec.get("error_line_masking_enabled"))) {
      return;
    }
    String ruleSet =
        sec.get("masking_rule_set") == null ? null : String.valueOf(sec.get("masking_rule_set"));
    for (FileErrorRecordEntity row : rows) {
      if (row.getErrorMessage() != null) {
        row.setErrorMessage(ContentMaskingUtils.maskPlainText(row.getErrorMessage(), ruleSet));
      }
      if (row.getRawRecord() != null) {
        row.setRawRecord(ContentMaskingUtils.maskPlainText(row.getRawRecord(), ruleSet));
      }
    }
  }

  private ConsoleFileRecordResponse toFileRecordResponse(FileRecordEntity entity) {
    return new ConsoleFileRecordResponse(
        entity.getId(),
        display(entity.getTenantId()),
        display(entity.getBizType()),
        display(entity.getFileName()),
        display(entity.getFileStatus()),
        entity.getBizDate(),
        display(entity.getTraceId()),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  public ConsoleFilePipelineResponse toFilePipelineResponse(Map<String, Object> row) {
    return new ConsoleFilePipelineResponse(
        longValue(row, KEY_ID),
        stringValue(row, KEY_TENANT_ID),
        longValue(row, "pipeline_definition_id"),
        stringValue(row, "job_code"),
        stringValue(row, "pipeline_type"),
        longValue(row, "file_id"),
        longValue(row, "related_job_instance_id"),
        stringValue(row, "current_stage"),
        stringValue(row, "last_success_stage"),
        stringValue(row, "run_status"),
        stringValue(row, "trace_id"),
        instantValue(row, "started_at"),
        instantValue(row, "finished_at"),
        instantValue(row, KEY_CREATED_AT),
        instantValue(row, KEY_UPDATED_AT));
  }

  private ConsoleFilePipelineStepResponse toFilePipelineStepResponse(Map<String, Object> row) {
    return new ConsoleFilePipelineStepResponse(
        longValue(row, KEY_ID),
        longValue(row, "pipeline_instance_id"),
        stringValue(row, "step_code"),
        stringValue(row, "stage_code"),
        intValue(row, "run_seq"),
        stringValue(row, "step_status"),
        stringValue(row, "input_summary"),
        stringValue(row, "output_summary"),
        stringValue(row, "error_code"),
        renderLocalizedError(row),
        intValue(row, "retry_count"),
        longValue(row, "duration_ms"),
        instantValue(row, "started_at"),
        instantValue(row, "finished_at"));
  }

  private ConsoleFileDispatchRecordResponse toFileDispatchRecordResponse(Map<String, Object> row) {
    return new ConsoleFileDispatchRecordResponse(
        longValue(row, KEY_ID),
        stringValue(row, KEY_TENANT_ID),
        longValue(row, "file_id"),
        longValue(row, "pipeline_instance_id"),
        stringValue(row, "channel_code"),
        stringValue(row, "dispatch_target"),
        stringValue(row, "dispatch_status"),
        intValue(row, "dispatch_attempt"),
        stringValue(row, "receipt_code"),
        stringValue(row, "receipt_status"),
        stringValue(row, "external_request_id"),
        stringValue(row, "error_code"),
        renderLocalizedError(row),
        instantValue(row, "dispatched_at"),
        instantValue(row, "ack_at"),
        instantValue(row, KEY_CREATED_AT),
        instantValue(row, KEY_UPDATED_AT));
  }

  /**
   * i18n 持久化:行有 error_key 时按当前 Locale 重渲染,否则透传 error_message。 用于所有 resultType="map" 行携带
   * (error_key/error_args/error_message) 三元组的场景。
   */
  private String renderLocalizedError(Map<String, Object> row) {
    return localizedErrorRenderer.render(
        stringValue(row, "error_key"),
        stringValue(row, "error_args"),
        stringValue(row, "error_message"));
  }

  private ConsoleFileChannelResponse toFileChannelResponse(Map<String, Object> row) {
    return new ConsoleFileChannelResponse(
        longValue(row, KEY_ID),
        stringValue(row, KEY_TENANT_ID),
        stringValue(row, "channel_code"),
        stringValue(row, "channel_name"),
        stringValue(row, "channel_type"),
        stringValue(row, "target_endpoint"),
        stringValue(row, "auth_type"),
        stringValue(row, "config_json"),
        stringValue(row, "receipt_policy"),
        intValue(row, "timeout_seconds"),
        booleanValue(row, "enabled"),
        instantValue(row, KEY_CREATED_AT),
        instantValue(row, KEY_UPDATED_AT));
  }

  private ConsoleFileTemplateResponse toFileTemplateResponse(Map<String, Object> row) {
    return new ConsoleFileTemplateResponse(
        longValue(row, KEY_ID),
        stringValue(row, KEY_TENANT_ID),
        stringValue(row, "template_code"),
        stringValue(row, "template_name"),
        stringValue(row, "template_type"),
        stringValue(row, "biz_type"),
        stringValue(row, "file_format_type"),
        stringValue(row, "charset"),
        stringValue(row, "target_charset"),
        booleanValue(row, "with_bom"),
        stringValue(row, "line_separator"),
        stringValue(row, "delimiter"),
        stringValue(row, "quote_char"),
        stringValue(row, "escape_char"),
        intValue(row, "record_length"),
        intValue(row, "header_rows"),
        intValue(row, "footer_rows"),
        stringValue(row, "header_template"),
        stringValue(row, "trailer_template"),
        stringValue(row, "checksum_type"),
        stringValue(row, "compress_type"),
        stringValue(row, "encrypt_type"),
        stringValue(row, "naming_rule"),
        stringValue(row, "field_mappings"),
        stringValue(row, "validation_rule_set"),
        stringValue(row, "default_query_code"),
        stringValue(row, "default_query_sql"),
        stringValue(row, "query_param_schema"),
        booleanValue(row, "streaming_enabled"),
        intValue(row, "page_size"),
        intValue(row, "fetch_size"),
        intValue(row, "chunk_size"),
        booleanValue(row, "preview_masking_enabled"),
        booleanValue(row, "error_line_masking_enabled"),
        booleanValue(row, "log_masking_enabled"),
        booleanValue(row, "content_encryption_enabled"),
        stringValue(row, "encryption_key_ref"),
        booleanValue(row, "download_requires_approval"),
        stringValue(row, "masking_rule_set"),
        booleanValue(row, "enabled"),
        intValue(row, "version"),
        stringValue(row, "description"),
        stringValue(row, "created_by"),
        stringValue(row, "updated_by"),
        instantValue(row, KEY_CREATED_AT),
        instantValue(row, KEY_UPDATED_AT));
  }

  private ConsoleFileArrivalGroupResponse toFileArrivalGroupResponse(
      FileArrivalGroupEntity entity) {
    return new ConsoleFileArrivalGroupResponse(
        display(entity.getTenantId()),
        display(entity.getFileGroupCode()),
        display(entity.getWaitFileGroupMode()),
        display(entity.getRequiredFileSet()),
        display(entity.getArrivalTimeoutAction()),
        display(entity.getArrivalState()),
        entity.getExpectedArrivalTime(),
        entity.getLatestTolerableTime(),
        entity.getArrivedCount(),
        entity.getTriggeredCount(),
        entity.getTimeoutCount(),
        entity.getWaitingCount(),
        entity.getLastUpdatedAt());
  }

  private ConsoleFileErrorRecordResponse toFileErrorRecordResponse(FileErrorRecordEntity entity) {
    String errorMessage = localizedErrorRenderer.render(entity);
    return new ConsoleFileErrorRecordResponse(
        entity.getId(),
        display(entity.getTenantId()),
        entity.getFileId(),
        entity.getPipelineInstanceId(),
        entity.getPipelineStepRunId(),
        entity.getRecordNo(),
        display(entity.getErrorCode()),
        display(errorMessage),
        display(entity.getErrorStage()),
        entity.getSkipped(),
        display(entity.getSkipAction()),
        display(entity.getRawRecord()),
        entity.getCreatedAt());
  }
}
