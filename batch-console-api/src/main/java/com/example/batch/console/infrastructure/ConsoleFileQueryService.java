package com.example.batch.console.infrastructure;

import static com.example.batch.console.infrastructure.ConsoleQuerySupport.*;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.ContentMaskingUtils;
import com.example.batch.console.domain.entity.FileArrivalGroupEntity;
import com.example.batch.console.domain.entity.FileErrorRecordEntity;
import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.query.FileArrivalGroupQuery;
import com.example.batch.console.domain.query.FileErrorRecordQuery;
import com.example.batch.console.domain.query.FileRecordQuery;
import com.example.batch.console.support.ConsoleFileQueryMappers;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.query.FileArrivalGroupQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FileErrorRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.response.ConsoleFileArrivalGroupResponse;
import com.example.batch.console.web.response.ConsoleFileChannelResponse;
import com.example.batch.console.web.response.ConsoleFileDispatchRecordResponse;
import com.example.batch.console.web.response.ConsoleFileErrorRecordResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineResponse;
import com.example.batch.console.web.response.ConsoleFilePipelineStepResponse;
import com.example.batch.console.web.response.ConsoleFileRecordResponse;
import com.example.batch.console.web.response.ConsoleFileTemplateResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/** 文件相关查询子服务。 */
@Service
@RequiredArgsConstructor
class ConsoleFileQueryService {

    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleFileQueryMappers fileMappers;
    private final BatchSecurityProperties batchSecurityProperties;

