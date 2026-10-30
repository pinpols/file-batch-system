package io.github.pinpols.batch.console.application.contract.response;

import java.util.List;

/**
 * 维护状态响应。
 *
 * @param enabled 维护开关
 * @param readOnly true=只读放行 GET,false=整站 503
 * @param message 用户可见提示
 * @param etaAt 预计恢复时间(ISO-8601 字符串)
 * @param startsAt 计划开始时间(ISO-8601 字符串);空表示启用后立即生效
 * @param active 当前是否已进入实际维护状态
 * @param affectedServices 公告服务标记；固定应用编码限定通知抑制范围，空列表仅缺省到 Console
 * @param version 共享维护状态版本
 * @param updatedAt 最近一次共享状态更新时间(ISO-8601 字符串)
 */
public record MaintenanceStatusResponse(
    boolean enabled,
    boolean readOnly,
    String message,
    String etaAt,
    String startsAt,
    boolean active,
    List<String> affectedServices,
    long version,
    String updatedAt) {}
