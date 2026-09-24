package io.github.pinpols.batch.common.http;

/** 出站 HTTP 地址解析策略。 */
public enum OutboundAddressPolicy {
  /** 租户或外部可配置地址：解析结果任一地址受限即整体拒绝。 */
  GUARDED,
  /** 运维固定地址：允许集群私网，由系统 DNS 返回完整地址列表。 */
  TRUSTED
}
