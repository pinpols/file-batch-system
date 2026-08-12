package io.github.pinpols.batch.console.shared.command;

import io.github.pinpols.batch.common.enums.TriggerType;
import java.util.Map;

/**
 * Console 委派 trigger 的已解析命令。
 *
 * <p>它把租户、触发类型、业务参数和幂等键作为一个不可拆分的操作上下文传递，避免调用方在新增字段时继续扩大跨层方法签名。
 */
public record ConsoleLaunchCommand(
    String tenantId,
    String jobCode,
    String bizDate,
    TriggerType triggerType,
    Map<String, Object> params,
    String idempotencyKey) {}
