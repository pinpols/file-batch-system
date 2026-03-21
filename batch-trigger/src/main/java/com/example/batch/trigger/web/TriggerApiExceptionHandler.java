package com.example.batch.trigger.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TriggerApiExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<CommonResponse<Void>> handleBizException(BizException exception) {
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<CommonResponse<Void>> handleSystemException(SystemException exception) {
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<CommonResponse<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest()
                .body(CommonResponse.failure(ResultCode.MISSING_IDEMPOTENCY_KEY, exception.getMessage()));
    }
}
