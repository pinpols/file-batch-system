package com.example.batch.securityscan;

import java.util.Locale;

public enum ScanMode {
    ALL,
    SECRET,
    DEPS,
    SAST,
    FILESYSTEM,
    IMAGE,
    DAST;

    public static ScanMode fromCli(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "secret" -> SECRET;
            case "deps" -> DEPS;
            case "sast" -> SAST;
            case "filesystem", "fs" -> FILESYSTEM;
            case "image" -> IMAGE;
            case "dast" -> DAST;
            default -> throw new IllegalArgumentException("Unsupported mode: " + value);
        };
    }
}
