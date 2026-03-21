package com.example.batch.common.enums;

public enum WorkflowNodeCode {
    START("START", "开始"),
    END("END", "结束");

    private final String code;
    private final String label;

    WorkflowNodeCode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
