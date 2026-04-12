package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileReceiptPolicy {
    NONE("NONE", "无回执"),
    SYNC("SYNC", "同步"),
    ASYNC("ASYNC", "异步"),
    POLLING("POLLING", "轮询");

    private final String code;
    private final String label;

    FileReceiptPolicy(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(FileReceiptPolicy::code).collect(Collectors.toUnmodifiableSet());
    }
}
