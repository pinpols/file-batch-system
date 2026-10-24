package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

/** 分发操作结果，包含成功标志、外部请求 ID、回执码及回执挂起状态等信息。 */
public record DispatchResult(
    boolean success,
    String externalRequestId,
    String receiptCode,
    boolean acknowledged,
    boolean receiptPending,
    String message,
    String evidenceRef,
    DispatchManifestRef manifestRef,
    boolean externalOutcomeUnknown) {

  public DispatchResult(
      boolean success,
      String externalRequestId,
      String receiptCode,
      boolean acknowledged,
      boolean receiptPending,
      String message,
      String evidenceRef,
      DispatchManifestRef manifestRef) {
    this(
        success,
        externalRequestId,
        receiptCode,
        acknowledged,
        receiptPending,
        message,
        evidenceRef,
        manifestRef,
        false);
  }

  public DispatchResult(
      boolean success,
      String externalRequestId,
      String receiptCode,
      boolean acknowledged,
      boolean receiptPending,
      String message,
      String evidenceRef) {
    this(
        success,
        externalRequestId,
        receiptCode,
        acknowledged,
        receiptPending,
        message,
        evidenceRef,
        null,
        false);
  }

  /** 未取得外部请求身份或回执的确定失败，不标记为外部结果未知。 */
  public static DispatchResult failureWithoutReceipt(String message) {
    return new DispatchResult(false, null, null, false, false, message, null);
  }

  /** 外部请求可能已产生副作用，但未收到足以确认结果的响应。 */
  public static DispatchResult externalOutcomeUnknown(
      String externalRequestId, String receiptCode, String message) {
    return new DispatchResult(
        false, externalRequestId, receiptCode, false, false, message, null, null, true);
  }

  /** ADR-026 dry-run 演练成功 stub，不调外部 channel。 */
  public static DispatchResult success(
      String externalRequestId, String receiptCode, boolean acknowledged) {
    return new DispatchResult(
        true, externalRequestId, receiptCode, acknowledged, false, "dry-run", null);
  }
}
