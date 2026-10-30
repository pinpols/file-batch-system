package io.github.pinpols.batch.sdk.task;

import java.time.Instant;
import java.time.LocalDate;

/** 启动时由 Orchestrator 依赖门禁观察到的上游结果标识。 */
public record SdkDependencyResultReference(
    String upstreamJobCode,
    String bizDateAlignment,
    LocalDate upstreamBizDate,
    String businessKey,
    Integer versionNo,
    Long resultVersionId,
    Long sourceJobInstanceId,
    Instant effectiveAt) {}
