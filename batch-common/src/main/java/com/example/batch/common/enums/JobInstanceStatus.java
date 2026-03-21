package com.example.batch.common.enums;

public enum JobInstanceStatus {
    CREATED("CREATED", "已创建"),
    WAITING("WAITING", "等待中"),
    READY("READY", "待执行"),
    RUNNING("RUNNING", "执行中"),
    PARTIAL_FAILED("PARTIAL_FAILED", "部分失败"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    CANCELLED("CANCELLED", "已取消"),
    TERMINATED("TERMINATED", "已终止");

    private final String code;
    private final String label;

    JobInstanceStatus(String code, String label) {
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
