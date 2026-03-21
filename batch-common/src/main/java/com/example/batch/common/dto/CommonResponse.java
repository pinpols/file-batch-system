package com.example.batch.common.dto;

import com.example.batch.common.enums.ResultCode;

public record CommonResponse<T>(
        ResultCode code,
        String message,
        T data,
        ResponseMeta meta
) {
    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(ResultCode.SUCCESS, ResultCode.SUCCESS.defaultMessage(), data, null);
    }

    public static <T> CommonResponse<T> success(T data, ResponseMeta meta) {
        return new CommonResponse<>(ResultCode.SUCCESS, ResultCode.SUCCESS.defaultMessage(), data, meta);
    }

    public static <T> CommonResponse<T> success() {
        return success(null);
    }

    public static <T> CommonResponse<T> failure(ResultCode code, String message) {
        return new CommonResponse<>(code, message, null, null);
    }

    public static <T> CommonResponse<T> failure(ResultCode code, String message, ResponseMeta meta) {
        return new CommonResponse<>(code, message, null, meta);
    }
}
