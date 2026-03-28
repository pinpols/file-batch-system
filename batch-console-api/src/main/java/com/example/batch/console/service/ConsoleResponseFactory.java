package com.example.batch.console.service;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.common.enums.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 封装控制台统一响应体，自动填充 requestId / traceId 等 {@link com.example.batch.common.dto.ResponseMeta}。 */
@Component
@RequiredArgsConstructor
public class ConsoleResponseFactory {

    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    /** 成功响应并附带当前请求的元数据。 */
    public <T> CommonResponse<T> success(T data) {
        return CommonResponse.success(data, requestMetadataResolver.responseMeta());
    }

    /** 失败响应并附带元数据。 */
    public <T> CommonResponse<T> failure(ResultCode code, String message) {
        return CommonResponse.failure(code, message, requestMetadataResolver.responseMeta());
    }
}
