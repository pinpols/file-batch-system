package com.example.batch.console.support;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import org.springframework.stereotype.Component;

@Component
public class ConsoleResponseFactory {

    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    public ConsoleResponseFactory(ConsoleRequestMetadataResolver requestMetadataResolver) {
        this.requestMetadataResolver = requestMetadataResolver;
    }

    public <T> CommonResponse<T> success(T data) {
        return CommonResponse.success(data, requestMetadataResolver.responseMeta());
    }

    public <T> CommonResponse<T> failure(ResultCode code, String message) {
        return CommonResponse.failure(code, message, requestMetadataResolver.responseMeta());
    }
}
