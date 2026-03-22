package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice(basePackageClasses = LaunchController.class)
public class OrchestratorApiExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<Void>> handleException(Exception exception) {
        log.error("unexpected orchestrator exception", exception);
        return ResponseEntity.internalServerError()
                .body(CommonResponse.failure(ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.defaultMessage()));
    }
}
