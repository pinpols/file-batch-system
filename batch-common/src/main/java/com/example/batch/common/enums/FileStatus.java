package com.example.batch.common.enums;

public enum FileStatus {
    RECEIVED,
    PARSING,
    PARSED,
    VALIDATED,
    LOADED,
    GENERATED,
    DISPATCHING,
    DISPATCHED,
    ARCHIVED,
    FAILED,
    DELETED;

    public static FileStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return FileStatus.valueOf(code.trim().toUpperCase());
    }
}
