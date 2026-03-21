package com.example.batch.common.enums;

public enum RetryScheduleStatus {
    WAITING("WAITING", "待重试"),
    RUNNING("RUNNING", "执行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    EXHAUSTED("EXHAUSTED", "已耗尽"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String label;

    RetryScheduleStatus(String code, String label) {
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
