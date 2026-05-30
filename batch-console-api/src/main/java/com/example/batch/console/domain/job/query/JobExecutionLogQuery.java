package com.example.batch.console.domain.job.query;

import com.example.batch.common.model.PageRequest;

/**
 * {@code batch.job_execution_log} 查询条件。jobInstanceId 必填(日志查看锚定单个实例),其余可选过滤。
 *
 * @param cursorId 双轨分页 cursor(ADR-031):非 null 时走 {@code id &lt; #{cursorId}} 谓词,order by id desc。
 */
public record JobExecutionLogQuery(
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String logLevel,
    String logType,
    String keyword,
    PageRequest pageRequest,
    Long cursorId) {}
