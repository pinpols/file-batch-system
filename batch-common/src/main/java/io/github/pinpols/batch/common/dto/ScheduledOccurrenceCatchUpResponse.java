package io.github.pinpols.batch.common.dto;

/** 接受或提交某个指定定时触发进行补扫后的结果。 */
public record ScheduledOccurrenceCatchUpResponse(String requestId, String status) {}
