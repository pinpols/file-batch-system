package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum WorkflowType {
    DAG("DAG", "DAG"),
    PIPELINE("PIPELINE", "流水线"),
    MIXED("MIXED", "混合");

    private final String code;
    private final String label;

    WorkflowType(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(WorkflowType::code).collect(Collectors.toUnmodifiableSet());
    }
}
