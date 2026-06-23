package io.github.pinpols.batch.worker.imports.web;

import lombok.Data;

/**
 * 对象到达事件通知体(事件驱动到达,ADR 路线图 4.1)。
 *
 * <p>由对象存储事件源 POST 进来——可以是 S3 EventBridge/SNS、MinIO bucket notification,或任意自建 poller/网关:
 * 凡能在对象落地时发一个 HTTP 通知的来源皆可,**不绑定云厂商**。
 *
 * <p>v1 语义是「有东西到了,现在扫一次」:字段全部可选,仅用于日志定位。
 *
 * <p>实际发现仍交既有 {@code ImportIngressScanner} 按配置前缀扫描登记,不绕过 Trigger/Orchestrator。
 */
@Data
public class ObjectArrivalNotification {

  /** 租户 id(可选,仅日志定位用)。 */
  private String tenantId;

  /** 对象存储 bucket(可选,仅日志定位用)。 */
  private String bucket;

  /** 到达对象 key(可选,仅日志定位用)。 */
  private String objectKey;
}
