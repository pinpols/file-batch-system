package com.example.batch.console.domain.query;

import com.example.batch.common.model.PageRequest;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
public record JobInstanceQuery(
    String tenantId,
    String jobCode,
    String instanceStatus,
    /** 多状态过滤;非空时 IN (...) 查询,优先于 instanceStatus 单值。 */
    List<String> instanceStatuses,
    String instanceNo,
    String bizDate,
    String traceId,
    Instant startedFrom,
    Instant startedTo,
    String sortBy,
    Integer minDurationSeconds,
    Boolean slaBreached,
    PageRequest pageRequest,
    /**
     * 双轨分页 cursor 模式(ADR-031):非 null 时 Mapper 走 cursor 谓词(id &lt; #{cursorId} 或类似),不读
     * pageRequest.pageNo。 形态:CursorCodec.decode 出来的 {@code id} long 值;null 表示首页或 offset 模式。
     */
    Long cursorId) {}
