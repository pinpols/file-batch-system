package com.example.batch.worker.exports.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 导出任务的消息负载，由 Kafka 消息反序列化而来。
 *
 * <p>{@code ignoreUnknown=true}：orch 派发消息携带的字段集随业务迭代会扩（如 {@code catchUp} / {@code
 * scheduleType}），worker 侧不应因未知字段直接抛 UnrecognizedPropertyException； 未识别字段安全丢弃即可，契约扩展由新增字段方向向前兼容保证。与
 * {@code ImportPayload} 一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
