package io.github.pinpols.batch.worker.dispatchs.infrastructure;

/**
 * Dispatch 链路 {@code context.attributes} 的共享键。
 *
 * <p>这些键只在 dispatch worker 内部流通（写方：{@code DispatchInvocationSupport} / 各 stage step；
 * 读方：后续 step、receipt watcher 与 {@code DispatchStepExecutionAdapter}），因此不像
 * {@link io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys} 那样跨 worker 模块共享——
 * 与 {@code ShellAtomicHandler} 自持 {@code java.io.tmpdir} 常量同理：为字符串复用引入跨模块依赖不划算。
 *
 * <p>跨模块复用的键（如 {@code dryRunSkipped}）不放在这里，登记在 {@code PipelineRuntimeKeys}。
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

  /** 渠道编码键：dispatch payload 字段、file_record metadata 与审计明细三处同名同义。 */
  public static final String CHANNEL_CODE = "channelCode";

  /** 分发目标键：语义同 {@link #CHANNEL_CODE}，三处同名同义。 */
  public static final String DISPATCH_TARGET = "dispatchTarget";

  private DispatchRuntimeKeys() {}
}
