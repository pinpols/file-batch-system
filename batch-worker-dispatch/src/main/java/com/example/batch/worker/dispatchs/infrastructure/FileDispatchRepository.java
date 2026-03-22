package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.worker.dispatchs.mapper.FileDispatchMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class FileDispatchRepository {

    private final FileDispatchMapper fileDispatchMapper;

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
        Map<String, Object> fileRecord = fileDispatchMapper.selectFileRecord(params("tenantId", tenantId, "fileId", fileId));
        return fileRecord == null ? Map.of() : fileRecord;
    }

    public Map<String, Object> loadChannel(String tenantId, String channelCode) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(channelCode)) {
            return Map.of();
        }
        Map<String, Object> channelConfig = fileDispatchMapper.selectChannelConfig(
                params("tenantId", tenantId, "channelCode", channelCode)
        );
        return channelConfig == null ? Map.of() : channelConfig;
    }

    public Map<String, Object> loadLatestDispatchRecord(String tenantId, Long fileId, String channelCode) {
        if (!StringUtils.hasText(tenantId) || fileId == null || !StringUtils.hasText(channelCode)) {
            return Map.of();
        }
        Map<String, Object> dispatchRecord = fileDispatchMapper.selectLatestDispatchRecord(
                params("tenantId", tenantId, "fileId", fileId, "channelCode", channelCode)
        );
        return dispatchRecord == null ? Map.of() : dispatchRecord;
    }

    public int insertDispatchRecord(String tenantId,
                                    Long fileId,
                                    Long pipelineInstanceId,
                                    String channelCode,
                                    String dispatchTarget,
                                    String receiptCode,
                                    String receiptStatus,
                                    String externalRequestId) {
        return fileDispatchMapper.insertDispatchRecord(params(
                "tenantId", tenantId,
                "fileId", fileId,
                "pipelineInstanceId", pipelineInstanceId,
                "channelCode", channelCode,
                "dispatchTarget", dispatchTarget,
                "receiptCode", receiptCode,
                "receiptStatus", receiptStatus,
                "externalRequestId", externalRequestId
        ));
    }

    public int incrementAttempt(String tenantId, Long fileId, String channelCode) {
        return fileDispatchMapper.incrementAttempt(params("tenantId", tenantId, "fileId", fileId, "channelCode", channelCode));
    }

    public int markSent(String tenantId,
                        Long fileId,
                        String channelCode,
                        String externalRequestId,
                        String receiptCode,
                        String receiptStatus) {
        return fileDispatchMapper.markSent(params(
                "tenantId", tenantId,
                "fileId", fileId,
                "channelCode", channelCode,
                "externalRequestId", externalRequestId,
                "receiptCode", receiptCode,
                "receiptStatus", receiptStatus
        ));
    }

    public int markAcked(String tenantId, Long fileId, String channelCode, String receiptCode) {
        return fileDispatchMapper.markAcked(
                params("tenantId", tenantId, "fileId", fileId, "channelCode", channelCode, "receiptCode", receiptCode)
        );
    }

    public int markFailed(String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
        return fileDispatchMapper.markFailed(params(
                "tenantId", tenantId,
                "fileId", fileId,
                "channelCode", channelCode,
                "errorCode", errorCode,
                "errorMessage", errorMessage
        ));
    }

    public int markCompensated(String tenantId, Long fileId, String channelCode, String errorCode, String errorMessage) {
        return fileDispatchMapper.markCompensated(params(
                "tenantId", tenantId,
                "fileId", fileId,
                "channelCode", channelCode,
                "errorCode", errorCode,
                "errorMessage", errorMessage
        ));
    }

    public List<Map<String, Object>> listPendingReceiptPolls(int limit) {
        int safe = Math.max(1, Math.min(limit, 500));
        return fileDispatchMapper.listPendingReceiptPolls(params("limit", safe));
    }

    private Map<String, Object> params(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }
}
