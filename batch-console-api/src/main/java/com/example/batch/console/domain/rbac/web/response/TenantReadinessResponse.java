package com.example.batch.console.domain.rbac.web.response;

import java.util.List;

/**
 * 租户就绪自检结果(ADR-026 dry-run 边界内:只看「配置完整性 / 会不会跑」,不看「业务结果对不对」)。
 *
 * <p>{@code blocking}:阻断性缺口(enabled 配置但关键字段空 / 悬空引用),不修则跑必失败;{@code warnings}:非阻断提示(可疑但能跑)。
 *
 * @param tenantId 被检租户
 * @param ready blocking 为空即就绪
 * @param blocking 阻断项清单
 * @param warnings 警告项清单
 */
public record TenantReadinessResponse(
    String tenantId, boolean ready, List<ReadinessItem> blocking, List<ReadinessItem> warnings) {

  /**
   * 单条就绪项。
   *
   * @param item 检查项类别(如 template / channel / queue / job)
   * @param reason 原因(i18n 由 FE 按需,本字段为可读英文摘要)
   * @param ref 关联配置引用(如 templateCode / channelCode / queueCode / jobCode)
   */
  public record ReadinessItem(String item, String reason, String ref) {}
}
