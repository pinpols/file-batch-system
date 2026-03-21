package com.example.batch.common.enums;

public enum WorkflowRunStatus {
    CREATED("CREATED", "已创建"),
    RUNNING("RUNNING", "执行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    TERMINATED("TERMINATED", "已终止");

    private final String code;
    private final String label;

    WorkflowRunStatus(String code, String label) {
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
