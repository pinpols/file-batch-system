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
   * <p>{@code hint} / {@code docRef} 是「报怎么补」的可操作引导(向后兼容新增字段):告诉填写人具体在哪个 sheet 填哪个字段、参考哪份文档,
   * 而不是只报「缺什么」。两字段对 blocking 项必填具体值,对 warning 项可为空。
   *
   * @param item 检查项类别(如 template / channel / queue / job)
   * @param reason 原因(i18n 由 FE 按需,本字段为可读英文摘要)
   * @param ref 关联配置引用(如 templateCode / channelCode / queueCode / jobCode)
   * @param hint 怎么填的可操作提示(如「在配置模板 file_template_config sheet 填
   *     default_query_sql,参考『四类Worker示例』」),可为空
   * @param docRef 指向 quickstart 文档 / 字段说明的引用路径,可为空
   */
  public record ReadinessItem(String item, String reason, String ref, String hint, String docRef) {

    /** 兼容旧调用方:无 hint / docRef 的三参构造(hint / docRef 置空)。 */
    public ReadinessItem(String item, String reason, String ref) {
      this(item, reason, ref, null, null);
    }
  }
}
