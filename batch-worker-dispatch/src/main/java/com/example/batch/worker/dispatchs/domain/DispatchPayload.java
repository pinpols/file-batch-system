package com.example.batch.worker.dispatchs.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 分发任务载荷，携带文件 ID、渠道编码、接收方及回执策略等信息。
 *
 * <p>{@code ignoreUnknown=true}：orch 派发消息携带的字段集随业务迭代会扩（如 {@code catchUp}
 * / {@code scheduleType}），worker 侧不应因未知字段直接抛 UnrecognizedPropertyException；
 * 未识别字段安全丢弃即可，契约扩展由新增字段方向向前兼容保证。与 {@code ImportPayload} 一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchPayload(
    String fileId,
    String fileCode,
    String channelCode,
    String dispatchTarget,
    String externalRequestId,
    String receiptCode,
    Boolean ackRequired,
    Boolean forceRetry,
    @JsonProperty("run_mode") @JsonAlias("runMode") String runMode,
    Map<String, Object> metadata) {}
