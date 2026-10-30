package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.LocalDate;

/** Mapper 的租户及可选筛选条件，分页不参与计数。 */
public record PersistentWaitReadFilter(
    String tenantId,
    String waitType,
    String waitStatus,
    Long workflowDefinitionId,
    LocalDate bizDateFrom,
    LocalDate bizDateTo) {}
