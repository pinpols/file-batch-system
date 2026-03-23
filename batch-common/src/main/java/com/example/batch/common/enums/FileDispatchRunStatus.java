package com.example.batch.common.enums;

public enum FileDispatchRunStatus {
    CREATED("CREATED", "已创建"),
    RUNNING("RUNNING", "执行中"),
    COMPENSATING("COMPENSATING", "补偿中"),
    ARCHIVED("ARCHIVED", "已归档");

    private final String code;
    private final String label;

    FileDispatchRunStatus(String code, String label) {
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
