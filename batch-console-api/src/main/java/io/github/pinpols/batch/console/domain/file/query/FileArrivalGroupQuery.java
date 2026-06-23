package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;

public record FileArrivalGroupQuery(
    String tenantId,
    String fileGroupCode,
    String arrivalState,
    Instant fromTime,
    Instant toTime,
    PageRequest pageRequest) {}
