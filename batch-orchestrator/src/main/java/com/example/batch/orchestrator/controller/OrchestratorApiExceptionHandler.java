package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice(basePackageClasses = LaunchController.class)
public class OrchestratorApiExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<CommonResponse<Void>> handleBizException(BizException exception) {
        log.warn("orchestrator biz exception", exception);
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<CommonResponse<Void>> handleSystemException(SystemException exception) {
        log.error("orchestrator system exception", exception);
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<CommonResponse<Void>> handleResponseStatus(
            ResponseStatusException exception) {
        log.warn("orchestrator response status exception", exception);
        ResultCode code =
                switch (exception.getStatusCode().value()) {
                    case 404 -> ResultCode.NOT_FOUND;
                    case 409 -> ResultCode.CONFLICT;
                    default -> ResultCode.SYSTEM_ERROR;
                };
        String message =
                exception.getReason() != null ? exception.getReason() : code.defaultMessage();
        return ResponseEntity.status(exception.getStatusCode())
                .body(CommonResponse.failure(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<Void>> handleException(Exception exception) {
        log.error("unexpected orchestrator exception", exception);
        return ResponseEntity.internalServerError()
                .body(
                        CommonResponse.failure(
                                ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.defaultMessage()));
    }
}
