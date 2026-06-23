package io.github.pinpols.batch.console.web.response;

import java.util.List;

/**
 * 维护状态响应。
 *
 * @param enabled 维护开关
 * @param readOnly true=只读放行 GET,false=整站 503
 * @param message 用户可见提示
 * @param etaAt 预计恢复时间(ISO-8601 字符串)
 * @param affectedServices 受影响子系统 code 列表(空 list=整站)
 */
public record MaintenanceStatusResponse(
    boolean enabled,
    boolean readOnly,
    String message,
    String etaAt,
    List<String> affectedServices) {}
