package com.example.batch.common.exception;

import com.example.batch.common.enums.ResultCode;

public class SystemException extends RuntimeException {

    private final ResultCode code;

    public SystemException(String message) {
        super(message);
        this.code = ResultCode.SYSTEM_ERROR;
    }

    public SystemException(ResultCode code, String message) {
        super(message);
        this.code = code;
    }

    public SystemException(String message, Throwable cause) {
        super(message, cause);
        this.code = ResultCode.SYSTEM_ERROR;
    }

    public SystemException(ResultCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ResultCode getCode() {
        return code;
    }
}
