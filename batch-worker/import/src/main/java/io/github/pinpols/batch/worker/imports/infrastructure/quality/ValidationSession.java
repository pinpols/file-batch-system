package io.github.pinpols.batch.worker.imports.infrastructure.quality;

import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ValidationSession(
    ImportJobContext context,
    Map<String, Object> ruleSet,
    long totalCount,
    String normalizedPayload,
    ImportPayload importPayload,
    List<String> schemaFields,
    Map<String, Set<String>> seenValues,
    List<ValidationIssue> datasetIssues,
    Map<Long, ValidationIssue> recordIssues,
    Set<String> appliedChecks) {}
