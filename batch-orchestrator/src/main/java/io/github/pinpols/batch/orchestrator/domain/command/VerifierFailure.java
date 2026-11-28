package io.github.pinpols.batch.orchestrator.domain.command;

import java.util.List;
import java.util.Map;

/** 固定 verifier 失败字段；evidence 保持开放 JSON，不将软告警误当作任务执行失败。 */
public record VerifierFailure(String code, String message, Object evidence) {

    /** 旧输入不改写；内部以空集合表示无失败，保留原 toString 归一化和 null 元素的位置语义。 */
    public static List<VerifierFailure> fromWire(List<Map<String, Object>> failures) {
        if (failures == null) {
            return List.of();
        }
        return failures.stream()
                .map(failure -> {
                    if (failure == null) {
                        return null;
                    }
                    Object codeValue = failure.get("code");
                    String code = stringValue(codeValue);
                    Object messageValue = failure.get("message");
                    String message = stringValue(messageValue);
                    Object evidence = failure.get("evidence");
                    return new VerifierFailure(code, message, evidence);
                })
                .toList();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
