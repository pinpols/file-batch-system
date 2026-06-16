package com.example.batch.ext.sample.handlers;

import com.example.batch.sdk.handler.SdkAbstractDispatchHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-036 Dispatch 模板 sample —— tenant → external push demo。模拟"查 3 条 → 逐条 push"。
 *
 * <p>真业务:selectPayload 查待推送行,buildRequest 拼外部请求,push 调 HTTP/SFTP,onResponse 处理响应。单条失败不中断整批。
 */
public class DispatchEchoHandler extends SdkAbstractDispatchHandler<String> {

  private static final Logger log = LoggerFactory.getLogger(DispatchEchoHandler.class);

  @Override
  public String taskType() {
    return "sample_dispatch_echo";
  }

  @Override
  protected List<String> selectPayload(SdkTaskContext ctx) {
    log.info("dispatch sample: selectPayload for tenant={}", ctx.tenantId());
    return List.of("msg-1", "msg-2", "msg-3");
  }

  @Override
  protected Object buildRequest(SdkTaskContext ctx, String item) {
    return "{\"payload\":\"" + item + "\"}";
  }

  @Override
  protected Object push(SdkTaskContext ctx, Object request) {
    // 真业务:HTTP POST / SFTP put;这里只 log + 返回模拟响应
    log.info("dispatch sample: push request={}", request);
    return "200 OK";
  }

  @Override
  protected void onResponse(SdkTaskContext ctx, String item, Object response) {
    log.info("dispatch sample: onResponse item={} response={}", item, response);
  }
}
