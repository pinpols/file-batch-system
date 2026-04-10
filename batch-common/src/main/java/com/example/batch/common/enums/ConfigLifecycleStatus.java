package com.example.batch.common.enums;

public enum ConfigLifecycleStatus {
    DRAFT("DRAFT", "草稿"),
    PENDING_APPROVAL("PENDING_APPROVAL", "待审批"),
    PUBLISHED("PUBLISHED", "已发布"),
    GRAY("GRAY", "灰度"),
    ROLLED_BACK("ROLLED_BACK", "已回滚");

    private final String code;
    private final String label;

    ConfigLifecycleStatus(String code, String label) {
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
