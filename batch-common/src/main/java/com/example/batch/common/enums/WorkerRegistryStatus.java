package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    public static Set<String> codes() {
        return Arrays.stream(values()).map(WorkerRegistryStatus::code).collect(Collectors.toUnmodifiableSet());
    }
}
