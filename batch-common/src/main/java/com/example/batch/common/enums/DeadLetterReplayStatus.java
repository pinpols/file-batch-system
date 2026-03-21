package com.example.batch.common.enums;

public enum DeadLetterReplayStatus {
    NEW("NEW", "新建"),
    REPLAYING("REPLAYING", "重放中"),
    SUCCESS("SUCCESS", "重放成功"),
    FAILED("FAILED", "重放失败"),
    GIVE_UP("GIVE_UP", "放弃处理");

    private final String code;
    private final String label;

    DeadLetterReplayStatus(String code, String label) {
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
