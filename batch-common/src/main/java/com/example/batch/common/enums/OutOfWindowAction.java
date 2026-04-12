package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum OutOfWindowAction {
    WAIT("WAIT", "等待下次窗口"),
    FAIL("FAIL", "失败");

    private final String code;
    private final String label;

    OutOfWindowAction(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(OutOfWindowAction::code).collect(Collectors.toUnmodifiableSet());
    }
}
