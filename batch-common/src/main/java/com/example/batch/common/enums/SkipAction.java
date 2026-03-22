package com.example.batch.common.enums;

public enum SkipAction {
    CONTINUE("CONTINUE", "继续"),
    FAIL_BATCH("FAIL_BATCH", "失败整批"),
    MANUAL_REVIEW("MANUAL_REVIEW", "人工复核");

    private final String code;
    private final String label;

    SkipAction(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static SkipAction fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (SkipAction action : values()) {
            if (action.code.equalsIgnoreCase(code.trim())) {
                return action;
            }
        }
        return null;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
