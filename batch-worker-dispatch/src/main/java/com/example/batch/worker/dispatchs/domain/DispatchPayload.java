package com.example.batch.worker.dispatchs.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 分发任务载荷，携带文件 ID、渠道编码、接收方及回执策略等信息。
 *
 * <p>{@code ignoreUnknown=true}：orch 派发消息携带的字段集随业务迭代会扩（如 {@code catchUp} / {@code
 * scheduleType}），worker 侧不应因未知字段直接抛 UnrecognizedPropertyException； 未识别字段安全丢弃即可，契约扩展由新增字段方向向前兼容保证。与
 * {@code ImportPayload} 一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchPayload(
    // ADR-046 文件束分发:束 partition 的通用绑定列经 dispatch 派发塞进 payload 为 sourceFileId /
    // targetRef(见 DefaultPartitionDispatchService.enrichBundleBinding)。分发载荷历史字段名是
    // fileId / channelCode,这里用 @JsonAlias 把束的通用键直接映射进来,无需在 PrepareDispatchStep
    // 加分支——普通分发任务仍用原字段名,不受影响(同名优先,别名兜底)。
    @JsonAlias("sourceFileId") String fileId,
    String fileCode,
    @JsonAlias("targetRef") String channelCode,
    String dispatchTarget,
    String externalRequestId,
    String receiptCode,
    Boolean ackRequired,
    Boolean forceRetry,
    @JsonProperty("run_mode") @JsonAlias("runMode") String runMode,
    Map<String, Object> metadata) {}
