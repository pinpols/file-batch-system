package com.example.batch.common.exception;

import com.example.batch.common.enums.ResultCode;

public class BizException extends RuntimeException {

  private final ResultCode code;

  public BizException(ResultCode code, String message) {
    super(message);
    this.code = code;
  }

  public BizException(ResultCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public ResultCode getCode() {
    return code;
  }
}
