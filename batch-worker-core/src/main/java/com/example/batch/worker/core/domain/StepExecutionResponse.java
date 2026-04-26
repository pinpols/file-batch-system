package com.example.batch.worker.core.domain;

/** Pipeline 单步执行的返回结果。 统一封装成功标志、业务状态码与描述信息； 提供 {@link #successResponse()} 工厂方法以减少成功路径的样板代码。 */
public record StepExecutionResponse(boolean success, String code, String message) {
  public static StepExecutionResponse successResponse() {
    return new StepExecutionResponse(true, "SUCCESS", "ok");
  }
}
