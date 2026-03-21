package com.example.batch.common.enums;

public enum RetryPolicyType {
    NONE("NONE", "不重试"),
    FIXED("FIXED", "固定间隔"),
    EXPONENTIAL("EXPONENTIAL", "指数退避");

    private final String code;
    private final String label;

    RetryPolicyType(String code, String label) {
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
