package com.example.batch.worker.imports.infrastructure.quality;

import java.util.List;
import java.util.Map;

public record ValidationOutcome(
    Map<Long, ValidationIssue> recordIssues,
    List<ValidationIssue> datasetIssues,
    List<String> appliedChecks) {}
