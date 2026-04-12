package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum WorkflowEdgeType {
    SUCCESS("SUCCESS", "成功"),
    FAILURE("FAILURE", "失败"),
    CONDITION("CONDITION", "条件"),
    ALWAYS("ALWAYS", "总是");

    private final String code;
    private final String label;

    WorkflowEdgeType(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(WorkflowEdgeType::code).collect(Collectors.toUnmodifiableSet());
    }
}
