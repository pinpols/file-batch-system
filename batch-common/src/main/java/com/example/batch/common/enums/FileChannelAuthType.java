package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileChannelAuthType {
    NONE("NONE", "无"),
    PASSWORD("PASSWORD", "用户名密码"),
    KEY_PAIR("KEY_PAIR", "密钥对"),
    TOKEN("TOKEN", "Token"),
    OAUTH2("OAUTH2", "OAuth2"),
    CUSTOM("CUSTOM", "自定义");

    private final String code;
    private final String label;

    FileChannelAuthType(String code, String label) { this.code = code; this.label = label; }

    public String code() { return code; }
    public String label() { return label; }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(FileChannelAuthType::code).collect(Collectors.toUnmodifiableSet());
    }
}
