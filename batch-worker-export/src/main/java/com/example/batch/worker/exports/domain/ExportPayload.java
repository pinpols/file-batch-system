package com.example.batch.worker.exports.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        @JsonProperty("run_mode")
        @JsonAlias("runMode")
        String runMode,
        java.util.Map<String, Object> metadata
) {
}
