package com.example.batch.common.enums;

public enum CatchUpPolicyType {
    NONE("NONE", "不补跑"),
    AUTO("AUTO", "自动补跑"),
    MANUAL_APPROVAL("MANUAL_APPROVAL", "人工审批");

    private final String code;
    private final String label;

    CatchUpPolicyType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static CatchUpPolicyType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return NONE;
        }
        for (CatchUpPolicyType value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return NONE;
    }
}
