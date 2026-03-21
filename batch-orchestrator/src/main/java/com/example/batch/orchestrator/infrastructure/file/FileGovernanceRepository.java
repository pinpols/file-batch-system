package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.common.utils.JsonUtils;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class FileGovernanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public FileGovernanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select *
                from batch.file_record
                where tenant_id = ?
                  and id = ?
                """,
                tenantId,
                fileId
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public long countActivePipelineInstances(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject("""
                select count(1)
                from batch.pipeline_instance
                where tenant_id = ?
                  and file_id = ?
                  and run_status in ('CREATED', 'RUNNING', 'COMPENSATING')
                """,
                Long.class,
                tenantId,
                fileId
        );
        return count == null ? 0L : count;
    }

    public long countPendingDispatchRecords(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject("""
                select count(1)
                from batch.file_dispatch_record
                where tenant_id = ?
                  and file_id = ?
                  and (dispatch_status in ('CREATED', 'SENT') or receipt_status = 'PENDING')
                """,
                Long.class,
                tenantId,
                fileId
        );
        return count == null ? 0L : count;
    }

    public Map<String, Object> loadLatestDispatchRecord(String tenantId, Long fileId, String channelCode) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("""
                select *
                from batch.file_dispatch_record
                where tenant_id = ?
                  and file_id = ?
                """);
        if (StringUtils.hasText(channelCode)) {
            sql.append(" and channel_code = ? ");
        }
        sql.append(" order by id desc limit 1 ");
        List<Map<String, Object>> rows = StringUtils.hasText(channelCode)
                ? jdbcTemplate.queryForList(sql.toString(), tenantId, fileId, channelCode)
                : jdbcTemplate.queryForList(sql.toString(), tenantId, fileId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Long loadRelatedJobInstanceId(Long pipelineInstanceId) {
        if (pipelineInstanceId == null) {
            return null;
        }
        return jdbcTemplate.query("""
                select related_job_instance_id
                from batch.pipeline_instance
                where id = ?
                """,
                rs -> rs.next() ? toLong(rs.getObject("related_job_instance_id")) : null,
                pipelineInstanceId
        );
    }

    public void resetDispatchRecordForRedispatch(String tenantId, Long dispatchRecordId) {
        if (!StringUtils.hasText(tenantId) || dispatchRecordId == null) {
            return;
        }
        jdbcTemplate.update("""
                update batch.file_dispatch_record
                set dispatch_status = 'CREATED',
                    receipt_status = 'NONE',
                    error_code = null,
                    error_message = null,
                    ack_at = null,
                    updated_at = current_timestamp
                where tenant_id = ?
                  and id = ?
                """,
                tenantId,
                dispatchRecordId
        );
    }

    public List<Map<String, Object>> selectArchivedFilesForCleanup(Instant cutoff, int limit) {
        return jdbcTemplate.queryForList("""
                select *
                from batch.file_record
                where file_status = 'ARCHIVED'
                  and updated_at < ?
                order by updated_at asc
                limit ?
                """,
                Timestamp.from(cutoff),
                limit
        );
    }

    public long countArrivalDelayViolations(long thresholdSeconds) {
        Long count = jdbcTemplate.queryForObject("""
                select count(1)
                from batch.file_record
                where metadata_json ? 'expectedArrivalTime'
                  and created_at > ((metadata_json ->> 'expectedArrivalTime')::timestamptz + (? * interval '1 second'))
                """,
                Long.class,
                thresholdSeconds
        );
        return count == null ? 0L : count;
    }

    public long maxArrivalDelaySeconds() {
        Long maxDelay = jdbcTemplate.queryForObject("""
                select coalesce(max(extract(epoch from (created_at - ((metadata_json ->> 'expectedArrivalTime')::timestamptz)))), 0)::bigint
                from batch.file_record
                where metadata_json ? 'expectedArrivalTime'
                """,
                Long.class
        );
        return maxDelay == null ? 0L : maxDelay;
    }

    public List<Map<String, Object>> selectArrivalDelaySamples(long thresholdSeconds, int limit) {
        return jdbcTemplate.queryForList("""
                select tenant_id, id, file_name, created_at,
                       (metadata_json ->> 'expectedArrivalTime') as expected_arrival_time,
                       extract(epoch from (created_at - ((metadata_json ->> 'expectedArrivalTime')::timestamptz)))::bigint as arrival_delay_seconds
                from batch.file_record
                where metadata_json ? 'expectedArrivalTime'
                  and created_at > ((metadata_json ->> 'expectedArrivalTime')::timestamptz + (? * interval '1 second'))
                order by arrival_delay_seconds desc
                limit ?
                """,
                thresholdSeconds,
                limit
        );
    }

    public long countProcessingDelayViolations(long thresholdSeconds) {
        Long count = jdbcTemplate.queryForObject("""
                select count(1)
                from batch.pipeline_instance
                where run_status = 'RUNNING'
                  and started_at is not null
                  and started_at < current_timestamp - (? * interval '1 second')
                """,
                Long.class,
                thresholdSeconds
        );
        return count == null ? 0L : count;
    }

    public long maxProcessingDelaySeconds() {
        Long maxDelay = jdbcTemplate.queryForObject("""
                select coalesce(max(extract(epoch from (current_timestamp - started_at))), 0)::bigint
                from batch.pipeline_instance
                where run_status = 'RUNNING'
                  and started_at is not null
                """,
                Long.class
        );
        return maxDelay == null ? 0L : maxDelay;
    }

    public List<Map<String, Object>> selectProcessingDelaySamples(long thresholdSeconds, int limit) {
        return jdbcTemplate.queryForList("""
                select tenant_id, id, pipeline_code, pipeline_type, related_job_instance_id, started_at,
                       extract(epoch from (current_timestamp - started_at))::bigint as processing_delay_seconds
                from batch.pipeline_instance
                where run_status = 'RUNNING'
                  and started_at is not null
                  and started_at < current_timestamp - (? * interval '1 second')
                order by processing_delay_seconds desc
                limit ?
                """,
                thresholdSeconds,
                limit
        );
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

    public Long createReconciledFileRecord(String tenantId,
                                           String fileCategory,
                                           String fileName,
                                           String fileFormatType,
                                           long fileSizeBytes,
                                           String storageType,
                                           String storagePath,
                                           String storageBucket,
                                           String sourceType,
                                           String fileStatus,
                                           String traceId,
                                           Object metadata) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into batch.file_record (
                        tenant_id, file_category, file_name, original_file_name, file_ext, file_format_type,
                        charset, mime_type, file_size_bytes, checksum_type, checksum_value,
                        storage_type, storage_path, storage_bucket, file_version, file_generation_no, is_latest,
                        source_type, source_ref, file_status, biz_date, trace_id, metadata_json, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'NONE', null, ?, ?, ?, 'v1', 1, true, ?, null, ?, null, ?, ?::jsonb, current_timestamp, current_timestamp)
                    """, new String[] {"id"});
            statement.setString(1, tenantId);
            statement.setString(2, fileCategory);
            statement.setString(3, fileName);
            statement.setString(4, fileName);
            statement.setString(5, resolveFileExt(fileName));
            statement.setString(6, fileFormatType);
            statement.setString(7, "UTF-8");
            statement.setString(8, resolveMimeType(fileFormatType));
            statement.setLong(9, Math.max(fileSizeBytes, 0L));
            statement.setString(10, storageType);
            statement.setString(11, storagePath);
            statement.setString(12, storageBucket);
            statement.setString(13, sourceType);
            statement.setString(14, fileStatus);
            statement.setString(15, traceId);
            statement.setString(16, toJson(metadata));
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public void updateFileStatus(String tenantId, Long fileId, String nextStatus, Object metadata) {
        Map<String, Object> fileRecord = loadFileRecord(tenantId, fileId);
        if (fileRecord.isEmpty()) {
            return;
        }
        String currentStatus = fileRecord.get("file_status") == null ? null : String.valueOf(fileRecord.get("file_status"));
        FileStateMachine.assertTransition(currentStatus, nextStatus);
        jdbcTemplate.update("""
                update batch.file_record
                set file_status = ?,
                    metadata_json = coalesce(metadata_json, '{}'::jsonb) || coalesce(?::jsonb, '{}'::jsonb),
                    updated_at = current_timestamp
                where tenant_id = ?
                  and id = ?
                """,
                nextStatus,
                toJson(metadata),
                tenantId,
                fileId
        );
    }

    public void appendAudit(String tenantId,
                            Long fileId,
                            String operationType,
                            String operationResult,
                            String operatorType,
                            String operatorId,
                            String traceId,
                            Object detailSummary) {
        if (!StringUtils.hasText(tenantId) || fileId == null || !StringUtils.hasText(operationType) || !StringUtils.hasText(operationResult)) {
            return;
        }
        jdbcTemplate.update("""
                insert into batch.file_audit_log (
                    tenant_id, file_id, operation_type, operation_result,
                    operator_type, operator_id, trace_id, detail_summary, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, current_timestamp)
                """,
                tenantId,
                fileId,
                operationType,
                operationResult,
                defaultText(operatorType, "API"),
                operatorId,
                traceId,
                toJson(detailSummary)
        );
    }

    public Map<String, Object> operationDetail(String currentStatus,
                                               String nextStatus,
                                               String operatorId,
                                               String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("currentStatus", currentStatus);
        detail.put("nextStatus", nextStatus);
        detail.put("operatorId", operatorId);
        detail.put("reason", reason);
        return detail;
    }

    private String toJson(Object value) {
        return value == null ? null : JsonUtils.toJson(value);
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : Long.valueOf(text);
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
            case "DELIMITED" -> "text/csv";
            default -> "application/octet-stream";
        };
    }
}
