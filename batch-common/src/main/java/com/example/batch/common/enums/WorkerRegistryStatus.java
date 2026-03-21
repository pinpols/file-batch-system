package com.example.batch.common.enums;

public enum WorkerRegistryStatus {
    ONLINE("ONLINE", "在线"),
    OFFLINE("OFFLINE", "离线"),
    DRAINING("DRAINING", "排空中"),
    DECOMMISSIONED("DECOMMISSIONED", "已下线");

    private final String code;
    private final String label;

    WorkerRegistryStatus(String code, String label) {
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
