package com.example.batch.orchestrator.controller.request;

import java.util.List;

/** ADR-046 P2 切片 2.2:批量上报响应 —— 逐项结果,worker 据此只重报 ok=false 的项。 */
public record TaskReportBatchResponse(List<TaskReportResultPayload> results) {}
