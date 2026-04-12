package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileCompressType {
    NONE("NONE", "无压缩"),
    ZIP("ZIP", "ZIP"),
    GZIP("GZIP", "GZIP");

    private final String code;
    private final String label;

    FileCompressType(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(FileCompressType::code).collect(Collectors.toUnmodifiableSet());
    }
}
