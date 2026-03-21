package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.common.utils.JsonUtils;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class PlatformFileRuntimeRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlatformFileRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from batch.file_record where tenant_id = ? and id = ?",
                tenantId, fileId
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public boolean existsFileRecordByStoragePath(String tenantId, String storageBucket, String storagePath) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(storagePath)) {
            return false;
        }
        Long count = jdbcTemplate.queryForObject("""
                select count(1)
                from batch.file_record
                where tenant_id = ?
                  and coalesce(storage_bucket, '') = coalesce(?, '')
                  and storage_path = ?
                """,
                Long.class,
                tenantId,
                storageBucket,
                storagePath
        );
        return count != null && count > 0;
    }

    public Map<String, Object> loadLatestTemplateConfig(String tenantId, String templateCode, String templateType) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(templateCode)) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("""
                select *
                from batch.file_template_config
                where tenant_id = ?
                  and template_code = ?
                  and enabled = true
                """);
        if (StringUtils.hasText(templateType)) {
            sql.append(" and template_type = ? ");
        }
        sql.append(" order by version desc limit 1 ");
        List<Map<String, Object>> rows = StringUtils.hasText(templateType)
                ? jdbcTemplate.queryForList(sql.toString(), tenantId, templateCode, templateType)
                : jdbcTemplate.queryForList(sql.toString(), tenantId, templateCode);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> loadChannelConfig(String tenantId, String channelCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(channelCode)) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                select *
                from batch.file_channel_config
                where tenant_id = ?
                  and channel_code = ?
                  and enabled = true
                """,
                tenantId, channelCode
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Long ensurePipelineDefinition(String tenantId,
                                         String pipelineCode,
                                         String pipelineType,
                                         String workerGroup,
                                         String description) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(pipelineCode) || !StringUtils.hasText(pipelineType)) {
            return null;
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                """
                select id
                from batch.pipeline_definition
                where tenant_id = ?
                  and pipeline_code = ?
                order by version desc
                limit 1
                """,
                tenantId, pipelineCode
        );
        if (!existing.isEmpty()) {
            return toLong(existing.get(0).get("id"));
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into batch.pipeline_definition (
                        tenant_id, pipeline_code, pipeline_name, pipeline_type,
                        worker_group, version, enabled, description
                    ) values (?, ?, ?, ?, ?, 1, true, ?)
                    """, new String[] {"id"});
            statement.setString(1, tenantId);
            statement.setString(2, pipelineCode);
            statement.setString(3, pipelineCode);
            statement.setString(4, pipelineType);
            statement.setString(5, workerGroup);
            statement.setString(6, description);
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public Long createPipelineInstance(String tenantId,
                                       Long pipelineDefinitionId,
                                       String pipelineCode,
                                       String pipelineType,
                                       Long fileId,
                                       Long relatedJobInstanceId,
                                       String currentStage,
                                       String traceId) {
        if (!StringUtils.hasText(tenantId) || pipelineDefinitionId == null) {
            return null;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into batch.pipeline_instance (
                        tenant_id, pipeline_definition_id, pipeline_code, pipeline_type,
                        file_id, related_job_instance_id, current_stage, run_status,
                        trace_id, started_at, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, current_timestamp, current_timestamp, current_timestamp)
                    """, new String[] {"id"});
            statement.setString(1, tenantId);
            statement.setLong(2, pipelineDefinitionId);
            statement.setString(3, pipelineCode);
            statement.setString(4, pipelineType);
            if (fileId == null) {
                statement.setObject(5, null);
            } else {
                statement.setLong(5, fileId);
            }
            if (relatedJobInstanceId == null) {
                statement.setObject(6, null);
            } else {
                statement.setLong(6, relatedJobInstanceId);
            }
            statement.setString(7, currentStage);
            statement.setString(8, traceId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public void bindFileToPipelineInstance(Long pipelineInstanceId, Long fileId) {
        if (pipelineInstanceId == null || fileId == null) {
            return;
        }
        jdbcTemplate.update("""
                update batch.pipeline_instance
                set file_id = ?,
                    updated_at = current_timestamp
                where id = ?
                """,
                fileId, pipelineInstanceId
        );
    }

    public void updatePipelineStage(Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
        if (pipelineInstanceId == null) {
            return;
        }
        jdbcTemplate.update("""
                update batch.pipeline_instance
                set current_stage = ?,
                    last_success_stage = coalesce(?, last_success_stage),
                    updated_at = current_timestamp
                where id = ?
                """,
                currentStage,
                lastSuccessStage,
                pipelineInstanceId
        );
    }

    public void markPipelineSuccess(Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
        if (pipelineInstanceId == null) {
            return;
        }
        jdbcTemplate.update("""
                update batch.pipeline_instance
                set current_stage = ?,
                    last_success_stage = ?,
                    run_status = 'SUCCESS',
                    finished_at = current_timestamp,
                    updated_at = current_timestamp
                where id = ?
                """,
                currentStage, lastSuccessStage, pipelineInstanceId
        );
    }

    public void markPipelineFailed(Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
        if (pipelineInstanceId == null) {
            return;
        }
        jdbcTemplate.update("""
                update batch.pipeline_instance
                set current_stage = ?,
                    last_success_stage = ?,
                    run_status = 'FAILED',
                    finished_at = current_timestamp,
                    updated_at = current_timestamp
                where id = ?
                """,
                currentStage, lastSuccessStage, pipelineInstanceId
        );
    }

    public Long startStepRun(Long pipelineInstanceId,
                             String stepCode,
                             String stageCode,
                             Object inputSummary) {
        if (pipelineInstanceId == null || !StringUtils.hasText(stepCode) || !StringUtils.hasText(stageCode)) {
            return null;
        }
        Integer nextRunSeq = jdbcTemplate.queryForObject("""
                select coalesce(max(run_seq), 0) + 1
                from batch.pipeline_step_run
                where pipeline_instance_id = ?
                  and step_code = ?
                """,
                Integer.class,
                pipelineInstanceId,
                stepCode
        );
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String inputJson = toJson(inputSummary);
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into batch.pipeline_step_run (
                        pipeline_instance_id, step_code, stage_code, run_seq,
                        step_status, input_summary, started_at
                    ) values (?, ?, ?, ?, 'RUNNING', ?::jsonb, current_timestamp)
                    """, new String[] {"id"});
            statement.setLong(1, pipelineInstanceId);
            statement.setString(2, stepCode);
            statement.setString(3, stageCode);
            statement.setInt(4, nextRunSeq == null ? 1 : nextRunSeq);
            statement.setString(5, inputJson);
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public void finishStepRunSuccess(Long stepRunId, Object outputSummary) {
        finishStepRun(stepRunId, "SUCCESS", null, null, outputSummary);
    }

    public void finishStepRunFailure(Long stepRunId, String errorCode, String errorMessage, Object outputSummary) {
        finishStepRun(stepRunId, "FAILED", errorCode, errorMessage, outputSummary);
    }

    private void finishStepRun(Long stepRunId,
                               String status,
                               String errorCode,
                               String errorMessage,
                               Object outputSummary) {
        if (stepRunId == null) {
            return;
        }
        String outputJson = toJson(outputSummary);
        jdbcTemplate.update("""
                update batch.pipeline_step_run
                set step_status = ?,
                    output_summary = ?::jsonb,
                    error_code = ?,
                    error_message = ?,
                    duration_ms = greatest(0, extract(epoch from (current_timestamp - started_at)) * 1000)::bigint,
                    finished_at = current_timestamp
                where id = ?
                """,
                status, outputJson, errorCode, truncate(errorMessage, 1024), stepRunId
        );
    }

    @Transactional
    public Long createFileRecord(String tenantId,
                                 String fileCode,
                                 String bizType,
                                 String fileCategory,
                                 String fileName,
                                 String originalFileName,
                                 String fileFormatType,
                                 String charset,
                                 long fileSizeBytes,
                                 String checksumType,
                                 String checksumValue,
                                 String storageType,
                                 String storagePath,
                                 String storageBucket,
                                 String fileVersion,
                                 LocalDate bizDate,
                                 String sourceType,
                                 String sourceRef,
                                 String fileStatus,
                                 String traceId,
                                 Object metadata) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(fileCategory) || !StringUtils.hasText(fileName)
                || !StringUtils.hasText(fileFormatType) || !StringUtils.hasText(storageType)
                || !StringUtils.hasText(storagePath) || !StringUtils.hasText(sourceType) || !StringUtils.hasText(fileStatus)) {
            return null;
        }
        FileStateMachine.assertInitialStatus(fileStatus);
        int nextGenerationNo = 1;
        if (StringUtils.hasText(fileCode)) {
            Integer maxGeneration = jdbcTemplate.queryForObject("""
                    select coalesce(max(file_generation_no), 0)
                    from batch.file_record
                    where tenant_id = ?
                      and file_code = ?
                    """,
                    Integer.class,
                    tenantId,
                    fileCode
            );
            nextGenerationNo = (maxGeneration == null ? 0 : maxGeneration) + 1;
            jdbcTemplate.update("""
                    update batch.file_record
                    set is_latest = false,
                        updated_at = current_timestamp
                    where tenant_id = ?
                      and file_code = ?
                      and is_latest = true
                    """,
                    tenantId,
                    fileCode
            );
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String metadataJson = toJson(metadata);
        final int finalNextGenerationNo = nextGenerationNo;
        String resolvedFileVersion = StringUtils.hasText(fileVersion) ? fileVersion : "v" + finalNextGenerationNo;
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into batch.file_record (
                        tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
                        file_ext, file_format_type, charset, mime_type, file_size_bytes,
                        checksum_type, checksum_value, storage_type, storage_path, storage_bucket,
                        file_version, file_generation_no, is_latest, source_type, source_ref,
                        file_status, biz_date, trace_id, metadata_json, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?, ?::jsonb, current_timestamp, current_timestamp)
                    """, new String[] {"id"});
            statement.setString(1, tenantId);
            statement.setString(2, fileCode);
            statement.setString(3, bizType);
            statement.setString(4, fileCategory);
            statement.setString(5, fileName);
            statement.setString(6, originalFileName);
            statement.setString(7, resolveFileExt(fileName));
            statement.setString(8, fileFormatType);
            statement.setString(9, charset);
            statement.setString(10, resolveMimeType(fileFormatType));
            statement.setLong(11, Math.max(fileSizeBytes, 0L));
            statement.setString(12, defaultText(checksumType, "NONE"));
            statement.setString(13, checksumValue);
            statement.setString(14, storageType);
            statement.setString(15, storagePath);
            statement.setString(16, storageBucket);
            statement.setString(17, resolvedFileVersion);
            statement.setInt(18, finalNextGenerationNo);
            statement.setString(19, sourceType);
            statement.setString(20, sourceRef);
            statement.setString(21, fileStatus);
            if (bizDate == null) {
                statement.setObject(22, null);
            } else {
                statement.setObject(22, bizDate);
            }
            statement.setString(23, traceId);
            statement.setString(24, metadataJson);
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public void updateFileStatus(Long fileId, String fileStatus, Object metadata) {
        if (fileId == null || !StringUtils.hasText(fileStatus)) {
            return;
        }
        String currentStatus = jdbcTemplate.query("""
                select file_status
                from batch.file_record
                where id = ?
                """, rs -> rs.next() ? rs.getString("file_status") : null, fileId);
        if (!StringUtils.hasText(currentStatus)) {
            return;
        }
        FileStateMachine.assertTransition(currentStatus, fileStatus);
        jdbcTemplate.update("""
                update batch.file_record
                set file_status = ?,
                    metadata_json = coalesce(metadata_json, '{}'::jsonb) || coalesce(?::jsonb, '{}'::jsonb),
                    updated_at = current_timestamp
                where id = ?
                """,
                fileStatus, toJson(metadata), fileId
        );
    }

    public void appendAudit(Long fileId,
                            String tenantId,
                            String operationType,
                            String operationResult,
                            String operatorType,
                            String operatorId,
                            String traceId,
                            String evidenceRef,
                            Object detailSummary) {
        if (fileId == null || !StringUtils.hasText(tenantId) || !StringUtils.hasText(operationType) || !StringUtils.hasText(operationResult)) {
            return;
        }
        jdbcTemplate.update("""
                insert into batch.file_audit_log (
                    tenant_id, file_id, operation_type, operation_result,
                    operator_type, operator_id, trace_id, evidence_ref, detail_summary, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, current_timestamp)
                """,
                tenantId,
                fileId,
                operationType,
                operationResult,
                defaultText(operatorType, "SYSTEM"),
                operatorId,
                traceId,
                evidenceRef,
                toJson(detailSummary)
        );
    }

    public Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Long.valueOf(string);
        }
        return null;
    }

    public Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return null;
    }

    private String toJson(Object value) {
        return value == null ? null : JsonUtils.toJson(value);
    }

    private String resolveFileExt(String fileName) {
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return null;
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1);
    }

    private String resolveMimeType(String fileFormatType) {
        if (!StringUtils.hasText(fileFormatType)) {
            return "application/octet-stream";
        }
        return switch (fileFormatType) {
            case "JSON" -> "application/json";
            case "XML" -> "application/xml";
            case "EXCEL" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "text/plain";
        };
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
