package com.example.batch.console.service;

/**
 * 单次 webhook HTTP 投递的结构化结果。
 *
 * <p>由 {@link WebhookDispatcher#attemptDelivery} 同步返回,供 caller 决定是否重试 / 入库。 不抛异常 — 所有失败都 折叠成 {@code
 * success=false} + 描述性的 {@code httpStatus} / {@code errorSummary}。
 */
public record WebhookDeliveryResult(boolean success, Integer httpStatus, String errorSummary) {

  public static WebhookDeliveryResult ok() {
    return new WebhookDeliveryResult(true, null, null);
  }

  public static WebhookDeliveryResult failure(Integer httpStatus, String errorSummary) {
    return new WebhookDeliveryResult(false, httpStatus, errorSummary);
  }
}
