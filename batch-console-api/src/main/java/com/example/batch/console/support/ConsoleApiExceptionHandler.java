package com.example.batch.console.support;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@RequiredArgsConstructor
public class ConsoleApiExceptionHandler {

    private final ConsoleResponseFactory responseFactory;

    @ExceptionHandler(BizException.class)
    public ResponseEntity<?> handleBizException(BizException exception) {
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(responseFactory.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<?> handleSystemException(SystemException exception) {
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(responseFactory.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(responseFactory.failure(ResultCode.VALIDATION_ERROR, message.isBlank() ? ResultCode.VALIDATION_ERROR.defaultMessage() : message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolationException(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(responseFactory.failure(ResultCode.VALIDATION_ERROR, exception.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<?> handleMissingRequestHeaderException(MissingRequestHeaderException exception) {
        ResultCode code = CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
                ? ResultCode.MISSING_IDEMPOTENCY_KEY
                : ResultCode.INVALID_ARGUMENT;
        return ResponseEntity.badRequest()
                .body(responseFactory.failure(code, exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception exception) {
        return ResponseEntity.internalServerError()
                .body(responseFactory.failure(ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.defaultMessage()));
    }
}
