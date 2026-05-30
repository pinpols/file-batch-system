package com.example.batch.console.domain.observability.view.cluster;

/**
 * batch.event_delivery_log 按 delivery_status 分组的计数投影。
 *
 * <p>保留手写 {@code getXxx()} accessor — 见 {@link ShedLockView}。
 */
public record DeliveryStatusCountView(String deliveryStatus, Long cnt) {

  public String getDeliveryStatus() {
    return deliveryStatus;
  }

  public Long getCnt() {
    return cnt;
  }
}
