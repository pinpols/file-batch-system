package com.example.template.handlers;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示范 handler 1 / 2 — Echo:把 task parameters 原样回填到结果。
 *
 * <p>租户起步参考:复制本类、改 {@link #taskType()} 返回值 + 把 execute 内换成自家业务即可。
 * 声明 {@code @Component} 即被 starter 自动 register,无需手写 {@code client.register(...)}.
 */
@Component
public class EchoHandler implements SdkTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(EchoHandler.class);

  @Override
  public String taskType() {
    return "template.echo";
  }

  @Override
  public SdkTaskResult execute(SdkTaskContext ctx) {
    log.info("echo taskId={} params={}", ctx.taskId(), ctx.parameters());
    return SdkTaskResult.ok("echoed", Map.copyOf(ctx.parameters()));
  }
}
