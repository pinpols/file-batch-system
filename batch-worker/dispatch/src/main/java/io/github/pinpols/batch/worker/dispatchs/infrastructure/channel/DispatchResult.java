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
    DispatchManifestRef manifestRef) {

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
        null);
  }

  /** ADR-026 dry-run 演练成功 stub，不调外部 channel。 */
  public static DispatchResult success(
      String externalRequestId, String receiptCode, boolean acknowledged) {
    return new DispatchResult(
        true, externalRequestId, receiptCode, acknowledged, false, "dry-run", null);
  }
}
