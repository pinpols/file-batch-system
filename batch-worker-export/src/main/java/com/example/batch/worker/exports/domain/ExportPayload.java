package com.example.batch.worker.exports.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** 导出任务的消息负载，由 Kafka 消息反序列化而来。 */
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
    @JsonProperty("run_mode") @JsonAlias("runMode") String runMode,
    Map<String, Object> metadata) {}
