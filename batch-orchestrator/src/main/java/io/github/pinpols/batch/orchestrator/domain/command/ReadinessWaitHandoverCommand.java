package io.github.pinpols.batch.orchestrator.domain.command;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * readiness 等待移交命令：Trigger 在 defer 后移交的原 fire 身份（原依赖、调度版本、业务日与等待窗口）。
 *
 * <p>由 HTTP 入口组装，{@code ReadinessWaitService} 入口校验并在用例内部构建持久化 Entity。
 */
public record ReadinessWaitHandoverCommand(
    String tenantId,
    Long workflowDefinitionId,
    String workflowCode,
    String calendarCode,
    Integer scheduleVersion,
    List<String> dependsOnJobCodes,
    LocalDate bizDate,
    Instant scheduledFireTime,
    Instant deferredSince,
    Instant deadline,
    String traceId) {}
