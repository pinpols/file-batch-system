package io.github.pinpols.batch.worker.dispatchs.stage;

import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchCommand;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchManifestRef;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.Map;

/**
 * Dispatch 链路两条 step（{@link DeliverDispatchStep} / {@link RetryDispatchStep}）共用的"执行 dispatch →
 * 把识别码回写到 context → markSent / markFailed"骨架。
 *
 * <p>差异点（dry-run 处理、record insert vs increment、next-stage 跳转）保留在各 step 自己里，本 helper 只承担两侧严格一致的那段
 * ~60 行重复：构造 DispatchCommand → 调 gateway → context 写回 externalRequestId / receiptCode /
 * receiptStatus → 根据 success 调 markSent 或 markFailed。
 */
final class DispatchInvocationSupport {

  private DispatchInvocationSupport() {}

  /** 派发结果状态机：根据 ACK / 待回执 / 未确认推导出 receiptStatus 字符串。 */
  static String receiptStatusOf(DispatchResult dispatchResult) {
    if (dispatchResult.acknowledged()) {
      return "SUCCESS";
    }
    return dispatchResult.receiptPending() ? "PENDING" : "NONE";
  }

  /**
   * 执行真实派发：构造 DispatchCommand 调 gateway，把 externalRequestId / receiptCode / receiptStatus 写回
   * context。dryRun 场景由调用方在外层决定是否使用伪造结果（通过 DryRunGuard.callOrSkip）。
   *
   * @return gateway 返回的派发结果
   */
  static DispatchResult invokeAndRecordIdentifiers(
      DispatchChannelGateway gateway,
      DispatchJobContext context,
      Map<String, Object> fileRecord,
      Map<String, Object> channelConfig,
      DispatchPayload dispatchPayload) {
    DispatchResult dispatchResult =
        gateway.dispatch(
            new DispatchCommand(
                context.getTenantId(),
                String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID)),
                fileRecord,
                channelConfig,
                dispatchPayload));
    propagateIdentifiers(context, dispatchResult);
    return dispatchResult;
  }

  /** 把 DispatchResult 内的识别码与状态写到 context.attributes，供后续 step 与 receipt watcher 使用。 */
  static void propagateIdentifiers(DispatchJobContext context, DispatchResult dispatchResult) {
    Map<String, Object> attrs = context.getAttributes();
    attrs.put("dispatchResult", dispatchResult);
    attrs.put("externalRequestId", dispatchResult.externalRequestId());
    attrs.put("receiptCode", dispatchResult.receiptCode());
    attrs.put("receiptStatus", receiptStatusOf(dispatchResult));
    DispatchManifestRef manifestRef = dispatchResult.manifestRef();
    if (manifestRef != null) {
      manifestRef.putAttributes(attrs);
    }
  }

  /** 投递成功时统一 markSent；返回受影响行数（≤0 表示无对应 dispatch 记录，调用方需当作失败处理）。 */
  static int markSent(
      FileDispatchRepository repository,
      DispatchJobContext context,
      Long fileId,
      DispatchPayload dispatchPayload,
      DispatchResult dispatchResult) {
    return repository.markSent(
        context.getTenantId(),
        fileId,
        dispatchPayload.channelCode(),
        dispatchResult.externalRequestId(),
        dispatchResult.receiptCode(),
        receiptStatusOf(dispatchResult));
  }
}
