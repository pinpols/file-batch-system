package io.github.pinpols.batch.worker.dispatchs.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Map;
import lombok.Builder;

/**
 * 分发任务载荷，携带文件 ID、渠道编码、接收方及回执策略等信息。
 *
 * <p>{@code ignoreUnknown=true}：orch 派发消息携带的字段集随业务迭代会扩（如 {@code catchUp} / {@code
 * scheduleType}），worker 侧不应因未知字段直接抛 UnrecognizedPropertyException； 未识别字段安全丢弃即可，契约扩展由新增字段方向向前兼容保证。与
 * {@code ImportPayload} 一致。
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchPayload(
    // ADR-046 文件束分发:束 partition 的绑定列经 dispatch 派发写入 payload 为带 bundle 前缀的
    // bundleSourceFileId / bundleTargetRef(见 DefaultPartitionDispatchService.enrichBundleBinding)。
    // 束字段承载分区绑定，应优先于通用字段且不受 JSON 字段顺序影响。
    String fileId,
    String fileCode,
    String channelCode,
    String dispatchTarget,
    String externalRequestId,
    String receiptCode,
    Boolean ackRequired,
    Boolean forceRetry,
    @JsonProperty("run_mode") @JsonAlias("runMode") String runMode,
    Map<String, Object> metadata) {

  /** 文件束字段优先，避免分区绑定被通用字段或 JSON 字段顺序覆盖。 */
  @JsonCreator
  public DispatchPayload(
      @JsonProperty("fileId") String fileId,
      @JsonProperty("bundleSourceFileId") String bundleSourceFileId,
      @JsonProperty("fileCode") String fileCode,
      @JsonProperty("channelCode") String channelCode,
      @JsonProperty("dispatchChannelCode") String dispatchChannelCode,
      @JsonProperty("targetChannelCode") String targetChannelCode,
      @JsonProperty("bundleTargetRef") String bundleTargetRef,
      @JsonProperty("dispatchTarget") String dispatchTarget,
      @JsonProperty("externalRequestId") String externalRequestId,
      @JsonProperty("receiptCode") String receiptCode,
      @JsonProperty("ackRequired") Boolean ackRequired,
      @JsonProperty("forceRetry") Boolean forceRetry,
      @JsonProperty("run_mode") @JsonAlias("runMode") String runMode,
      @JsonProperty("metadata") Map<String, Object> metadata) {
    this(
        EmptyChecks.isNotNull(bundleSourceFileId) ? bundleSourceFileId : fileId,
        fileCode,
        EmptyChecks.isNotNull(bundleTargetRef)
            ? bundleTargetRef
            : resolveChannelCode(channelCode, dispatchChannelCode, targetChannelCode),
        dispatchTarget,
        externalRequestId,
        receiptCode,
        ackRequired,
        forceRetry,
        runMode,
        metadata);
  }

  private static String resolveChannelCode(
      String channelCode, String dispatchChannelCode, String targetChannelCode) {
    for (String value : new String[] {channelCode, dispatchChannelCode, targetChannelCode}) {
      if (EmptyChecks.isNotNull(value) && !value.isBlank()) { // empty-check: allow - 保留 JDK 字符串空白判定
        return value.trim();
      }
    }
    return channelCode;
  }
}
