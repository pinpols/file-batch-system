package io.github.pinpols.batch.orchestrator.domain.query;

/**
 * 一次 outbox 领取的凭据：{@code publish_attempt} 每次领取自增且从不复位，用它区分同一事件的前后两轮发布者。
 */
public record OutboxPublishClaim(Long id, Integer publishAttempt) {}
