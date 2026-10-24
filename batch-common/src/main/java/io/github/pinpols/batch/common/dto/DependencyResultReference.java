package io.github.pinpols.batch.common.dto;

import java.time.Instant;
import java.time.LocalDate;

/** EFFECTIVE 上游结果被下游启动门禁观察到后形成的不可变标识。 */
public record DependencyResultReference(
    String upstreamJobCode,
    String bizDateAlignment,
    LocalDate upstreamBizDate,
    String businessKey,
    Integer versionNo,
    Long resultVersionId,
    Long sourceJobInstanceId,
    Instant effectiveAt) {}
