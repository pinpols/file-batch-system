package io.github.pinpols.batch.console.domain.job.application.contract.response;

import java.util.List;

/** 原键对应的批次状态；未找到不表示未提交或可以重发。 */
public record BatchDayCatchUpRequestStatusResponse(
    boolean found,
    String tenantId,
    String bizDate,
    String calendarCode,
    String catchUpPolicy,
    String operationStatus,
    List<Item> items) {

  public record Item(String jobCode, String status, String referenceNo) {}
}
