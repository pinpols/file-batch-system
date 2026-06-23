package com.example.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * ADR-046 P2 切片 2.2:批量上报请求 —— 一次 HTTP 往返上报 K 个独立 partition 的执行结果。 每项即单条 {@link
 * TaskExecutionReportDto}(自带 taskId),逐项独立事务推进,互不影响。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskReportBatchRequest(List<TaskExecutionReportDto> items) {}
