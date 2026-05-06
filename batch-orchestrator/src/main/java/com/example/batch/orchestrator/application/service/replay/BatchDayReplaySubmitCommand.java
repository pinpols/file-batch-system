package com.example.batch.orchestrator.application.service.replay;

import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/** ADR-020 batch_day_replay 提交命令。 */
@Builder
public record BatchDayReplaySubmitCommand(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String scope,
    /** SUBSET_JOB_CODES 时填；其他 scope 忽略。 */
    List<String> jobCodes,
    /** OUTPUTS_ONLY 时填具体要 promote 的 result_version id；其他 scope 忽略。 */
    List<Long> versionIds,
    String resultPolicy,
    String configVersionPolicy,
    Integer configVersion,
    String reason,
    String requestedBy,
    /** true → 跳过审批直接 RUNNING；false → PENDING_APPROVAL 等审批。 */
    boolean autoApprove,
    String traceId) {}
