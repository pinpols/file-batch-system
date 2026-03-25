package com.example.batch.common.enums;

public enum WorkflowNodeType {
    TASK("TASK", "任务节点"),
    GATEWAY("GATEWAY", "网关节点"),
    FILE_STEP("FILE_STEP", "文件步骤"),
    START("START", "开始节点"),
    END("END", "结束节点"),
    JOB("JOB", "作业节点");

    private final String code;
    private final String label;

    WorkflowNodeType(String code, String label) {
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
