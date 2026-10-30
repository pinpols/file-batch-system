package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import io.github.pinpols.batch.common.enums.DispatchTaskKeys;

/**
 * Dispatch Worker 的运行键；跨控制面/Worker 的 payload 键由 {@link DispatchTaskKeys} 定义。
 *
 * <p>其余键在 Dispatch Worker 内部流通；除非出现跨模块契约，不在公共模块登记。
 *
 * <p>审计明细（{@code detailSummary}）键、出站 payload 键与 attributes 键同名，按既有约定复用同一常量。
 */
public final class DispatchRuntimeKeys {

  public static final String DISPATCH_PAYLOAD = "dispatchPayload";
  public static final String DISPATCH_RESULT = "dispatchResult";
  public static final String DISPATCH_MANIFEST_REF = "dispatchManifestRef";
  public static final String DISPATCH_RECORD = "dispatchRecord";
  public static final String EXTERNAL_REQUEST_ID = "externalRequestId";
  public static final String RECEIPT_CODE = "receiptCode";
  public static final String RECEIPT_STATUS = "receiptStatus";
  public static final String RETRY_REQUESTED = "retryRequested";
  public static final String RETRY_RECOVERED = "retryRecovered";
  public static final String DISPATCH_SECRET_BINDINGS = DispatchTaskKeys.SECRET_BINDINGS;

  /** 渠道编码键：dispatch payload 字段、file_record metadata 与审计明细三处同名同义。 */
  public static final String CHANNEL_CODE = "channelCode";

  /** 分发目标键：语义同 {@link #CHANNEL_CODE}，三处同名同义。 */
  public static final String DISPATCH_TARGET = "dispatchTarget";

  private DispatchRuntimeKeys() {}
}
