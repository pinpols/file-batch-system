package com.example.batch.console.support.audit;

import java.time.Instant;

/**
 * 通用控制台操作审计事件 — Aspect 构造,Service 落库。
 *
 * <p>字段名 / 顺序与 DB 表 {@code batch.console_operation_audit} 一致,**也是将来 Kafka payload 的 schema**:迁移到
 * Kafka 时把这条 record 直接序列化成 JSON 发到 topic 即可,消费者按 {@link #eventVersion} 分支处理新老格式。
 *
 * <p>所以新增字段务必:
 *
 * <ol>
 *   <li>非必填(老消费者读到 null 不挂)
 *   <li>同步升级 {@code eventVersion}
 *   <li>DB migration 给列 default 兜底
 * </ol>
 *
 * <p>没有静态工厂方法:构造器 canonical(record)受 CLAUDE.md 豁免,Aspect 用 canonical 构造器 直接传 16 个字段(用 null
 * 占位选填项),避免 11-参数 of() 触发参数数量约束。
 */
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
