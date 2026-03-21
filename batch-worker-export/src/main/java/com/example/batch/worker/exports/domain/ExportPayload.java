package com.example.batch.worker.exports.domain;

public record ExportPayload(
        String fileCode,
        String bizType,
        String templateCode,
        String batchNo,
        String fileName,
        String objectName,
        String bizDate,
        String targetPath,
        Boolean autoDispatch,
        java.util.Map<String, Object> metadata
) {
}
