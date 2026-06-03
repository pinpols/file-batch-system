package com.example.batch.console.service;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
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

  /**
   * 透传 orchestrator 内部 API 返回的 {@code CommonResponse<T>} envelope。
   *
   * <p>orchestrator 的 internal controller 返 {@code CommonResponse.success(payload)}, console-bff 用
   * {@code RestClient.retrieve().body(Map.class)} 反序列化得到 {@code {success,data,code,message,...}}。
   * 如果再调 {@link #success(Object)} 整体当 data 包一层 → FE 看到 {@code
   * {success:true,data:{success:true,data:{...真实业务负载...}}}} —— ADR-026 dry-run e2e
   * (integration-adr-features:18) 断言 {@code data.findings} 时落到外层 {@code data.data.findings} 而误判
   * success=false。
   *
   * <p>本方法解 envelope 的 {@code data} 字段透传;若 {@code success=false} 则抛 BizException 把内层 code / message
   * 透传出去。
   *
   * @param resp orchestrator 返回的反序列化 Map (允许 null,表示无响应体)
   * @param <T> 期望的 data payload 类型 (调用方自负 cast)
   * @return 内层 data 透传后的 console envelope
   */
  @SuppressWarnings("unchecked")
  public <T> CommonResponse<T> forwardOrchestrator(Map<String, Object> resp) {
    if (resp == null) {
      return success(null);
    }
    Object successFlag = resp.get("success");
    if (successFlag instanceof Boolean b && !b) {
      Object code = resp.get("code");
      Object message = resp.get("message");
      throw BizException.of(
          ResultCode.SYSTEM_ERROR,
          "error.console.orchestrator_forward_failed",
          code == null ? "" : code.toString(),
          message == null ? "" : message.toString());
    }
    return success((T) resp.get("data"));
  }
}
