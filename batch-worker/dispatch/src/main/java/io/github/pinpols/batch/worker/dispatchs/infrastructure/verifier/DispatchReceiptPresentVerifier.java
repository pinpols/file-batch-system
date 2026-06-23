package io.github.pinpols.batch.worker.dispatchs.infrastructure.verifier;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.common.verifier.ContentVerifier;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ADR-030 示例 verifier：DISPATCH 任务必须有回执标识。
 *
 * <p>判定逻辑：DISPATCH task 在 worker 提交远端通道（API / NAS / OSS / SFTP）后，必须返回 {@code receiptCode}（或同义的
 * {@code externalRequestId}）；二者皆缺 → 记 {@code DISPATCH_RECEIPT_MISSING} 失败，避免"任务成功但下游无凭证可追踪"。
 *
 * <p>对应的输出 key 与 {@code CLAUDE.md §Workflow 节点参数 DSL 规范} 中 DISPATCH worker 输出 schema 一致（receiptCode
 * / externalRequestId）。
 */
@Component
public class DispatchReceiptPresentVerifier implements ContentVerifier {

  @Override
  public String code() {
    return "DISPATCH_RECEIPT_PRESENT";
  }

  @Override
  public Set<JobType> appliesTo() {
    return Set.of(JobType.DISPATCH);
  }

  @Override
  public VerifyResult verify(VerifyContext context) {
    String receiptCode = stringValue(context.property("receiptCode"));
    String externalRequestId = stringValue(context.property("externalRequestId"));
    if (Texts.hasText(receiptCode) || Texts.hasText(externalRequestId)) {
      return VerifyResult.pass();
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("channelCode", context.property("channelCode"));
    evidence.put("fileId", context.property("fileId"));
    return VerifyResult.fail(
        "DISPATCH_RECEIPT_MISSING",
        "DISPATCH task reported success but no receiptCode/externalRequestId returned",
        evidence);
  }

  private static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }
}
