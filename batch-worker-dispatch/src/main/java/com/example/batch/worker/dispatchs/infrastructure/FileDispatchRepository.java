package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class FileDispatchRepository {

    private final JdbcTemplate platformJdbcTemplate;

    public FileDispatchRepository(JdbcTemplate platformJdbcTemplate) {
        this.platformJdbcTemplate = platformJdbcTemplate;
    }

    public Map<String, Object> loadFile(String tenantId, String fileId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(fileId)) {
            return Map.of();
        }
        return loadFile(tenantId, Long.valueOf(fileId));
    }

    public Map<String, Object> loadFile(String tenantId, Long fileId) {
        if (!StringUtils.hasText(tenantId) || fileId == null) {
            return Map.of();
        }
        List<Map<String, Object>> fileRows = platformJdbcTemplate.queryForList(
                "select * from batch.file_record where tenant_id = ? and id = ?",
                tenantId, fileId
        );
        return fileRows.isEmpty() ? Map.of() : fileRows.get(0);
    }

    public Map<String, Object> loadChannel(String tenantId, String channelCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(channelCode)) {
            return Map.of();
        }
        List<Map<String, Object>> channelRows = platformJdbcTemplate.queryForList(
                "select * from batch.file_channel_config where tenant_id = ? and channel_code = ?",
                tenantId, channelCode
        );
        return channelRows.isEmpty() ? Map.of() : channelRows.get(0);
    }

    public Map<String, Object> loadLatestDispatchRecord(String tenantId, Long fileId, String channelCode) {
        if (!StringUtils.hasText(tenantId) || fileId == null || !StringUtils.hasText(channelCode)) {
            return Map.of();
        }
        List<Map<String, Object>> rows = platformJdbcTemplate.queryForList("""
                select *
                from batch.file_dispatch_record
                where tenant_id = ?
                  and file_id = ?
                  and channel_code = ?
                order by id desc
                limit 1
                """,
                tenantId,
                fileId,
                channelCode
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public int insertDispatchRecord(String tenantId,
                                    Long fileId,
                                    Long pipelineInstanceId,
                                    String channelCode,
                                    String dispatchTarget,
                                    String receiptCode,
                                    String receiptStatus,
                                    String externalRequestId) {
        String sql = """
                insert into batch.file_dispatch_record (
                    tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_target, dispatch_status,
                    dispatch_attempt, receipt_code, receipt_status, external_request_id
                ) values (?, ?, ?, ?, ?, 'CREATED', 1, ?, ?, ?)
                """;
        return platformJdbcTemplate.update(sql,
                tenantId,
                fileId,
                pipelineInstanceId,
                channelCode,
                dispatchTarget,
                receiptCode,
                receiptStatus,
                externalRequestId
        );
    }

    public int incrementAttempt(String tenantId, Long fileId, String channelCode) {
        return platformJdbcTemplate.update("""
                update batch.file_dispatch_record
                set dispatch_attempt = dispatch_attempt + 1,
                    updated_at = current_timestamp
                where tenant_id = ? and file_id = ? and channel_code = ?
                """,
                tenantId, fileId, channelCode);
    }

    public int markSent(String tenantId,
                        Long fileId,
                        String channelCode,
                        String externalRequestId,
                        String receiptCode,
                        String receiptStatus) {
        return platformJdbcTemplate.update("""
                update batch.file_dispatch_record
                set dispatch_status = 'SENT',
                    external_request_id = ?,
                    receipt_code = coalesce(?, receipt_code),
                    receipt_status = ?,
                    dispatched_at = current_timestamp,
                    updated_at = current_timestamp
                where tenant_id = ? and file_id = ? and channel_code = ?
                """,
                externalRequestId, receiptCode, receiptStatus, tenantId, fileId, channelCode);
    }

    public int markAcked(String tenantId, Long fileId, String channelCode, String receiptCode) {
        return platformJdbcTemplate.update("""
                update batch.file_dispatch_record
                set dispatch_status = 'ACKED',
                    receipt_status = 'SUCCESS',
                    receipt_code = ?,
                    ack_at = current_timestamp,
                    updated_at = current_timestamp
                where tenant_id = ? and file_id = ? and channel_code = ?
                """,
                receiptCode, tenantId, fileId, channelCode);
    }

    public int markFailed(String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
        return platformJdbcTemplate.update("""
                update batch.file_dispatch_record
                set dispatch_status = 'FAILED',
                    receipt_status = 'FAILED',
                    error_code = ?,
                    error_message = ?,
                    updated_at = current_timestamp
                where tenant_id = ? and file_id = ? and channel_code = ?
                """,
                errorCode, errorMessage, tenantId, fileId, channelCode);
    }

    public int markCompensated(String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
        return platformJdbcTemplate.update("""
                update batch.file_dispatch_record
                set dispatch_status = 'COMPENSATED',
                    receipt_status = case
                        when receipt_status = 'SUCCESS' then receipt_status
                        else 'FAILED'
                    end,
                    error_code = ?,
                    error_message = ?,
                    updated_at = current_timestamp
                where tenant_id = ? and file_id = ? and channel_code = ?
                """,
                errorCode, errorMessage, tenantId, fileId, channelCode);
    }
}
