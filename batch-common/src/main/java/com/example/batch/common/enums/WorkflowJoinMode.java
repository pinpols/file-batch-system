package com.example.batch.common.enums;

public enum WorkflowJoinMode {
    ALL("ALL", "全部满足后汇聚"),
    ANY("ANY", "任一满足即汇聚"),
    N_OF("N_OF", "满足指定数量后汇聚");

    private final String code;
    private final String label;

    WorkflowJoinMode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static WorkflowJoinMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return ALL;
        }
        for (WorkflowJoinMode value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return ALL;
    }
}
