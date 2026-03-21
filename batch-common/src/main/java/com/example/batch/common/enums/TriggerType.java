package com.example.batch.common.enums;

public enum TriggerType {
    API("API", "接口触发"),
    MANUAL("MANUAL", "手工触发"),
    EVENT("EVENT", "事件触发"),
    CATCH_UP("CATCH_UP", "补跑触发"),
    SCHEDULED("SCHEDULED", "定时触发");

    private final String code;
    private final String label;

    TriggerType(String code, String label) {
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
