package io.github.pinpols.batch.worker.dispatchs.domain;

/**
 * 待轮询回执的分发记录视图，只含回执轮询实际消费的列。
 *
 * <p>字段顺序必须与 {@code FileDispatchMapper.xml} 的 {@code <constructor>} 声明保持一致。
 */
public record PendingReceiptPollRow(
    String tenantId, Long fileId, String channelCode, String externalRequestId) {}
