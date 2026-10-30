package io.github.pinpols.batch.orchestrator.domain.query;

import lombok.Builder;

/** 托管上传孤儿筛选参数；保留模式、到达状态、TTL 和批次上限。 */
@Builder
public record FileGovernanceOrphanUploadQuery(
    String receivedStatus,
    String uploadMode,
    String waitingArrivalState,
    Long ttlSeconds,
    Integer limit) {}
