package io.github.pinpols.batch.console.application.ops.query;

import java.time.LocalDate;

/** Console 到 Orchestrator 的持久等待查询条件；保留既有筛选与分页口径。 */
public record ConsolePersistentWaitQuery(
    String tenantId,
    String waitType,
    String waitStatus,
    Long workflowDefinitionId,
    LocalDate bizDateFrom,
    LocalDate bizDateTo,
    int pageNo,
    int pageSize) {}
