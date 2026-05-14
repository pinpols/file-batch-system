package com.example.batch.common.verifier;

import java.util.Map;

/**
 * ContentVerifier 的结果。
 *
 * @param passed 验证是否通过；false 时调用方按业务策略决定是否阻塞任务推进
 * @param code 失败代码（passed=true 时 null）；命名习惯：{@code <SCOPE>_<REASON>}（如 {@code
 *     EXPORT_FILE_EMPTY}）便于告警规则匹配
 * @param message 失败描述（passed=true 时 null）
 * @param evidence 排障所需的少量证据（fileId / actualSize / expectedSize 等），会写入 outbox event 和 Micrometer
 *     tag（仅 code 作为 tag，避免 high-cardinality 爆炸）
 */
public record VerifyResult(
    boolean passed, String code, String message, Map<String, Object> evidence) {

  private static final VerifyResult PASS_SINGLETON = new VerifyResult(true, null, null, Map.of());

  public static VerifyResult pass() {
    return PASS_SINGLETON;
  }

  public static VerifyResult fail(String code, String message) {
    return new VerifyResult(false, code, message, Map.of());
  }

  public static VerifyResult fail(String code, String message, Map<String, Object> evidence) {
    return new VerifyResult(false, code, message, evidence == null ? Map.of() : evidence);
  }
}
