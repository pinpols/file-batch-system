package io.github.pinpols.batch.console.domain.job.query;

import io.github.pinpols.batch.common.model.PageRequest;

/**
 * {@code batch.job_execution_log} 查询条件。普通日志查看锚定单个实例,trace snapshot 内部查询可只传 traceId。
 *
 * @param cursorId 双轨分页 cursor(ADR-031):非 null 时走 {@code id &lt; #{cursorId}} 谓词,order by id desc。
 */
public record JobExecutionLogQuery(
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String logLevel,
    String logType,
    String traceId,
    String keyword,
    PageRequest pageRequest,
    Long cursorId) {}
