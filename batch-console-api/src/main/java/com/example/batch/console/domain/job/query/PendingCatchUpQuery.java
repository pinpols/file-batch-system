package com.example.batch.console.domain.job.query;

import com.example.batch.common.model.PageRequest;
import lombok.Builder;

/**
 * catch-up 待审列表查询条件(7 字段)。字段多且多为可选过滤项,构造一律走 {@link #builder()} 具名赋值, 禁内联多 null 位参(QF-2 守护 {@code
 * QueryRecordConstructionConventionTest})。
 */
@Builder
public record PendingCatchUpQuery(
    String tenantId,
    String jobCode,
    String requestId,
    String bizDate,
    String keyword,
    PageRequest pageRequest,
    Long cursorId) {}
