package io.github.pinpols.batch.common.dto;

import java.time.Instant;
import java.time.LocalDate;

/** Immutable identity of an EFFECTIVE upstream result observed by a downstream launch gate. */
public record DependencyResultReference(
    String upstreamJobCode,
    String bizDateAlignment,
    LocalDate upstreamBizDate,
    String businessKey,
    Integer versionNo,
    Long resultVersionId,
    Long sourceJobInstanceId,
    Instant effectiveAt) {}
