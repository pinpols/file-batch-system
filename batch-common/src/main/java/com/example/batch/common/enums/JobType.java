package com.example.batch.common.enums;

public enum JobType {
    GENERAL("GENERAL", "通用任务"),
    IMPORT("IMPORT", "导入任务"),
    EXPORT("EXPORT", "导出任务"),
    DISPATCH("DISPATCH", "分发任务"),
    WORKFLOW("WORKFLOW", "工作流任务");

    private final String code;
    private final String label;

    JobType(String code, String label) {
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
