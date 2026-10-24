package io.github.pinpols.batch.console.domain.audit.support;

import lombok.Builder;

import java.time.Instant;
/**
 * 通用控制台操作审计事件 — Aspect 构造,Service 写入数据库。
 *
 * <p>字段名 / 顺序与 DB 表 {@code batch.console_operation_audit} 一致,**也是将来 Kafka payload 的 schema**:迁移到
 * Kafka 时把这条 record 直接序列化成 JSON 发到 topic 即可,消费者按 {@link #eventVersion} 分支处理新老格式。
 *
 * <p>所以新增字段务必:
 *
 * <ol>
 *   <li>保持新增字段可选，兼容既有消费者
 *   <li>同步维护 {@code eventVersion}
 *   <li>数据库迁移为新增列提供兼容默认值
 * </ol>
 *
 * <p>调用侧通过 Builder 按字段名构造可选审计信息；record 字段顺序、公开构造器和序列化契约保持不变。
 */
@Builder
public record OperationAuditEvent(
    String tenantId,
    String aggregateType,
    String aggregateId,
    String action,
    String operatorId,
    String operatorRole,
    String result,
    String errorCode,
    String errorMessage,
    String paramsJson,
    String traceId,
    String requestId,
    String ipHash,
    String uaHash,
    int eventVersion,
    Instant createdAt) {}