    PageResponse<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request) {
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
                        parseInstant(request.getFromTime(), "fromTime"),
                        parseInstant(request.getToTime(), "toTime"),
                        pageRequest);
        List<FileRecordEntity> rows = fileMappers.fileRecordMapper.selectByQuery(query);
        long total = fileMappers.fileRecordMapper.countByQuery(query);
        return page(pageRequest, total, rows, this::toFileRecordResponse);
    }

    PageResponse<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        List<Map<String, Object>> rows =
                fileMappers.filePipelineMapper.selectByQuery(
                        resolveTenant(tenantGuard, request.getTenantId()),
                        request.getFileId(),
                        request.getPipelineInstanceId(),
                        request.getPipelineType(),
                        request.getRunStatus(),
                        request.getTraceId(),
                        parseInstant(request.getFromTime(), "fromTime"),
                        parseInstant(request.getToTime(), "toTime"),
                        pageRequest);
        long total =
                fileMappers.filePipelineMapper.countByQuery(
                        resolveTenant(tenantGuard, request.getTenantId()),
                        request.getFileId(),
                        request.getPipelineInstanceId(),
                        request.getPipelineType(),
                        request.getRunStatus(),
                        request.getTraceId(),
                        parseInstant(request.getFromTime(), "fromTime"),
                        parseInstant(request.getToTime(), "toTime"));
        return page(pageRequest, total, rows, this::toFilePipelineResponse);
    }

    PageResponse<ConsoleFilePipelineStepResponse> filePipelineSteps(
            FilePipelineStepQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        List<Map<String, Object>> rows =
                fileMappers.filePipelineStepRunMapper.selectByQuery(
                        request.getPipelineInstanceId(),
                        request.getStepCode(),
                        request.getStageCode(),
                        request.getStepStatus(),
                        pageRequest);
        long total =
                fileMappers.filePipelineStepRunMapper.countByQuery(
                        request.getPipelineInstanceId(),
                        request.getStepCode(),
                        request.getStageCode(),
                        request.getStepStatus());
        return page(pageRequest, total, rows, this::toFilePipelineStepResponse);
    }

    PageResponse<ConsoleFileDispatchRecordResponse> fileDispatchRecords(
            FileDispatchRecordQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        List<Map<String, Object>> rows =
                fileMappers.fileDispatchRecordMapper.selectByQuery(
                        resolveTenant(tenantGuard, request.getTenantId()),
                        request.getFileId(),
                        request.getChannelCode(),
                        request.getDispatchStatus(),
                        request.getReceiptStatus(),
                        parseInstant(request.getFromTime(), "fromTime"),
                        parseInstant(request.getToTime(), "toTime"),
                        pageRequest);
        long total =
                fileMappers.fileDispatchRecordMapper.countByQuery(
                        resolveTenant(tenantGuard, request.getTenantId()),
                        request.getFileId(),
                        request.getChannelCode(),
                        request.getDispatchStatus(),
                        request.getReceiptStatus(),
                        parseInstant(request.getFromTime(), "fromTime"),
                        parseInstant(request.getToTime(), "toTime"));
        return page(pageRequest, total, rows, this::toFileDispatchRecordResponse);
    }

    PageResponse<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request) {
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

    PageResponse<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request) {
        PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
        List<Map<String, Object>> rows =
                fileMappers.fileTemplateConfigMapper.selectByQuery(
                        resolveTenant(tenantGuard, request.getTenantId()),
                        request.getKeyword(),
                        request.getTemplateCode(),
                        request.getTemplateName(),
                        request.getTemplateType(),
                        request.getBizType(),
                        request.getEnabled(),
                        pageRequest);
        long total =
                fileMappers.fileTemplateConfigMapper.countByQuery(
                        resolveTenant(tenantGuard, request.getTenantId()),
                        request.getKeyword(),
                        request.getTemplateCode(),
                        request.getTemplateName(),
                        request.getTemplateType(),
                        request.getBizType(),
                        request.getEnabled());
        return page(pageRequest, total, rows, this::toFileTemplateResponse);
    }

    PageResponse<ConsoleFileArrivalGroupResponse> fileArrivalGroups(
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

    PageResponse<ConsoleFileErrorRecordResponse> fileErrorRecords(
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

    Map<String, Object> fileChannelDetail(String tenantId, String channelCode) {
        String resolved = resolveTenant(tenantGuard, tenantId);
        Map<String, Object> row =
                fileMappers.fileChannelConfigMapper.selectByUniqueKey(resolved, channelCode);
        if (row == null || row.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "file channel not found: " + channelCode);
        }
        return row;
    }

    Map<String, Object> fileTemplateDetail(String tenantId, String templateCode, Integer version) {
        String resolved = resolveTenant(tenantGuard, tenantId);
        Integer ver = version != null ? version : 1;
        Map<String, Object> row =
                fileMappers.fileTemplateConfigMapper.selectByUniqueKey(resolved, templateCode, ver);
        if (row == null || row.isEmpty()) {
            throw new BizException(
                    ResultCode.NOT_FOUND, "file template not found: " + templateCode);
        }
        return row;
    }

    Map<String, Object> fileRecordDetail(String tenantId, Long fileId) {
        String resolved = resolveTenant(tenantGuard, tenantId);
        Map<String, Object> row =
                fileMappers.fileRecordMapper.selectFileRecordById(resolved, fileId);
        if (row == null || row.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "file record not found: " + fileId);
        }
        return row;
    }

    ConsoleFilePipelineResponse filePipelineDetail(String tenantId, Long id) {
        String resolved = resolveTenant(tenantGuard, tenantId);
        PageRequest pageSingle = new PageRequest(1, 1);
        List<Map<String, Object>> rows =
                fileMappers.filePipelineMapper.selectByQuery(
                        resolved, null, id, null, null, null, null, null, pageSingle);
        if (rows.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "pipeline instance not found: " + id);
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
        if (batchSecurityProperties.isTestingOpen()
                || rows == null
                || rows.isEmpty()
                || fileId == null
                || !StringUtils.hasText(tenantId)) {
            return;
        }
        String templateCode =
                fileMappers.fileRecordMapper.selectTemplateCodeByFileId(tenantId, fileId);
        if (!StringUtils.hasText(templateCode)) {
            return;
        }
        Map<String, Object> sec =
                fileMappers.fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(
                        tenantId, templateCode);
        if (sec == null || !truthy(sec.get("error_line_masking_enabled"))) {
            return;
        }
        String ruleSet =
                sec.get("masking_rule_set") == null
                        ? null
                        : String.valueOf(sec.get("masking_rule_set"));
        for (FileErrorRecordEntity row : rows) {
            if (row.getErrorMessage() != null) {
                row.setErrorMessage(
                        ContentMaskingUtils.maskPlainText(row.getErrorMessage(), ruleSet));
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

    ConsoleFilePipelineResponse toFilePipelineResponse(Map<String, Object> row) {
        return new ConsoleFilePipelineResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
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
                instantValue(row, "created_at"),
                instantValue(row, "updated_at"));
    }

    private ConsoleFilePipelineStepResponse toFilePipelineStepResponse(Map<String, Object> row) {
        return new ConsoleFilePipelineStepResponse(
                longValue(row, "id"),
                longValue(row, "pipeline_instance_id"),
                stringValue(row, "step_code"),
                stringValue(row, "stage_code"),
                intValue(row, "run_seq"),
                stringValue(row, "step_status"),
                stringValue(row, "input_summary"),
                stringValue(row, "output_summary"),
                stringValue(row, "error_code"),
                stringValue(row, "error_message"),
                intValue(row, "retry_count"),
                longValue(row, "duration_ms"),
                instantValue(row, "started_at"),
                instantValue(row, "finished_at"));
    }

    private ConsoleFileDispatchRecordResponse toFileDispatchRecordResponse(
            Map<String, Object> row) {
        return new ConsoleFileDispatchRecordResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
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
                stringValue(row, "error_message"),
                instantValue(row, "dispatched_at"),
                instantValue(row, "ack_at"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at"));
    }

    private ConsoleFileChannelResponse toFileChannelResponse(Map<String, Object> row) {
        return new ConsoleFileChannelResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
                stringValue(row, "channel_code"),
                stringValue(row, "channel_name"),
                stringValue(row, "channel_type"),
                stringValue(row, "target_endpoint"),
                stringValue(row, "auth_type"),
                stringValue(row, "config_json"),
                stringValue(row, "receipt_policy"),
                intValue(row, "timeout_seconds"),
                booleanValue(row, "enabled"),
                instantValue(row, "created_at"),
                instantValue(row, "updated_at"));
    }

    private ConsoleFileTemplateResponse toFileTemplateResponse(Map<String, Object> row) {
        return new ConsoleFileTemplateResponse(
                longValue(row, "id"),
                stringValue(row, "tenant_id"),
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
                instantValue(row, "created_at"),
                instantValue(row, "updated_at"));
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
        return new ConsoleFileErrorRecordResponse(
                entity.getId(),
                display(entity.getTenantId()),
                entity.getFileId(),
                entity.getPipelineInstanceId(),
                entity.getPipelineStepRunId(),
                entity.getRecordNo(),
                display(entity.getErrorCode()),
                display(entity.getErrorMessage()),
                display(entity.getErrorStage()),
                entity.getSkipped(),
                display(entity.getSkipAction()),
                display(entity.getRawRecord()),
                entity.getCreatedAt());
    }
}
