package com.example.batch.common.enums;

public enum ApprovalCommandStatus {
    PENDING("PENDING", "待审批"),
    APPROVED("APPROVED", "已通过"),
    REJECTED("REJECTED", "已拒绝"),
    EXECUTED("EXECUTED", "已执行");

    private final String code;
    private final String label;

    ApprovalCommandStatus(String code, String label) {
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
