package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum AiPromptCategory {
    PLATFORM("PLATFORM", "平台咨询"),
    FILE_GOVERNANCE("FILE_GOVERNANCE", "文件治理"),
    WORKFLOW("WORKFLOW", "工作流"),
    OPERATIONS("OPERATIONS", "运维操作"),
    OUT_OF_SCOPE("OUT_OF_SCOPE", "超出范围");

    private final String code;
    private final String label;

    AiPromptCategory(String code, String label) {
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
        return Arrays.stream(values()).map(AiPromptCategory::code).collect(Collectors.toUnmodifiableSet());
    }
}
