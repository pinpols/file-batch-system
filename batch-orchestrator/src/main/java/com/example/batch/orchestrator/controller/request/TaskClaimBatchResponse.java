package com.example.batch.orchestrator.controller.request;

import java.util.List;

/** ADR-046 P2 切片 2.1:批量认领响应 —— 逐项结果,worker 据此只处理 claimed=true 的子集。 */
public record TaskClaimBatchResponse(List<TaskClaimResultPayload> results) {}
