package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    public static Set<String> codes() {
        return Arrays.stream(values()).map(ApprovalCommandStatus::code).collect(Collectors.toUnmodifiableSet());
    }
}
