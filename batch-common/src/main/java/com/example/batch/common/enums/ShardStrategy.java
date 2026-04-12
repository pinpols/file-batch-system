package com.example.batch.common.enums;

import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum ShardStrategy {
    NONE("NONE", "不分片"),
    STATIC("STATIC", "静态分片"),
    DYNAMIC("DYNAMIC", "动态分片"),
    AUTO("AUTO", "自动分片");

    private final String code;
    private final String label;

    ShardStrategy(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static ShardStrategy fromCode(String code) {
        if (!StringUtils.hasText(code)) {
            return NONE;
        }
        for (ShardStrategy candidate : values()) {
            if (candidate.code.equalsIgnoreCase(code.trim())) {
                return candidate;
            }
        }
        return NONE;
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(ShardStrategy::code).collect(Collectors.toUnmodifiableSet());
    }
}
