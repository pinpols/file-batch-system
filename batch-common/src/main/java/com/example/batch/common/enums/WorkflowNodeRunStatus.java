package com.example.batch.common.enums;

public enum WorkflowNodeRunStatus {
    READY("READY", "待执行"),
    RUNNING("RUNNING", "执行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    SKIPPED("SKIPPED", "跳过");

    private final String code;
    private final String label;

    WorkflowNodeRunStatus(String code, String label) {
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
