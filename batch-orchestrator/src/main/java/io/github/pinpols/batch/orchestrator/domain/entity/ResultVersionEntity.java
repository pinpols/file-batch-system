package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Builder;

/**
 * 结果版本投影（ADR-017）。MyBatis 通过 {@code resultMap+constructor} 映射为不可变 record。
 *
 * <p>语义：
 *
 * <ul>
 *   <li>{@code business_key} 形如 {@code job:{jobCode}:{bizDate}} / {@code
 *       workflow:{wfCode}:{bizDate}};
 *   <li>同 {@code (tenant_id, business_key)} 内 partial unique index 保证至多 1 行 {@code
 *       status='EFFECTIVE'};
 *   <li>{@code job_instance_id} 永远引用 SUCCESS / PARTIAL_SUCCESS 终态实例;
 *   <li>{@code payload_storage='INLINE_JSON'} 时直接用 {@code payload_json};{@code EXTERNAL_REF} /
 *       {@code FILE_RECORD} 时 {@code payload_ref} 是 {@code oss://...} 或 {@code file_record:{id}}。
 * </ul>
 *
 * <p><b>不要加 Spring Data 注解</b>—— 本表走 MyBatis；持久化由 {@code ResultVersionMapper} 接管。
 */
@Builder(toBuilder = true)
@SuppressWarnings("PMD.ExcessiveParameterList")
public record ResultVersionEntity(
    Long id,
    String tenantId,
    String businessKey,
    Integer versionNo,
    Long jobInstanceId,
    String status,
    Instant effectiveAt,
    Instant deactivatedAt,
    String payloadStorage,
    String payloadJson,
    String payloadRef,
    Instant generatedAt,
    String generatedBy,
    String promotionPolicy,
    Long approvalId,
    Instant createdAt,
    Instant updatedAt,
    /** ADR-021 DQ gate 结果：PASS / WARN / BLOCKED；NULL = 无规则关联，未跑 gate。 */
    String dqGateStatus) {

  /** 兼容旧调用：不带 dqGateStatus 的 17 元构造（V117 之前的调用方）。 */
  public ResultVersionEntity(
      Long id,
      String tenantId,
      String businessKey,
      Integer versionNo,
      Long jobInstanceId,
      String status,
      Instant effectiveAt,
      Instant deactivatedAt,
      String payloadStorage,
      String payloadJson,
      String payloadRef,
      Instant generatedAt,
      String generatedBy,
      String promotionPolicy,
      Long approvalId,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        tenantId,
        businessKey,
        versionNo,
        jobInstanceId,
        status,
        effectiveAt,
        deactivatedAt,
        payloadStorage,
        payloadJson,
        payloadRef,
        generatedAt,
        generatedBy,
        promotionPolicy,
        approvalId,
        createdAt,
        updatedAt,
        null);
  }
}
