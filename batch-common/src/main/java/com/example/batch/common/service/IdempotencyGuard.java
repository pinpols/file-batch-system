package com.example.batch.common.service;

/**
 * D-1: 幂等守卫接口，保证同一 idempotencyKey 对应的操作最多执行一次。
 * 底层依赖 {@code batch.idempotency_record} 表的唯一约束实现原子性。
 */
public interface IdempotencyGuard {

    String executeOnce(String tenantId, String idempotencyKey, IdempotentAction action);

    boolean isAlreadyExecuted(String tenantId, String idempotencyKey);

    @FunctionalInterface
    interface IdempotentAction {
        String execute();
    }
}
