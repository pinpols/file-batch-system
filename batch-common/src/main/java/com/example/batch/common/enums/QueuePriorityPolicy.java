package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum QueuePriorityPolicy {
    FIFO("FIFO", "先进先出"),
    PRIORITY("PRIORITY", "优先级"),
    FAIR_SHARE("FAIR_SHARE", "公平共享");

    private final String code;
    private final String label;

    QueuePriorityPolicy(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(QueuePriorityPolicy::code).collect(Collectors.toUnmodifiableSet());
    }
}
