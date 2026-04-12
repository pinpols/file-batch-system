package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileChannelType {
    SFTP("SFTP", "SFTP"),
    API("API", "API"),
    API_PUSH("API_PUSH", "API 推送"),
    EMAIL("EMAIL", "邮件"),
    NAS("NAS", "NAS"),
    OSS("OSS", "对象存储"),
    LOCAL("LOCAL", "本地");

    private final String code;
    private final String label;

    FileChannelType(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(FileChannelType::code).collect(Collectors.toUnmodifiableSet());
    }
}
